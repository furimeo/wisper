import type {ReactElement} from 'react'
import {useCallback, useEffect, useLayoutEffect, useRef, useState} from 'react'

import {t} from '@/i18n'
import {Button, Icon, cx, useIsWide} from '@/shell'

import type {DeploymentLog} from './deployTypes'
import {
  LOG_GUTTER_GAP,
  LOG_GUTTER_WIDTH,
  LOG_PADDING_X,
  LOG_TEXT_CLASSES,
  LogLine,
} from './LogLine'
import {LogToolbar} from './LogToolbar'
import {useDeploymentLog} from './useDeploymentLog'
import {useLogWindow} from './useLogWindow'

/** How many characters the width probe renders. Longer is a more accurate average. */
const PROBE = '0123456789'.repeat(10)

/** Within this many pixels of the bottom counts as "at the bottom" and keeps following. */
const FOLLOW_SLACK = 24

/** Never fewer columns than this, however narrow the viewport gets. */
const MIN_COLUMNS = 16

/**
 * The build log: streamed over SSE, virtualised, and following the tail until the reader
 * says otherwise.
 *
 * Auto-scroll is the behaviour this screen lives or dies by. Following the tail is what
 * somebody wants for the ninety seconds they are watching a build; the instant they drag
 * upward to read the error that just went past, following has to stop - not a second
 * later, and not after the next batch of lines has yanked them back down. So the scroll
 * handler decides, synchronously, from one fact: is the viewport at the bottom. Scrolling
 * up turns following off on the very first scroll event; scrolling back down turns it on
 * again, which means the way to resume is the way you would guess.
 *
 * Only the rows crossing the viewport are in the DOM - see `useLogWindow` for why a
 * wrapped row's height is computed rather than measured. Everything above and below is a
 * single spacer, so a fifty-thousand-line log costs the same as a fifty-line one.
 */
export function BuildLogViewer({
  serviceId,
  deploymentId,
  initialLines,
  cursor,
  onEnded,
}: {
  serviceId: string
  deploymentId: string
  initialLines: DeploymentLog[]
  cursor: number
  /**
   * Called once when the server says the build is over. The page uses it to re-read the
   * deployment, which is otherwise the status it had when the page was rendered - a
   * "Building" pill over a log that stopped ten minutes ago.
   */
  onEnded?: () => void
}) {
  const {lines, dropped, status, ended, reopen} = useDeploymentLog(
    serviceId,
    deploymentId,
    initialLines,
    cursor,
  )

  const notify = useRef(onEnded)
  notify.current = onEnded
  useEffect(() => {
    if (ended) {
      notify.current?.()
    }
  }, [ended])

  const wide = useIsWide()
  const scroller = useRef<HTMLDivElement | null>(null)
  const probe = useRef<HTMLSpanElement | null>(null)

  const [metrics, setMetrics] = useState({columns: 80, lineHeight: 20})
  const [viewport, setViewport] = useState(0)
  const [scrollTop, setScrollTop] = useState(0)
  const [following, setFollowing] = useState(true)
  // Read by the auto-scroll effect, which must never act on a value one render stale.
  const follow = useRef(true)
  const frame = useRef(0)

  const view = useLogWindow({
    lines,
    columns: metrics.columns,
    lineHeight: metrics.lineHeight,
    scrollTop,
    viewportHeight: viewport,
  })

  useLayoutEffect(() => {
    const element = scroller.current
    const sample = probe.current
    if (!element || !sample) {
      return
    }
    const remeasure = () => {
      const width = sample.getBoundingClientRect().width / PROBE.length
      const parsed = Number.parseFloat(window.getComputedStyle(sample).lineHeight)
      const lineHeight = Number.isFinite(parsed) && parsed > 0 ? parsed : 20
      const gutter = wide ? LOG_GUTTER_WIDTH + LOG_GUTTER_GAP : 0
      const usable = element.clientWidth - LOG_PADDING_X * 2 - gutter
      // A pixel of slack: measuring a character a hair narrow would fit one column too
      // many, and an underestimated row height overlaps the row below it.
      const columns = width > 0 ? Math.max(MIN_COLUMNS, Math.floor((usable - 1) / width)) : 80

      setMetrics((current) =>
        current.columns === columns && current.lineHeight === lineHeight
          ? current
          : {columns, lineHeight},
      )
      setViewport(element.clientHeight)
    }

    remeasure()
    const observer = new ResizeObserver(remeasure)
    observer.observe(element)
    return () => observer.disconnect()
  }, [wide])

  // Pinned to the tail while following. Runs on every change of total height, which is
  // exactly once per flushed batch of lines.
  useLayoutEffect(() => {
    const element = scroller.current
    if (!element || !follow.current) {
      return
    }
    element.scrollTop = element.scrollHeight
    setScrollTop(element.scrollTop)
  }, [view.totalHeight, viewport])

  const onScroll = useCallback(() => {
    const element = scroller.current
    if (!element) {
      return
    }
    const atBottom =
      element.scrollHeight - element.scrollTop - element.clientHeight <= FOLLOW_SLACK
    follow.current = atBottom
    setFollowing(atBottom)
    if (frame.current === 0) {
      frame.current = window.requestAnimationFrame(() => {
        frame.current = 0
        setScrollTop(element.scrollTop)
      })
    }
  }, [])

  const jumpToLatest = useCallback(() => {
    const element = scroller.current
    if (!element) {
      return
    }
    follow.current = true
    setFollowing(true)
    element.scrollTop = element.scrollHeight
    setScrollTop(element.scrollTop)
  }, [])

  const rows: ReactElement[] = []
  for (let index = view.first; index < view.last; index += 1) {
    const line = lines[index]
    if (line === undefined) {
      continue
    }
    rows.push(
      <LogLine
        key={line.id}
        line={line}
        top={view.topOf(index)}
        height={view.heightOf(index)}
        showNumber={wide}
      />,
    )
  }

  return (
    <section className="overflow-hidden rounded-xl border border-ink-200 dark:border-ink-800">
      <LogToolbar
        lines={lines}
        status={status}
        ended={ended}
        following={following}
        onReopen={reopen}
      />

      {dropped > 0 ? (
        <p className="border-b border-ink-800 bg-degraded/15 px-4 py-2 text-sm text-ink-800 dark:text-ink-100">
          {t('deploy.viewer.dropped_notice', {dropped: dropped.toLocaleString()})}
        </p>
      ) : null}

      <div className="relative">
        <div
          ref={scroller}
          onScroll={onScroll}
          tabIndex={0}
          aria-label={t('deploy.viewer.aria_label')}
          className={cx(
            'relative h-[60dvh] min-h-64 overflow-y-auto overscroll-contain bg-ink-950 py-2',
            'md:h-[68dvh]',
          )}
        >
          <span
            ref={probe}
            aria-hidden="true"
            className={cx('pointer-events-none absolute top-0 left-0 whitespace-pre', LOG_TEXT_CLASSES)}
            style={{visibility: 'hidden'}}
          >
            {PROBE}
          </span>

          {lines.length === 0 ? (
            <p className={cx('px-3 text-ink-400', LOG_TEXT_CLASSES)}>
              {ended
                ? t('deploy.viewer.empty_ended')
                : t('deploy.viewer.empty_running')}
            </p>
          ) : (
            <div className="relative" style={{height: view.totalHeight}}>
              {rows}
            </div>
          )}
        </div>

        {following ? null : (
          <Button
            onClick={jumpToLatest}
            icon={<Icon name="chevronDown" className="size-4" />}
            className="absolute bottom-3 left-1/2 -translate-x-1/2 shadow-lg"
          >
            {t('deploy.viewer.jump_latest')}
          </Button>
        )}
      </div>
    </section>
  )
}
