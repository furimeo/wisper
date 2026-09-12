import {Head, usePage} from '@inertiajs/react'
import {useState} from 'react'

import {t} from '@/i18n'
import {Button, ButtonLink, Card, EmptyState, Icon, PageHeader, mayWrite} from '@/shell'
import type {MemberRole} from '@/shell'

import {QuotaMeter} from '@/features/org/QuotaMeter'
import type {QuotaAllowance} from '@/features/org/orgTypes'

import {ScheduledTaskDialog} from './ScheduledTaskDialog'
import {ScheduledTaskRow} from './ScheduledTaskRow'
import {ServiceTabs} from './ServiceTabs'
import {lastRunFailed} from './cronVocabulary'
import type {ConcurrencyPolicy, CronTaskView, ServiceView} from './serviceTypes'

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
      <Head title={t('service.tasks.head_title', {name: service.name})} />
      <ServiceTabs serviceId={service.id} />

      <PageHeader
        title={t('service.tasks.title')}
        description={
          service.site
            ? t('service.tasks.description_site')
            : t('service.tasks.description_app')
        }
        actions={
          writable ? (
            <Button icon={<Icon name="jobs" />} onClick={() => setEditor({task: null})}>
              {t('service.tasks.schedule_button')}
            </Button>
          ) : null
        }
      />

      {service.site ? (
        <Card title={t('service.tasks.nothing_to_run_title')}>
          <p className="text-sm leading-relaxed text-ink-700 dark:text-ink-300">
            {t('service.tasks.nothing_to_run_body')}
          </p>
          <div className="mt-3">
            <ButtonLink
              variant="secondary"
              block
              className="sm:w-auto"
              href={`/services/${service.id}/deployments`}
            >
              {t('service.tasks.deployments_button')}
            </ButtonLink>
          </div>
        </Card>
      ) : (
        <>
          {failing.length > 0 ? (
            <Card>
              <p className="text-sm leading-relaxed text-failed">
                {failing.length === 1
                  ? t('service.tasks.failing_single', {name: failing[0]?.name ?? ''})
                  : t('service.tasks.failing_multi', {count: failing.length})}{' '}
                <span className="text-ink-700 dark:text-ink-300">
                  {t('service.tasks.failing_explanation')}
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
                {t('service.tasks.read_only_notice')}
              </p>
            </Card>
          )}

          {service.archived ? (
            <Card>
              <p className="text-sm text-ink-700 dark:text-ink-300">
                {t('service.tasks.archived_notice')}
              </p>
            </Card>
          ) : null}

          <Card
            title={t('service.tasks.commands_card_title')}
            description={
              tasks.length === 0
                ? undefined
                : t('service.tasks.commands_card_desc', {
                    count: tasks.length,
                    off,
                    used: allowance.used,
                    limit: allowance.limit,
                  })
            }
            padded={false}
          >
            {tasks.length === 0 ? (
              <EmptyState
                icon={<Icon name="jobs" />}
                title={t('service.tasks.empty_title')}
                description={t('service.tasks.empty_desc')}
                action={
                  writable ? (
                    <Button onClick={() => setEditor({task: null})}>{t('service.tasks.schedule_first')}</Button>
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
            {t('service.tasks.footer_hint')}
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
