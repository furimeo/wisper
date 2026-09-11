import {useCallback, useEffect, useRef, useState} from 'react'

import {csrfHeaders, useEventSource} from '@/shell'

import type {TerminalAck, TerminalExit, TerminalReady, TerminalView} from './fileTypes'
import {bytesToBase64, concatBytes, decodeBase64ToBytes} from './terminalCodec'

/**
 * One shell: opening it, reading it, typing into it, and knowing when it is gone.
 *
 * The transport is the one in `docs/contracts/pages.md` §7 - a `POST` mints the session,
 * an `EventSource` carries the output, and three small posts carry the three frame types
 * the browser can send. There is deliberately no "write whatever" call: an unframed write
 * is what broke the predecessor's terminal, where a resize was indistinguishable from
 * keystrokes.
 *
 * **Keystrokes are batched, and stay in order.** One request per character would be a
 * request per 40ms of typing; instead they are collected for a few milliseconds and sent
 * as one frame, and each frame waits for the previous one - a PTY that receives `ls` as
 * `sl` is worse than a slow one. A frame is split at the panel's 64 kB ceiling, which is
 * what makes pasting a config file into a shell work.
 *
 * **A dropped stream is not a dead shell.** The panel detaches the browser without ending
 * the PTY and buffers the output, so a tunnel hiccup on a train reconnects into the same
 * session with the last screen intact. What does end a shell is the node saying so, and
 * that arrives two ways: the `exit` frame, and `TerminalAck.finished` on the next
 * keystroke - which is sooner for a browser whose stream has already gone.
 *
 * Over three hundred lines, deliberately (AGENTS.md §3.2). Opening, reading, writing,
 * resizing and closing are one session's lifecycle sharing one piece of state; split
 * across files they would share it through a context, and a terminal whose input handler
 * can be mounted without its output stream is the class of bug this replaces.
 */
export type TerminalPhase = 'idle' | 'opening' | 'live' | 'ended' | 'failed'

export interface TerminalSessionState {
  phase: TerminalPhase
  session: TerminalView | null
  /** The size the PTY reported once the stream attached. */
  ready: TerminalReady | null
  exit: TerminalExit | null
  /** Why opening failed, or why the shell ended badly. */
  error: string | null
  /** True while the event stream is not connected but the shell is still open. */
  reconnecting: boolean
  open: (columns: number, rows: number) => void
  send: (bytes: Uint8Array) => void
  resize: (columns: number, rows: number) => void
  close: () => void
  /** Try the output stream again after it dropped. */
  retryStream: () => void
}

/** The panel refuses a larger frame; a paste of a whole file is split across several. */
const MAX_FRAME_BYTES = 64 * 1024

/** Long enough to coalesce a burst of typing, short enough to feel immediate. */
const FLUSH_MS = 12

const RESIZE_DEBOUNCE_MS = 150

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

  const sink = useRef(onOutput)
  sink.current = onOutput

  const pending = useRef<Uint8Array[]>([])
  const flushTimer = useRef<number | null>(null)
  const inFlight = useRef<Promise<void>>(Promise.resolve())
  const resizeTimer = useRef<number | null>(null)
  const sessionRef = useRef<TerminalView | null>(null)
  sessionRef.current = session

  const base = `/services/${serviceId}/terminal`
  const streaming = session !== null && phase === 'live'

  const stream = useEventSource<{out: string; ready: TerminalReady; exit: TerminalExit}>(
    session ? `${base}/${encodeURIComponent(session.sessionId)}/stream` : null,
    {
      out: (encoded) => sink.current(decodeBase64ToBytes(encoded)),
      ready: (frame) => {
        setReady(frame)
        setReconnecting(false)
      },
      exit: (frame) => {
        setExit(frame)
        setPhase('ended')
      },
    },
    {
      // `out` is a bare base64 string, not JSON - `LiveTerminals` sends the encoded bytes
      // as the event data. Parsing it as JSON would throw on every frame.
      decode: {out: (raw) => raw},
      enabled: streaming,
      onOpen: () => setReconnecting(false),
      onError: () => setReconnecting(true),
    },
  )

  const finish = useCallback((frame: TerminalExit) => {
    setExit(frame)
    setPhase('ended')
  }, [])

  const post = useCallback(
    async (path: string, fields: Record<string, string>, keepalive = false) => {
      const body = new FormData()
      for (const [name, value] of Object.entries(fields)) {
        body.append(name, value)
      }
      const response = await fetch(path, {
        method: 'POST',
        headers: csrfHeaders({Accept: 'application/json'}),
        credentials: 'same-origin',
        body,
        keepalive,
      })
      if (!response.ok) {
        throw new Error(
          response.status === 404
            ? 'This shell is no longer open. The node ended it, or the panel restarted.'
            : `The panel answered ${response.status}.`,
        )
      }
      return (await response.json()) as TerminalAck
    },
    [],
  )

  const flush = useCallback(() => {
    flushTimer.current = null
    const current = sessionRef.current
    if (!current || pending.current.length === 0) {
      return
    }
    const buffered = concatBytes(pending.current)
    pending.current = []

    const frames: Uint8Array[] = []
    for (let start = 0; start < buffered.length; start += MAX_FRAME_BYTES) {
      frames.push(buffered.subarray(start, start + MAX_FRAME_BYTES))
    }

    // Chained rather than fired together: two frames in flight at once can arrive at the
    // PTY in the wrong order, and a shell does not forgive that.
    inFlight.current = inFlight.current
      .then(async () => {
        for (const frame of frames) {
          const ack = await post(`${base}/${encodeURIComponent(current.sessionId)}/input`, {
            data: bytesToBase64(frame),
          })
          if (ack.finished) {
            finish({code: ack.exitCode, reason: ack.exitReason})
            return
          }
        }
      })
      .catch((cause: unknown) => {
        finish({
          code: -1,
          reason: cause instanceof Error ? cause.message : 'the connection to the shell failed',
        })
      })
  }, [base, post, finish])

  const send = useCallback(
    (bytes: Uint8Array) => {
      if (bytes.length === 0) {
        return
      }
      pending.current.push(bytes)
      if (flushTimer.current === null) {
        flushTimer.current = window.setTimeout(flush, FLUSH_MS)
      }
    },
    [flush],
  )

  const resize = useCallback(
    (columns: number, rows: number) => {
      const current = sessionRef.current
      if (!current || columns <= 0 || rows <= 0) {
        return
      }
      if (resizeTimer.current !== null) {
        window.clearTimeout(resizeTimer.current)
      }
      // On a phone this fires every time the software keyboard opens or closes, and again
      // for every frame of the animation in between.
      resizeTimer.current = window.setTimeout(() => {
        resizeTimer.current = null
        void post(`${base}/${encodeURIComponent(current.sessionId)}/resize`, {
          columns: String(columns),
          rows: String(rows),
        }).catch(() => {
          // A resize that did not land is a redraw at the wrong width, not a lost shell.
          // The next keystroke reports whether the session is really gone.
        })
      }, RESIZE_DEBOUNCE_MS)
    },
    [base, post],
  )

  const open = useCallback(
    (columns: number, rows: number) => {
      setPhase('opening')
      setError(null)
      setExit(null)
      setReady(null)
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
    const current = sessionRef.current
    setSession(null)
    setPhase('idle')
    setReady(null)
    if (!current) {
      return
    }
    void post(`${base}/${encodeURIComponent(current.sessionId)}/close`, {}, true).catch(() => {
      // Already gone. The node's idle timeout is the backstop, and it is safe to call
      // close for a session that has ended - the controller says so.
    })
  }, [base, post])

  // A tab that goes away has to end the PTY. Without this a forgotten shell runs as the
  // customer's own application until the node's idle timeout, which is fifteen minutes of
  // something they thought they had closed.
  useEffect(() => {
    const leave = () => {
      const current = sessionRef.current
      if (!current) {
        return
      }
      const body = new FormData()
      void fetch(`${base}/${encodeURIComponent(current.sessionId)}/close`, {
        method: 'POST',
        headers: csrfHeaders({Accept: 'application/json'}),
        credentials: 'same-origin',
        body,
        keepalive: true,
      }).catch(() => undefined)
    }
    window.addEventListener('pagehide', leave)
    return () => {
      window.removeEventListener('pagehide', leave)
      leave()
    }
  }, [base])

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
    retryStream: stream.reopen,
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
