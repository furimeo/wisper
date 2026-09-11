import {useCallback, useEffect, useRef, useState} from 'react'

import {useEventSource} from '@/shell'
import type {SseStatus} from '@/shell'

import type {DeploymentLog} from './deployTypes'

/**
 * The live build log of one deployment, as a growing array.
 *
 * Three things this does that a bare `EventSource` does not, and each of them is the
 * difference between a log viewer that works during a real build and one that works in a
 * demo.
 *
 * **It batches.** A build printing a thousand lines a second would otherwise be a
 * thousand React renders a second, which on a phone is a frozen tab. Arrivals are
 * collected and flushed once per animation frame, so the render rate is bounded by the
 * display and not by `npm ci`.
 *
 * **It resumes exactly.** Every line carries its sequence, the server sends it as the SSE
 * id, and the browser echoes the last one back in `Last-Event-ID` when the tunnel drops
 * the connection. A line whose sequence is not past what is already held is discarded, so
 * a reconnect that overlaps replays nothing twice.
 *
 * **It has a ceiling.** A runaway build can print more than a phone can hold. Past
 * `MAX_LINES` the oldest are dropped and counted, and the viewer says so - a gap somebody
 * is told about can be worked around, a silent one cannot.
 */

/** How many lines are kept in memory before the oldest start being dropped. */
const MAX_LINES = 100_000

/** How many are dropped when the ceiling is hit, so trimming is rare rather than constant. */
const DROP_BATCH = 10_000

export interface DeploymentLogFeed {
  /** Oldest first, contiguous by sequence. */
  lines: DeploymentLog[]
  /** How many lines were dropped off the front to stay under the ceiling. */
  dropped: number
  status: SseStatus
  /** The server said the build is over and nothing more will arrive. */
  ended: boolean
  /** Opens the stream again after it ended or gave up. */
  reopen: () => void
}

interface Held {
  lines: DeploymentLog[]
  dropped: number
}

/**
 * @param initial the rows the controller rendered with the page, oldest first
 * @param cursor  `logCursor` - the sequence the page already holds up to. Read once: the
 *                stream's URL must not change as lines arrive, or the browser would tear
 *                the connection down and reopen it on every batch
 */
export function useDeploymentLog(
  serviceId: string,
  deploymentId: string,
  initial: DeploymentLog[],
  cursor: number,
): DeploymentLogFeed {
  const start = useRef(cursor)
  const [held, setHeld] = useState<Held>(() => ({lines: initial, dropped: 0}))
  const [ended, setEnded] = useState(false)

  const pending = useRef<DeploymentLog[]>([])
  const frame = useRef(0)

  const flush = useCallback(() => {
    frame.current = 0
    const batch = pending.current
    if (batch.length === 0) {
      return
    }
    pending.current = []
    setHeld((current) => absorb(current, batch, start.current))
  }, [])

  const receive = useCallback(
    (line: DeploymentLog) => {
      pending.current.push(line)
      if (frame.current === 0) {
        frame.current = window.requestAnimationFrame(flush)
      }
    },
    [flush],
  )

  // The close handler is reached from inside a listener created before the connection
  // exists, so it goes through a ref rather than through the binding.
  const closer = useRef<() => void>(() => {})

  const url = `/services/${serviceId}/deployments/${deploymentId}/log?after=${start.current}`

  const connection = useEventSource<{line: DeploymentLog; end: number}>(
    url,
    {
      line: receive,
      end: () => {
        // Flush before closing: the last lines of a failed build are the ones being
        // read, and they may still be sitting in the frame that has not run yet.
        flush()
        setEnded(true)
        closer.current()
      },
    },
    {decode: {end: (raw) => Number(raw)}},
  )
  closer.current = connection.close

  useEffect(
    () => () => {
      if (frame.current !== 0) {
        window.cancelAnimationFrame(frame.current)
        frame.current = 0
      }
    },
    [],
  )

  const reopen = useCallback(() => {
    setEnded(false)
    connection.reopen()
  }, [connection])

  return {
    lines: held.lines,
    dropped: held.dropped,
    status: connection.status,
    ended,
    reopen,
  }
}

/**
 * Appends a batch, dropping anything already held and trimming the front if the ceiling
 * is reached.
 *
 * Returns the same object when nothing was added, so a redelivered batch does not
 * re-render the viewer.
 */
function absorb(current: Held, batch: DeploymentLog[], floor: number): Held {
  const last = current.lines[current.lines.length - 1]
  let cursor = last === undefined ? floor : last.sequence

  const added: DeploymentLog[] = []
  for (const line of batch) {
    if (line.sequence <= cursor) {
      continue
    }
    added.push(line)
    cursor = line.sequence
  }
  if (added.length === 0) {
    return current
  }

  const lines = current.lines.concat(added)
  if (lines.length <= MAX_LINES) {
    return {lines, dropped: current.dropped}
  }
  const remove = lines.length - MAX_LINES + DROP_BATCH
  return {lines: lines.slice(remove), dropped: current.dropped + remove}
}
