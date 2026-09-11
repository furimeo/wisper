import {Head, usePage} from '@inertiajs/react'
import {useState} from 'react'

import {Button, ButtonLink, Card, EmptyState, Icon, PageHeader, mayWrite} from '@/shell'
import type {MemberRole} from '@/shell'

import {QuotaMeter} from '@/features/org/QuotaMeter'
import type {QuotaAllowance} from '@/features/org/orgTypes'

import {ScheduledTaskDialog} from './ScheduledTaskDialog'
import {ScheduledTaskRow} from './ScheduledTaskRow'
import {ServiceTabs} from './ServiceTabs'
import {lastRunFailed} from './cronVocabulary'
import type {ConcurrencyPolicy, CronTaskView, ServiceView} from './serviceTypes'

/**
 * `GET /services/{serviceId}/tasks` - the commands this service runs on a schedule.
 *
 * The failures are counted at the top. A service with nine healthy crons and one that has
 * exited 1 every night for a fortnight is the case this screen exists for, and a list
 * sorted by name buries it in the middle - so the count is stated before the list, and the
 * row that caused it is coloured.
 *
 * A static site gets the same explanation it gets on the volumes screen: there is no
 * container for a command to run in, `CreateScheduledTask` refuses one, and offering the
 * button anyway would teach the customer that the panel's controls are decorative.
 */
type ServiceTasksProps = {
  service: ServiceView
  tasks: CronTaskView[]
  policies: ConcurrencyPolicy[]
  /** The CRON_TASK quota for the organization this service is in. */
  allowance: QuotaAllowance
  viewerRole: MemberRole
}

export default function ServiceTasksPage() {
  const {service, tasks, policies, allowance, viewerRole} = usePage<ServiceTasksProps>().props
  const [editor, setEditor] = useState<{task: CronTaskView | null} | null>(null)

  const writable = mayWrite(viewerRole) && !service.archived && service.app
  const failing = tasks.filter(lastRunFailed)
  const off = tasks.filter((task) => !task.enabled).length

  return (
    <div className="flex flex-col gap-4">
      <Head title={`${service.name} scheduled tasks`} />
      <ServiceTabs serviceId={service.id} />

      <PageHeader
        title="Scheduled tasks"
        description={
          service.site
            ? 'A static site has no container, so there is nothing for a scheduled command to run inside.'
            : 'Run by the node inside this container. They keep firing while the panel is unreachable.'
        }
        actions={
          writable ? (
            <Button icon={<Icon name="jobs" />} onClick={() => setEditor({task: null})}>
              Schedule a command
            </Button>
          ) : null
        }
      />

      {service.site ? (
        <Card title="Nothing to run">
          <p className="text-sm leading-relaxed text-ink-700 dark:text-ink-300">
            A static site is files on disk; there is no process to run a command in. If the site
            needs something rebuilt on a schedule, the thing to schedule is a deployment - or put
            the command on an app service in the same project, which does have a container.
          </p>
          <div className="mt-3">
            <ButtonLink
              variant="secondary"
              block
              className="sm:w-auto"
              href={`/services/${service.id}/deployments`}
            >
              Deployments
            </ButtonLink>
          </div>
        </Card>
      ) : (
        <>
          {failing.length > 0 ? (
            <Card>
              <p className="text-sm leading-relaxed text-failed">
                {failing.length === 1
                  ? `${failing[0]?.name} ended badly on its last run.`
                  : `${failing.length} of these ended badly on their last run.`}{' '}
                <span className="text-ink-700 dark:text-ink-300">
                  A schedule that fires correctly and a command that exits non-zero look the same
                  from the outside - open the row for the exit code and whatever the node
                  reported.
                </span>
              </p>
            </Card>
          ) : null}

          {allowance.used >= allowance.limit || allowance.used / Math.max(1, allowance.limit) >= 0.9 ? (
            <Card>
              <QuotaMeter allowance={allowance} className="py-0" />
            </Card>
          ) : null}

          {mayWrite(viewerRole) || service.archived ? null : (
            <Card>
              <p className="text-sm text-ink-700 dark:text-ink-300">
                You have read access to this organization, so the controls on this page are off.
              </p>
            </Card>
          )}

          {service.archived ? (
            <Card>
              <p className="text-sm text-ink-700 dark:text-ink-300">
                This service is archived, so nothing here is running. Restore the project from its
                settings screen to schedule commands again.
              </p>
            </Card>
          ) : null}

          <Card
            title="Commands"
            description={
              tasks.length === 0
                ? undefined
                : `${tasks.length} scheduled${off > 0 ? `, ${off} switched off` : ''}. ${allowance.used} of ${allowance.limit} allowed on your plan.`
            }
            padded={false}
          >
            {tasks.length === 0 ? (
              <EmptyState
                icon={<Icon name="jobs" />}
                title="Nothing scheduled"
                description="A queue worker's nightly prune, a database vacuum, a report at 6am. The node
                  keeps the schedule itself, so these keep running even when the panel is down."
                action={
                  writable ? (
                    <Button onClick={() => setEditor({task: null})}>Schedule the first one</Button>
                  ) : null
                }
              />
            ) : (
              <ul className="divide-y divide-ink-200 dark:divide-ink-800">
                {tasks.map((task) => (
                  <ScheduledTaskRow
                    key={task.id}
                    task={task}
                    onOpen={writable ? () => setEditor({task}) : undefined}
                  />
                ))}
              </ul>
            )}
          </Card>

          <p className="px-1 text-sm text-ink-500 dark:text-ink-400">
            Commands run without a shell: the line is split into arguments and executed directly,
            so a pipe or a redirect has to go inside a script the container already has. Output
            goes to the container's logs.
          </p>
        </>
      )}

      {editor ? (
        <ScheduledTaskDialog
          key={editor.task?.name ?? '@new'}
          service={service}
          task={editor.task}
          policies={policies}
          onClose={() => setEditor(null)}
        />
      ) : null}
    </div>
  )
}
