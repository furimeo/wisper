import {Head, usePage} from '@inertiajs/react'
import type {ReactNode} from 'react'
import {Suspense, lazy, useCallback, useRef, useState} from 'react'

import {Badge, Button, ButtonLink, Card, PageHeader, Spinner, useTheme} from '@/shell'

import {ServiceTabs} from '@/features/service/ServiceTabs'
import type {ServiceLocation} from '@/features/service/serviceTypes'

import {MobileKeyBar} from './MobileKeyBar'
import {TerminalPasteDialog} from './TerminalPasteDialog'
import type {TerminalHandle} from './TerminalScreen'
import {controlByteFor, terminalKeys, textForKey} from './terminalKeys'
import {binaryToBytes, textToBytes} from './terminalCodec'
import {useTerminalSession} from './useTerminalSession'

/*
 * xterm and its two addons are a couple of hundred kilobytes of renderer. Nobody who
 * opened the service overview needs them, and the customer who opens this tab is about to
 * wait for a PTY anyway.
 */
const TerminalScreen = lazy(() =>
  import('./TerminalScreen').then((module) => ({default: module.TerminalScreen})),
)

/**
 * `GET /services/{serviceId}/terminal` - a shell inside the customer's own container.
 *
 * No shell is opened by loading this page. Landing on the terminal tab while reading
 * something else must not start a process inside a container, and a PTY opened by a page
 * load is one that stays open through every visit of the back button - which is why the
 * controller renders the page and the button mints the session.
 *
 * The screen below the terminal is the part that makes it usable from a phone: an extra
 * key bar with Escape, Tab, a sticky Control, the arrows and the punctuation a software
 * keyboard buries, and a paste button that works even when the browser refuses to hand
 * over the clipboard. Without those this is a terminal you can read and cannot use, which
 * is what the predecessor shipped.
 *
 * Over three hundred lines, deliberately (AGENTS.md §3.2). Two thirds of it is the three
 * states this screen legitimately has - not placed, no permission, and a live shell - each
 * of which explains itself in a sentence rather than rendering an empty frame. The
 * renderer, the transport, the key table and the paste fallback are all separate files
 * already.
 */
type TerminalProps = {
  service: ServiceLocation
  canOpen: boolean
  placed: boolean
  idleTimeoutSeconds: number
  maxDurationSeconds: number
}

export default function TerminalPage() {
  const {service, canOpen, placed, idleTimeoutSeconds, maxDurationSeconds} =
    usePage<TerminalProps>().props
  const theme = useTheme()

  const screen = useRef<TerminalHandle | null>(null)
  const [controlArmed, setControlArmed] = useState(false)
  const [pasting, setPasting] = useState(false)

  const write = useCallback((bytes: Uint8Array) => {
    screen.current?.write(bytes)
  }, [])

  const shell = useTerminalSession(service.serviceId, write)

  const send = shell.send
  const sendText = useCallback(
    (text: string) => {
      if (text !== '') {
        send(textToBytes(text))
      }
    },
    [send],
  )

  /** What xterm produced, with the sticky Control applied to the next character. */
  const onInput = (payload: {text: string} | {binary: string}) => {
    if ('binary' in payload) {
      send(binaryToBytes(payload.binary))
      return
    }
    if (controlArmed) {
      setControlArmed(false)
      const byte = controlByteFor(payload.text)
      if (byte !== null) {
        send(Uint8Array.of(byte))
        return
      }
    }
    send(textToBytes(payload.text))
  }

  const onKeyBar = (id: string) => {
    if (id === 'ctrl') {
      setControlArmed((armed) => !armed)
      return
    }
    const text = textForKey(id)
    if (text === null) {
      return
    }
    if (controlArmed && text.length === 1) {
      setControlArmed(false)
      const byte = controlByteFor(text)
      if (byte !== null) {
        send(Uint8Array.of(byte))
        screen.current?.focus()
        return
      }
    }
    setControlArmed(false)
    sendText(text)
    screen.current?.focus()
  }

  const paste = async () => {
    try {
      const text = await navigator.clipboard?.readText()
      if (text) {
        sendText(text)
        screen.current?.focus()
        return
      }
    } catch {
      // Safari without permission, or a page served over plain HTTP. The dialog below is
      // the way that always works.
    }
    setPasting(true)
  }

  const start = () => {
    const size = screen.current?.size()
    shell.open(size?.columns ?? 80, size?.rows ?? 24)
  }

  if (!placed) {
    return (
      <Frame service={service}>
        <Card title="Nothing is running this service yet">
          <p className="text-sm text-ink-700 dark:text-ink-300">
            A shell runs inside the container, so there has to be one. Start {service.name} and
            this page will open a shell in it - the platform places it on a node, pulls the
            image and reports back, which usually takes a few seconds.
          </p>
          <div className="mt-3">
            <ButtonLink href={`/services/${service.serviceId}`} variant="secondary">
              Go to the service
            </ButtonLink>
          </div>
        </Card>
      </Frame>
    )
  }

  if (!canOpen) {
    return (
      <Frame service={service}>
        <Card title="You have read access to this organization">
          <p className="text-sm text-ink-700 dark:text-ink-300">
            A shell inside a container can do anything the application can, so opening one
            counts as changing things. Somebody with a developer role or above can open it,
            and logs and metrics are still available to you.
          </p>
          <div className="mt-3 flex flex-wrap gap-2">
            <ButtonLink href={`/services/${service.serviceId}/logs`} variant="secondary">
              Logs
            </ButtonLink>
            <ButtonLink href={`/services/${service.serviceId}/metrics`} variant="secondary">
              Metrics
            </ButtonLink>
          </div>
        </Card>
      </Frame>
    )
  }

  return (
    <Frame service={service}>
      <PageHeader
        title="Terminal"
        description={`/bin/sh inside the container. It ends by itself after ${minutes(idleTimeoutSeconds)} idle, and after ${hours(maxDurationSeconds)} whatever happens.`}
        actions={
          shell.phase === 'live' ? (
            <Button variant="danger" onClick={shell.close}>
              End session
            </Button>
          ) : (
            <Button onClick={start} loading={shell.phase === 'opening'}>
              {shell.phase === 'ended' || shell.phase === 'failed'
                ? 'Start another shell'
                : 'Start a shell'}
            </Button>
          )
        }
      />

      <div className="flex flex-wrap items-center gap-2">
        {shell.phase === 'live' ? (
          <Badge tone={shell.reconnecting ? 'degraded' : 'running'} dot pulse={shell.reconnecting}>
            {shell.reconnecting ? 'Reconnecting' : 'Connected'}
          </Badge>
        ) : null}
        {shell.ready ? (
          <Badge tone="neutral">
            {shell.ready.columns}x{shell.ready.rows}
          </Badge>
        ) : null}
        {shell.session ? (
          <span className="font-mono text-xs text-ink-500 dark:text-ink-400">
            container {shell.session.containerId.slice(0, 12)}
          </span>
        ) : null}
      </div>

      {shell.reconnecting ? (
        <p className="rounded-lg bg-degraded/15 px-3 py-2 text-sm text-ink-800 dark:text-ink-100">
          The connection to the output dropped. The shell is still open on the node and the
          panel is holding what it printed - this reconnects by itself.{' '}
          <button
            type="button"
            onClick={shell.retryStream}
            className="underline underline-offset-2"
          >
            Reconnect now
          </button>
        </p>
      ) : null}

      {shell.error ? (
        <p className="rounded-lg bg-failed/10 px-3 py-2 text-sm text-ink-800 dark:text-ink-100">
          {shell.error}
        </p>
      ) : null}

      {shell.exit ? (
        <p className="rounded-lg bg-ink-100 px-3 py-2 text-sm text-ink-800 dark:bg-ink-800 dark:text-ink-100">
          The shell ended with exit code {shell.exit.code}
          {shell.exit.reason ? `: ${shell.exit.reason}` : '.'} Nothing in the container was
          stopped - only the shell.
        </p>
      ) : null}

      {shell.session ? (
        <div className="overflow-hidden rounded-xl border border-ink-200 dark:border-ink-800">
          <div className="h-[58dvh] min-h-64 md:h-[62dvh]">
            <Suspense
              fallback={
                <div className="flex h-full items-center justify-center gap-2 text-sm text-ink-500">
                  <Spinner />
                  Loading the terminal
                </div>
              }
            >
              <TerminalScreen
                theme={theme.resolved}
                onReady={(handle) => {
                  screen.current = handle
                  handle.focus()
                }}
                onInput={onInput}
                onResize={shell.resize}
              />
            </Suspense>
          </div>
          <MobileKeyBar
            keys={terminalKeys(controlArmed)}
            onPress={onKeyBar}
            trailing={
              <button
                type="button"
                onPointerDown={(event) => event.preventDefault()}
                onClick={() => void paste()}
                className="flex h-11 items-center rounded-lg bg-accent-600 px-3 text-sm font-medium text-white"
              >
                Paste
              </button>
            }
          />
        </div>
      ) : (
        <Card>
          <p className="text-sm text-ink-700 dark:text-ink-300">
            No shell is running. Opening this page does not start one - a terminal left open
            in a background tab is a process inside your container, so it is always something
            you ask for.
          </p>
        </Card>
      )}

      <TerminalPasteDialog
        open={pasting}
        onClose={() => setPasting(false)}
        onSend={(text) => {
          sendText(text)
          screen.current?.focus()
        }}
      />
    </Frame>
  )
}

function Frame({
  service,
  children,
}: {
  service: ServiceLocation
  children: ReactNode
}) {
  return (
    <div className="flex flex-col gap-4">
      <Head title={`Terminal · ${service.name}`} />
      <ServiceTabs serviceId={service.serviceId} />
      {children}
    </div>
  )
}

function minutes(seconds: number): string {
  const value = Math.max(1, Math.round(seconds / 60))
  return `${value} minute${value === 1 ? '' : 's'}`
}

function hours(seconds: number): string {
  const value = Math.max(1, Math.round(seconds / 3600))
  return `${value} hour${value === 1 ? '' : 's'}`
}
