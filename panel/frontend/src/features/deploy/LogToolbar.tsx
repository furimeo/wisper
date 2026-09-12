import {t} from '@/i18n'
import {Button, Icon, Spinner, useClipboard} from '@/shell'
import type {SseStatus} from '@/shell'

import type {DeploymentLog} from './deployTypes'

/**
 * The strip above the build log: where the stream stands, and the two things to do with it.
 *
 * The state sentence is not decoration. A log that has stopped printing means one of three
 * completely different things - the build finished, the tunnel dropped the connection and
 * the browser is retrying, or the reader has scrolled up and lines are still arriving out
 * of sight - and a customer who cannot tell them apart reloads the page and loses their
 * place. So it says which.
 *
 * Copy builds its string on the click rather than holding one: a fifty-thousand-line log
 * is several megabytes, and keeping that joined on every render for a button nobody has
 * pressed would cost more than the viewer it sits above.
 */
export function LogToolbar({
  lines,
  status,
  ended,
  following,
  onReopen,
}: {
  lines: DeploymentLog[]
  status: SseStatus
  ended: boolean
  following: boolean
  onReopen: () => void
  }) {
  return (
    <div className="flex flex-wrap items-center gap-x-3 gap-y-2 border-b border-ink-200 bg-ink-100 px-3 py-2 dark:border-ink-800 dark:bg-ink-900">
      <span
        role="status"
        className="flex min-w-0 flex-1 items-center gap-2 text-sm text-ink-700 dark:text-ink-300"
      >
        {!ended && status === 'open' ? <Spinner /> : null}
        <span className="truncate">
          {ended
            ? t('deploy.toolbar.log_complete')
            : status === 'open'
              ? following
                ? t('deploy.toolbar.streaming_tail')
                : t('deploy.toolbar.streaming_scrolled')
              : status === 'connecting'
                ? t('deploy.toolbar.reconnecting')
                : t('deploy.toolbar.stream_closed')}
        </span>
        <span className="shrink-0 tabular-nums text-ink-500 dark:text-ink-400">
          {t('deploy.toolbar.lines_count', {count: lines.length.toLocaleString()})}
        </span>
      </span>

      {!ended && status === 'closed' ? (
        <Button variant="secondary" onClick={onReopen}>
          {t('deploy.toolbar.reconnect')}
        </Button>
      ) : null}

      <CopyLogButton lines={lines} />
    </div>
  )
}

/**
 * Copies every line held, and says so - or says it could not, which happens outside a
 * secure context and on a document that has lost focus.
 */
function CopyLogButton({lines}: {lines: DeploymentLog[]}) {
  const {copy, state} = useClipboard()

  return (
    <Button
      variant="ghost"
      disabled={lines.length === 0}
      onClick={() => void copy(lines.map((line) => line.message).join('\n'))}
      icon={<Icon name={state === 'copied' ? 'check' : 'copy'} className="size-4" />}
    >
      {state === 'copied'
        ? t('deploy.toolbar.copied')
        : state === 'failed'
          ? t('deploy.toolbar.copy_failed')
          : t('deploy.toolbar.copy')}
    </Button>
  )
}
