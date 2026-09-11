import {Badge, Icon, RelativeTime, cx} from '@/shell'

import type {CronTaskView} from './serviceTypes'
import {describeSchedule, lastRunFailed, lastRunSentence} from './cronVocabulary'

/**
 * One scheduled command.
 *
 * Both halves are on the row: when it is next due, and how the last run went. A cron whose
 * schedule is correct and whose command exits 1 every night looks perfectly healthy on a
 * screen that draws only the schedule, and the customer finds out weeks later from
 * whatever the job was supposed to be doing.
 *
 * The schedule is shown in words with the expression underneath rather than instead of it.
 * `0 3 * * 1` is what gets edited and what a customer pastes from somewhere else, so it
 * has to stay visible; "At 03:00, on Mondays" is what makes a wrong field obvious.
 */
export function ScheduledTaskRow({
  task,
  onOpen,
}: {
  task: CronTaskView
  /** Absent for a viewer. */
  onOpen?: () => void
}) {
  const failed = lastRunFailed(task)
  const spoken = describeSchedule(task.schedule)

  const body = (
    <>
      <span className="min-w-0 flex-1">
        <span className="flex flex-wrap items-center gap-2">
          <span className="truncate text-sm font-medium text-ink-900 dark:text-ink-100">
            {task.name}
          </span>
          {task.enabled ? null : <Badge tone="neutral">Off</Badge>}
          {task.running ? (
            <Badge tone="accent" dot pulse>
              Running
            </Badge>
          ) : null}
          {failed ? <Badge tone="failed">Failed</Badge> : null}
        </span>

        <span className="mt-0.5 block truncate font-mono text-xs text-ink-500 dark:text-ink-400">
          {task.command}
        </span>

        <span className="mt-1 block text-xs text-ink-600 dark:text-ink-400">
          {spoken}
          {spoken === task.schedule ? null : (
            <code className="ml-1.5 font-mono text-ink-500 dark:text-ink-500">{task.schedule}</code>
          )}
          {task.timezone ? ` · ${task.timezone}` : null}
        </span>

        <span
          className={cx(
            'mt-1 block text-xs',
            failed ? 'text-failed' : 'text-ink-500 dark:text-ink-400',
          )}
        >
          {lastRunSentence(task)}
          {task.lastFinishedAt ? (
            <>
              {' '}
              <RelativeTime at={task.lastFinishedAt} />
            </>
          ) : null}
        </span>

        {task.lastError ? (
          <span className="mt-1 block text-xs leading-relaxed text-failed">{task.lastError}</span>
        ) : null}

        <span className="mt-1 block text-xs text-ink-500 dark:text-ink-400">
          {task.enabled ? (
            task.nextRunAt ? (
              <>
                Next <RelativeTime at={task.nextRunAt} />
              </>
            ) : (
              'No further run could be worked out from this schedule.'
            )
          ) : (
            'Switched off. Its schedule and history are kept.'
          )}
        </span>
      </span>

      {onOpen ? <Icon name="chevronRight" className="size-4 shrink-0 text-ink-400" /> : null}
    </>
  )

  return (
    <li>
      {onOpen ? (
        <button
          type="button"
          onClick={onOpen}
          className="flex w-full touch-target items-start gap-3 px-4 py-3 text-left transition-colors hover:bg-ink-100 md:px-5 dark:hover:bg-ink-800"
        >
          {body}
        </button>
      ) : (
        <div className="flex w-full touch-target items-start gap-3 px-4 py-3 md:px-5">{body}</div>
      )}
    </li>
  )
}
