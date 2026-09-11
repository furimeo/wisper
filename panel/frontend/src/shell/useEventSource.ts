import {useCallback, useEffect, useRef, useState} from 'react'

/**
 * A typed subscription to one of the panel's Server-Sent Events streams.
 *
 * Realtime in wisper is SSE and nothing else - deployment logs, container logs, metric
 * points, the terminal's output. Every one of those streams is named events carrying a
 * string, and every consumer of them was otherwise going to write the same twenty lines:
 * open, add four listeners, decode, remember to close on unmount, and get the last part
 * wrong.
 *
 * Two details that are not optional here. The panel sits behind a tunnel that closes an
 * idle connection, which is why the server sends `: alive` comments and why a caller must
 * not treat a reconnect as an error. And a stream that ends on purpose - the build
 * finished - has to be closed by the caller from inside its own handler, otherwise the
 * browser reopens it every few seconds forever.
 */
export type SseStatus = 'connecting' | 'open' | 'closed'

/** Handlers keyed by SSE event name. Every one receives already-decoded data. */
export type SseListeners<E> = {
  [K in keyof E & string]?: (data: E[K]) => void
}

/** Per-event decoders. The default is `JSON.parse`; a text event passes the raw string. */
export type SseDecoders<E> = {
  [K in keyof E & string]?: (raw: string) => E[K]
}

export interface SseOptions<E> {
  decode?: SseDecoders<E>
  /** Called once per successful connection, including after the browser reconnects. */
  onOpen?: () => void
  /**
   * The transport dropped, or a decoder threw. A drop is normal behind a tunnel: the
   * browser is already retrying, and `status` says `connecting` while it does.
   */
  onError?: (cause: unknown) => void
  /** False keeps the stream shut without unmounting the component that owns it. */
  enabled?: boolean
}

export interface SseConnection {
  status: SseStatus
  /** Stops the stream and stops the browser reopening it. */
  close: () => void
  /** Opens it again after `close`, or forces a fresh connection. */
  reopen: () => void
}

type RawListeners = Record<string, ((data: unknown) => void) | undefined>
type RawDecoders = Record<string, ((raw: string) => unknown) | undefined>

/**
 * Subscribes to `url` for as long as the component is mounted.
 *
 * ```ts
 * const {status, close} = useEventSource<{line: DeploymentLog; end: number}>(
 *   `/services/${id}/deployments/${deploymentId}/log?after=${cursor}`,
 *   {line: (entry) => append(entry), end: () => close()},
 *   {decode: {end: (raw) => Number(raw)}},
 * )
 * ```
 */
export function useEventSource<E extends Record<string, unknown>>(
  url: string | null,
  listeners: SseListeners<E>,
  options: SseOptions<E> = {},
): SseConnection {
  const [status, setStatus] = useState<SseStatus>('closed')
  const [generation, setGeneration] = useState(0)
  const source = useRef<EventSource | null>(null)

  /*
   * The handler and decoder objects are rebuilt on every render of the calling
   * component. Holding them in refs means a re-render does not tear the connection down
   * and open a new one, which on a log stream would lose every line sent in between.
   * The generic types are checked at the call site; inside, the maps are keyed by a
   * string that only exists at runtime, so they are read untyped.
   */
  const listenersRef = useRef<RawListeners>({})
  listenersRef.current = listeners as RawListeners
  const optionsRef = useRef<SseOptions<E>>(options)
  optionsRef.current = options

  // The *set* of event names decides which listeners get attached, and unlike the
  // handler functions it hardly ever changes. A string so it compares by value.
  const eventNames = Object.keys(listeners).sort().join(',')
  const enabled = options.enabled ?? true

  useEffect(() => {
    if (url === null || !enabled) {
      setStatus('closed')
      return
    }
    setStatus('connecting')

    const stream = new EventSource(url)
    source.current = stream

    stream.onopen = () => {
      setStatus('open')
      optionsRef.current.onOpen?.()
    }

    stream.onerror = (event) => {
      // CLOSED means the browser has given up; anything else means it is retrying, which
      // behind a tunnel is the normal course of a long-lived stream.
      setStatus(stream.readyState === EventSource.CLOSED ? 'closed' : 'connecting')
      optionsRef.current.onError?.(event)
    }

    const attached: Array<[string, EventListener]> = []
    for (const name of eventNames === '' ? [] : eventNames.split(',')) {
      const handler = ((event: MessageEvent<string>) => {
        const decoders = optionsRef.current.decode as RawDecoders | undefined
        const decode = decoders?.[name]
        let data: unknown
        try {
          data = decode ? decode(event.data) : JSON.parse(event.data)
        } catch (failure) {
          optionsRef.current.onError?.(failure)
          return
        }
        listenersRef.current[name]?.(data)
      }) as EventListener
      stream.addEventListener(name, handler)
      attached.push([name, handler])
    }

    return () => {
      for (const [name, handler] of attached) {
        stream.removeEventListener(name, handler)
      }
      stream.onopen = null
      stream.onerror = null
      stream.close()
      source.current = null
    }
  }, [url, enabled, eventNames, generation])

  const close = useCallback(() => {
    source.current?.close()
    source.current = null
    setStatus('closed')
  }, [])

  const reopen = useCallback(() => {
    source.current?.close()
    source.current = null
    setGeneration((current) => current + 1)
  }, [])

  return {status, close, reopen}
}
