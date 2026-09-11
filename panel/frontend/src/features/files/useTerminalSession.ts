import {useCallback, useEffect, useRef, useState} from 'react'

import {csrfHeaders} from '@/shell'

import type {TerminalExit, TerminalReady, TerminalView} from './fileTypes'
import {
  MAX_FRAME_BYTES,
  exitFrame,
  keystrokeFrame,
  readFrame,
  resizeFrame,
} from './terminalFrames'

/**
 * One shell: opening it, reading it, typing into it, and knowing when it is gone.
 *
 * A `POST` mints the session - that is the state change, so it keeps the CSRF token and
 * the audit entry - and everything after it is one WebSocket. Output used to be an
 * `EventSource` and input a `POST` per keystroke, and the second half of that was the
 * problem: a request per character, each one through the whole security filter chain and a
 * lookup of the signed-in account. A socket is authorised once, at the handshake, and a
 * keystroke afterwards is a few bytes on a connection that is already open.
 *
 * **Typing is not batched any more, and that is the point.** Coalescing existed to make
 * requests less frequent; with no request there is nothing to coalesce, and a frame goes
 * out the moment xterm produces it. Ordering is the socket's, which is what a shell needs
 * - `ls` arriving as `sl` is worse than a slow terminal. A paste larger than the panel's
 * 64 kB ceiling is split, in order, across frames.
 *
 * **A dropped socket is worth retrying, briefly.** The panel ends a shell when its
 * connection closes, so a reconnect only wins where the panel has not noticed yet - which
 * is the common mobile case, a phone moving from wi-fi to mobile data, where the old TCP
 * connection is dead on this side and still open on the other. The reconnect carries the
 * same session id, the panel swaps the reader, and the customer keeps their command. The
 * browser does not expose the handshake's status code to script, so a refusal and a
 * blipped tunnel look identical here: the attempt budget is what ends it.
 *
 * Over three hundred lines, deliberately (AGENTS.md §3.2). Opening, reading, writing,
 * resizing and closing are one session's lifecycle sharing one connection; split across
 * files they would share it through a context, and a terminal whose input handler can be
 * mounted without its output stream is the class of bug this replaces. The frame format
 * lives in `terminalFrames.ts` and the byte conversions in `terminalCodec.ts`.
 */
export type TerminalPhase = 'idle' | 'opening' | 'live' | 'ended' | 'failed'

export interface TerminalSessionState {
  phase: TerminalPhase
  session: TerminalView | null
  /** The size the PTY reported once the socket attached. */
  ready: TerminalReady | null
  exit: TerminalExit | null
  /** Why opening failed, or why the shell ended badly. */
  error: string | null
  /** True while the socket is down and the shell may still be there. */
  reconnecting: boolean
  open: (columns: number, rows: number) => void
  send: (bytes: Uint8Array) => void
  resize: (columns: number, rows: number) => void
  close: () => void
  /** Try the socket again now, rather than waiting out the backoff. */
  reconnect: () => void
}

/** Enough for a slow network to come back, short enough that nobody watches a dead screen. */
const RECONNECT_DELAYS_MS = [300, 700, 1500, 3000, 5000]

const RESIZE_DEBOUNCE_MS = 150

/**
 * Keystrokes held while the socket is down.
 *
 * Small on purpose: this is what somebody types into a terminal that is not responding,
 * and replaying a minute of blind typing into a shell that came back is worse than
 * dropping it.
 */
const MAX_QUEUED_INPUT_BYTES = 8 * 1024

export function useTerminalSession(
  serviceId: string,
  onOutput: (bytes: Uint8Array) => void,
): TerminalSessionState {
  const [phase, setPhase] = useState<TerminalPhase>('idle')
  const [session, setSession] = useState<TerminalView | null>(null)
  const [ready, setReady] = useState<TerminalReady | null>(null)
  const [exit, setExit] = useState<TerminalExit | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [reconnecting, setReconnecting] = useState(false)
  /** Bumped to open a socket; the only thing that does. */
  const [generation, setGeneration] = useState(0)

  const sink = useRef(onOutput)
  sink.current = onOutput

  const socket = useRef<WebSocket | null>(null)
  /** Consecutive failed connections, which is both the delay and the budget. */
  const attempts = useRef(0)
  const queued = useRef<Uint8Array[]>([])
  const queuedBytes = useRef(0)
  const lastSize = useRef<{columns: number; rows: number} | null>(null)
  const resizeTimer = useRef<number | null>(null)
  const retryTimer = useRef<number | null>(null)
  /** Set once the shell is over, so a closing socket does not look like a drop. */
  const finished = useRef(false)

  const base = `/services/${serviceId}/terminal`

  const write = useCallback((frame: Uint8Array) => {
    const live = socket.current
    if (live === null || live.readyState !== WebSocket.OPEN) {
      return false
    }
    live.send(frame)
    return true
  }, [])

  const send = useCallback(
    (bytes: Uint8Array) => {
      if (bytes.length === 0 || finished.current) {
        return
      }
      for (let start = 0; start < bytes.length; start += MAX_FRAME_BYTES) {
        const slice = bytes.subarray(start, start + MAX_FRAME_BYTES)
        if (write(keystrokeFrame(slice))) {
          continue
        }
        // The socket is between connections. Hold a little and flush on the next open.
        queued.current.push(slice.slice())
        queuedBytes.current += slice.length
        while (queuedBytes.current > MAX_QUEUED_INPUT_BYTES && queued.current.length > 1) {
          queuedBytes.current -= queued.current.shift()?.length ?? 0
        }
      }
    },
    [write],
  )

  const sendSize = useCallback(() => {
    const size = lastSize.current
    if (size !== null) {
      write(resizeFrame(size.columns, size.rows))
    }
  }, [write])

  const resize = useCallback(
    (columns: number, rows: number) => {
      if (columns <= 0 || rows <= 0) {
        return
      }
      lastSize.current = {columns, rows}
      if (resizeTimer.current !== null) {
        window.clearTimeout(resizeTimer.current)
      }
      // On a phone this fires every time the software keyboard opens or closes, and again
      // for every frame of the animation in between.
      resizeTimer.current = window.setTimeout(() => {
        resizeTimer.current = null
        sendSize()
      }, RESIZE_DEBOUNCE_MS)
    },
    [sendSize],
  )

  /*
   * The software keyboard. xterm reports a resize only when the number of columns or rows
   * changes, so a keyboard that shrinks the box without changing the grid produces no
   * event - and neither does one that changed the grid while the socket happened to be
   * down. Re-announcing the size the renderer settled on is idempotent and costs five
   * bytes; a PTY that believes the wrong height is a full-screen program drawing over
   * itself.
   */
  useEffect(() => {
    const viewport = window.visualViewport
    if (!viewport || phase !== 'live') {
      return
    }
    let pending = 0
    const announce = () => {
      window.clearTimeout(pending)
      pending = window.setTimeout(sendSize, RESIZE_DEBOUNCE_MS)
    }
    viewport.addEventListener('resize', announce)
    return () => {
      window.clearTimeout(pending)
      viewport.removeEventListener('resize', announce)
    }
  }, [phase, sendSize])

  const finish = useCallback((frame: TerminalExit) => {
    finished.current = true
    setExit(frame)
    setPhase('ended')
    setReconnecting(false)
  }, [])

  // The socket. Torn down and rebuilt whenever the session changes or a retry is armed,
  // which is the only way it is ever reopened - a reconnect bumps `attempt`.
  useEffect(() => {
    if (session === null || finished.current) {
      return
    }
    const url = new URL(`${base}/socket`, window.location.href)
    url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
    url.searchParams.set('session', session.sessionId)

    const live = new WebSocket(url)
    live.binaryType = 'arraybuffer'
    socket.current = live

    live.onopen = () => {
      attempts.current = 0
      setReconnecting(false)
      setError(null)
      // The PTY's size is state on the node. A resize sent while this socket was down was
      // dropped by a socket that no longer existed, so say it again.
      sendSize()
      for (const held of queued.current) {
        live.send(keystrokeFrame(held))
      }
      queued.current = []
      queuedBytes.current = 0
    }

    live.onmessage = (event: MessageEvent<ArrayBuffer>) => {
      const message = readFrame(event.data)
      if (message === null) {
        return
      }
      if (message.kind === 'output') {
        // A view onto this message's own buffer, which nothing else will reuse - xterm
        // keeps a reference to it while it works through its write queue.
        sink.current(message.data)
        return
      }
      if (message.kind === 'ready') {
        setReady(message.ready)
        return
      }
      finish(message.exit)
      live.close(1000, 'the shell ended')
    }

    live.onclose = () => {
      socket.current = null
      if (finished.current) {
        return
      }
      const delay = RECONNECT_DELAYS_MS[attempts.current]
      if (delay === undefined) {
        // Out of budget. The shell is gone - the panel ends one whose reader left - and
        // saying so is better than a screen that quietly stops accepting keys.
        finish({
          code: -1,
          reason:
            'the connection to the shell could not be re-established. The shell has ended; ' +
            'nothing in the container was stopped',
        })
        return
      }
      attempts.current += 1
      setReconnecting(true)
      retryTimer.current = window.setTimeout(
        () => setGeneration((previous) => previous + 1),
        delay,
      )
    }

    return () => {
      live.onclose = null
      live.onmessage = null
      live.onopen = null
      if (retryTimer.current !== null) {
        window.clearTimeout(retryTimer.current)
        retryTimer.current = null
      }
      // Closing the socket is what ends the shell: the panel releases a session whose
      // reader has gone. A tab being closed does the same thing without any code here,
      // which is why there is no unload handler in this file any more.
      live.close(1000, 'the page moved on')
      socket.current = null
    }
  }, [base, session, generation, finish, sendSize])

  const open = useCallback(
    (columns: number, rows: number) => {
      setPhase('opening')
      setError(null)
      setExit(null)
      setReady(null)
      setReconnecting(false)
      attempts.current = 0
      finished.current = false
      queued.current = []
      queuedBytes.current = 0
      lastSize.current = {columns, rows}
      const body = new FormData()
      body.append('columns', String(columns))
      body.append('rows', String(rows))
      fetch(base, {
        method: 'POST',
        headers: csrfHeaders({Accept: 'application/json'}),
        credentials: 'same-origin',
        body,
      })
        .then(async (response) => {
          if (!response.ok) {
            throw new Error(await openFailure(response))
          }
          return (await response.json()) as TerminalView
        })
        .then((view) => {
          setSession(view)
          setPhase('live')
        })
        .catch((cause: unknown) => {
          setPhase('failed')
          setError(cause instanceof Error ? cause.message : 'The shell could not be started.')
        })
    },
    [base],
  )

  const close = useCallback(() => {
    // The frame first, then the socket: the panel ends the PTY either way, and an explicit
    // end is what turns "this browser went away" into "the customer closed their shell" in
    // the audit trail.
    finished.current = true
    write(exitFrame())
    socket.current?.close(1000, 'the customer ended the session')
    socket.current = null
    setSession(null)
    setPhase('idle')
    setReady(null)
    setReconnecting(false)
  }, [write])

  const reconnect = useCallback(() => {
    if (retryTimer.current !== null) {
      window.clearTimeout(retryTimer.current)
      retryTimer.current = null
    }
    // A person pressing the button is not a failed attempt, so the budget starts again.
    attempts.current = 0
    setGeneration((previous) => previous + 1)
  }, [])

  return {
    phase,
    session,
    ready,
    exit,
    error,
    reconnecting: reconnecting && phase === 'live',
    open,
    send,
    resize,
    close,
    reconnect,
  }
}

async function openFailure(response: Response): Promise<string> {
  if (response.status === 403) {
    return 'You do not have permission to open a shell in this service.'
  }
  if (response.status === 404) {
    return 'This service is not there any more.'
  }
  if (response.status === 409 || response.status === 503) {
    return (
      'The machine holding this service did not answer. A node that is reconnecting takes ' +
      'a few seconds; one that is offline cannot open a shell at all.'
    )
  }
  return `The shell could not be started: the panel answered ${response.status}.`
}
