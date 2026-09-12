import {useEffect, useMemo, useRef, useState} from 'react'

import {t} from '@/i18n'
import {Badge, Button, CopyButton, cx} from '@/shell'

import {tailText, visibleLines} from './logLines'
import type {LogLine} from './logLines'
import type {LogStream} from './useLogStream'

/**
 * The output itself: a black rectangle that keeps up.
 *
 * Two decisions here are about a phone rather than about logs.
 *
 * Consecutive lines with the same origin are rendered as one node, not one node each.
 * Four thousand `<div>`s is a scroll that stutters on any phone that is not new, and a
 * container in a boot loop fills that in a minute; joining runs of stdout into a single
 * `<span>` inside a `<pre>` keeps the colouring and collapses the tree to a handful of
 * elements.
 *
 * Following the tail stops the moment the reader scrolls up, and says so with a button
 * that goes back. A viewer that yanks you to the bottom while you are reading is a viewer
 * you cannot read a stack trace in - and a stack trace is why anybody opens this.
 */
export function LogConsole({stream, placed}: {stream: LogStream; placed: boolean}) {
  const scroller = useRef<HTMLDivElement | null>(null)
  const [following, setFollowing] = useState(true)
  const [wrap, setWrap] = useState(true)

  const lines = visibleLines(stream.tail)
  const chunks = useMemo(() => group(lines), [lines])

  useEffect(() => {
    const element = scroller.current
    if (!element || !following) {
      return
    }
    element.scrollTop = element.scrollHeight
  }, [chunks, following])

  return (
    <div className="flex flex-col gap-2">
      <div className="flex flex-wrap items-center gap-2">
        <Badge
          tone={
            stream.tail.ended
              ? 'neutral'
              : stream.paused
                ? 'degraded'
                : stream.connected
                  ? 'running'
                  : 'degraded'
          }
          dot
          pulse={!stream.tail.ended && !stream.paused && !stream.connected}
        >
          {stream.tail.ended
            ? t('stats.console.statusEnded')
            : stream.paused
              ? t('stats.console.statusPaused')
              : stream.connected
                ? t('stats.console.statusFollowing')
                : t('stats.console.statusConnecting')}
        </Badge>

        <span className="text-xs tabular-nums text-ink-500 dark:text-ink-400">
          {t('stats.console.linesCount', {count: lines.length})}
        </span>

        {stream.tail.droppedBytes > 0 ? (
          <Badge tone="degraded">
            {t('stats.console.bytesDropped', {count: stream.tail.droppedBytes.toLocaleString()})}
          </Badge>
        ) : null}

        <div className="ml-auto flex flex-wrap items-center gap-2">
          <Button variant="ghost" size="sm" onClick={() => setWrap((current) => !current)}>
            {wrap ? t('stats.console.noWrap') : t('stats.console.wrap')}
          </Button>
          <Button variant="ghost" size="sm" onClick={stream.clear}>
            {t('stats.console.clear')}
          </Button>
          <CopyButton value={tailText(stream.tail)} label={t('stats.console.copy')} size="sm" />
          {stream.tail.ended ? (
            <Button variant="secondary" size="sm" onClick={stream.reconnect}>
              {t('stats.console.followAgain')}
            </Button>
          ) : (
            <Button
              variant="secondary"
              size="sm"
              onClick={() => stream.setPaused(!stream.paused)}
              disabled={!placed}
            >
              {stream.paused ? t('stats.console.resume') : t('stats.console.pause')}
            </Button>
          )}
        </div>
      </div>

      {stream.reconnecting ? (
        <p className="rounded-lg bg-degraded/15 px-3 py-2 text-sm text-ink-800 dark:text-ink-100">
          {t('stats.console.streamDropped')}{' '}
          <button
            type="button"
            onClick={stream.reconnect}
            className="underline underline-offset-2"
          >
            {t('stats.console.reconnectNow')}
          </button>
        </p>
      ) : null}

      <div className="relative">
        <div
          ref={scroller}
          onScroll={(event) => {
            const element = event.currentTarget
            setFollowing(
              element.scrollTop + element.clientHeight >= element.scrollHeight - 24,
            )
          }}
          className={cx(
            'h-[52dvh] min-h-56 overflow-auto rounded-xl border p-3 md:h-[58dvh]',
            'border-ink-200 bg-white font-mono text-xs leading-5',
            'dark:border-ink-800 dark:bg-[#12161c]',
          )}
        >
          {chunks.length === 0 ? (
            <p className="text-ink-500 dark:text-ink-400">
              {placed
                ? t('stats.console.emptyPlaced')
                : t('stats.console.emptyUnplaced')}
            </p>
          ) : (
            <pre className={cx('m-0', wrap ? 'whitespace-pre-wrap break-words' : 'whitespace-pre')}>
              {chunks.map((chunk) => (
                <span
                  key={chunk.id}
                  className={
                    chunk.notice
                      ? 'text-degraded'
                      : chunk.stderr
                        ? 'text-failed'
                        : 'text-ink-800 dark:text-ink-200'
                  }
                >
                  {chunk.text}
                  {'\n'}
                </span>
              ))}
            </pre>
          )}
        </div>

        {following ? null : (
          <Button
            size="sm"
            onClick={() => {
              setFollowing(true)
              const element = scroller.current
              if (element) {
                element.scrollTop = element.scrollHeight
              }
            }}
            className="absolute bottom-3 right-3 shadow-lg"
          >
            {t('stats.console.jumpToLatest')}
          </Button>
        )}
      </div>
    </div>
  )
}

interface Chunk {
  id: number
  text: string
  stderr: boolean
  notice: boolean
}

/** Consecutive lines from the same stream, joined into one node. */
function group(lines: LogLine[]): Chunk[] {
  const chunks: Chunk[] = []
  for (const line of lines) {
    const last = chunks[chunks.length - 1]
    if (last && last.stderr === line.stderr && last.notice === line.notice) {
      last.text = `${last.text}\n${line.text}`
      continue
    }
    chunks.push({id: line.id, text: line.text, stderr: line.stderr, notice: line.notice})
  }
  return chunks
}
