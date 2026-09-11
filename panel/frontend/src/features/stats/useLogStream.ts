import {useCallback, useEffect, useRef, useState} from 'react'

import {useEventSource} from '@/shell'

import {acceptEvent, emptyTail} from './logLines'
import type {LogTail} from './logLines'
import type {LogEvent, LogSourceName} from './statsTypes'

/**
 * The container's output, tailed over Server-Sent Events.
 *
 * There is no stored log to page through - container output lives on the node in Docker's
 * rotated files, and copying it into PostgreSQL would make the panel the disk-space
 * bottleneck for every chatty application on the platform. So this is a live tail with a
 * screenful of history, and everything awkward about that is here.
 *
 * **Reconnecting is done by hand, with a cursor.** An `EventSource` reopens the same URL
 * by itself, which would replay the same `tail` lines every time a tunnel hiccups. Instead
 * a drop closes the stream and reopens it with `since` set to the last timestamp seen, so
 * the customer gets what they missed and not what they already read.
 *
 * **State is written on a timer, not per event.** A container in a boot loop produces
 * hundreds of events a second; a React update for each one would spend a phone's battery
 * re-rendering text nobody has looked at yet. Events are folded into the tail in a ref and
 * published a few times a second.
 *
 * **Pausing really pauses.** It closes the stream rather than hiding the output, because
 * a paused viewer that is still receiving is a paused viewer that runs the tab out of
 * memory. Resuming asks for everything since the last line.
 */
export interface LogStream {
  tail: LogTail
  /** True while the browser has an open stream. */
  connected: boolean
  /** The stream dropped and is being reopened. */
  reconnecting: boolean
  paused: boolean
  setPaused: (paused: boolean) => void
  clear: () => void
  /** Reopen now rather than waiting out the backoff. */
  reconnect: () => void
}

/** Lines kept in the viewer. Past this the oldest go. */
const LINE_LIMIT = 4000

/** How often the accumulated events are published to React. */
const FLUSH_MS = 120

const MAX_BACKOFF_MS = 15_000

export function useLogStream(
  serviceId: string,
  source: LogSourceName,
  subjectId: string,
  tailLines: number,
  enabled: boolean,
): LogStream {
  const [tail, setTail] = useState<LogTail>(emptyTail)
  const [paused, setPaused] = useState(false)
  const [reconnecting, setReconnecting] = useState(false)
  const [attempt, setAttempt] = useState(0)

  // Folded here and published on a timer; see the note above about chatty containers.
  const buffered = useRef<LogEvent[]>([])
  const working = useRef<LogTail>(emptyTail())
  const lastAt = useRef<string | null>(null)
  const failures = useRef(0)
  const retryTimer = useRef<number | null>(null)
  const closeStream = useRef<() => void>(() => undefined)

  const url = buildUrl(serviceId, source, subjectId, tailLines, lastAt.current, attempt)

  const stream = useEventSource<{log: LogEvent; end: LogEvent}>(
    url,
    {
      log: (event) => {
        lastAt.current = event.at
        buffered.current.push(event)
      },
      end: (event) => {
        lastAt.current = event.at
        buffered.current.push({...event, end: true})
        // The source is over. Left open, the browser would reopen the stream every few
        // seconds for a container that has exited.
        closeStream.current()
      },
    },
    {
      enabled: enabled && !paused,
      onOpen: () => {
        failures.current = 0
        setReconnecting(false)
      },
      onError: () => {
        if (working.current.ended) {
          return
        }
        setReconnecting(true)
        closeStream.current()
        scheduleRetry()
      },
    },
  )
  closeStream.current = stream.close

  const scheduleRetry = useCallback(() => {
    if (retryTimer.current !== null) {
      return
    }
    const wait = Math.min(MAX_BACKOFF_MS, 1000 * 2 ** failures.current) + Math.random() * 500
    failures.current += 1
    retryTimer.current = window.setTimeout(() => {
      retryTimer.current = null
      // A new attempt number changes the URL, which is what makes the hook open a fresh
      // connection - this time carrying `since`.
      setAttempt((count) => count + 1)
    }, wait)
  }, [])

  useEffect(() => {
    const timer = window.setInterval(() => {
      if (buffered.current.length === 0) {
        return
      }
      const events = buffered.current
      buffered.current = []
      let next = working.current
      for (const event of events) {
        next = acceptEvent(next, event, LINE_LIMIT)
      }
      working.current = next
      setTail(next)
    }, FLUSH_MS)
    return () => window.clearInterval(timer)
  }, [])

  useEffect(
    () => () => {
      if (retryTimer.current !== null) {
        window.clearTimeout(retryTimer.current)
      }
    },
    [],
  )

  const clear = useCallback(() => {
    buffered.current = []
    working.current = {...emptyTail(), ended: working.current.ended}
    setTail(working.current)
  }, [])

  const reconnect = useCallback(() => {
    if (retryTimer.current !== null) {
      window.clearTimeout(retryTimer.current)
      retryTimer.current = null
    }
    failures.current = 0
    working.current = {...working.current, ended: false}
    setTail(working.current)
    setAttempt((count) => count + 1)
  }, [])

  return {
    tail,
    connected: stream.status === 'open',
    reconnecting: reconnecting && !tail.ended,
    paused,
    setPaused,
    clear,
    reconnect,
  }
}

/**
 * The stream's URL.
 *
 * `since` turns a reconnect into a continuation. `tail` drops to one line once there is a
 * cursor: the node filters by timestamp, and asking for two hundred lines of history that
 * have already been read is two hundred lines the customer sees twice.
 *
 * `attempt` is in the query string on purpose. It is what makes the URL change so the hook
 * opens a new connection, and a reconnect to a byte-identical URL would be served from the
 * browser's own retry instead - which is the thing being replaced.
 */
function buildUrl(
  serviceId: string,
  source: LogSourceName,
  subjectId: string,
  tailLines: number,
  since: string | null,
  attempt: number,
): string {
  const query = new URLSearchParams({
    source,
    subjectId,
    tail: String(since === null ? tailLines : 1),
  })
  if (since !== null) {
    query.set('since', since)
  }
  if (attempt > 0) {
    query.set('attempt', String(attempt))
  }
  return `/services/${serviceId}/logs/stream?${query.toString()}`
}
