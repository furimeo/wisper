import {router} from '@inertiajs/react'
import {useState} from 'react'

import {t} from '@/i18n'
import {Badge, Button, Card, RelativeTime, askConfirmation} from '@/shell'

import {ServiceStatusBadge} from './ServiceStatusBadge'
import type {DesiredState, ServiceSummary, ServiceView} from './serviceTypes'
import {healthLabel, statusSentence} from './serviceVocabulary'
import {useLiveServiceStatus} from './useLiveServiceStatus'

type Action = 'start' | 'stop' | 'restart'

export function ServiceStatePanel({
  service,
  status,
  writable,
}: {
  service: ServiceView
  /** Null until a node has reported this service at all. That is a real state. */
  status: ServiceSummary | null
  writable: boolean
}) {
  const [pending, setPending] = useState<Action | null>(null)

  // What the customer has asked for, including the request that is still in the air.
  const intent: DesiredState =
    pending === 'stop' ? 'STOPPED' : pending === null ? service.desiredState : 'RUNNING'

  const settling = pending !== null || isSettling(status, intent)
  useLiveServiceStatus(settling, !service.archived)

  function act(action: Action) {
    setPending(action)
    router.post(
      `/services/${service.id}/${action}`,
      {},
      {
        preserveScroll: true,
        onFinish: () => setPending(null),
      },
    )
  }

  async function confirmThenAct(action: 'stop' | 'restart') {
    const confirmed = await askConfirmation(
      action === 'stop'
        ? {
            title: t('service.state.stop_confirm_title', {name: service.name}),
            body: service.site
              ? t('service.state.stop_confirm_body_site')
              : t('service.state.stop_confirm_body_app'),
            confirmLabel: t('service.state.stop_confirm_button'),
            tone: 'danger',
          }
        : {
            title: t('service.state.restart_confirm_title', {name: service.name}),
            body: t('service.state.restart_confirm_body'),
            confirmLabel: t('service.state.restart_confirm_button'),
          },
    )
    if (confirmed) {
      act(action)
    }
  }

  const canRestart = service.app && intent === 'RUNNING' && !service.archived
  const busy = pending !== null

  return (
    <Card
      title={t('service.state.title')}
      action={status ? <ServiceStatusBadge status={status} /> : <Badge tone="neutral">{t('service.state.not_placed')}</Badge>}
      footer={
        <div className="flex flex-col gap-2 sm:flex-row">
          {intent === 'RUNNING' ? (
            <Button
              variant="secondary"
              block
              className="sm:w-auto"
              disabled={!writable || service.archived}
              loading={pending === 'stop'}
              onClick={() => void confirmThenAct('stop')}
            >
              {t('service.state.stop')}
            </Button>
          ) : (
            <Button
              block
              className="sm:w-auto"
              disabled={!writable || service.archived}
              loading={pending === 'start'}
              onClick={() => act('start')}
            >
              {t('service.state.start')}
            </Button>
          )}

          {canRestart ? (
            <Button
              variant="secondary"
              block
              className="sm:w-auto"
              disabled={!writable || busy}
              loading={pending === 'restart'}
              onClick={() => void confirmThenAct('restart')}
            >
              {t('service.state.restart')}
            </Button>
          ) : null}
        </div>
      }
    >
      <div className="flex flex-col gap-2">
        <p className="text-sm text-ink-800 dark:text-ink-200">
          {pending === null ? (
            statusSentence(status)
          ) : (
            <span aria-live="polite">{inFlightSentence(pending, service.name)}</span>
          )}
        </p>

        <dl className="grid grid-cols-1 gap-x-6 gap-y-1 text-sm sm:grid-cols-2">
          <div className="flex items-baseline justify-between gap-3 py-0.5">
            <dt className="text-ink-500 dark:text-ink-400">{t('service.state.you_asked_for')}</dt>
            <dd className="font-medium text-ink-900 dark:text-ink-100">
              {intent === 'RUNNING' ? t('service.reported_states.RUNNING') : t('service.reported_states.STOPPED')}
            </dd>
          </div>

          <div className="flex items-baseline justify-between gap-3 py-0.5">
            <dt className="text-ink-500 dark:text-ink-400">{t('service.state.node_last_reported')}</dt>
            <dd className="font-medium text-ink-900 dark:text-ink-100">
              <RelativeTime at={status?.reportedAt} fallback={t('service.state.never')} />
            </dd>
          </div>

          {status?.health ? (
            <div className="flex items-baseline justify-between gap-3 py-0.5">
              <dt className="text-ink-500 dark:text-ink-400">{t('service.state.health_check')}</dt>
              <dd className="font-medium text-ink-900 dark:text-ink-100">
                {healthLabel(status.health)}
              </dd>
            </div>
          ) : null}

          <div className="flex items-baseline justify-between gap-3 py-0.5">
            <dt className="text-ink-500 dark:text-ink-400">{t('service.state.node')}</dt>
            <dd className="font-mono text-xs text-ink-900 dark:text-ink-100">
              {status?.nodeId ? shortId(status.nodeId) : t('service.state.none_yet')}
            </dd>
          </div>
        </dl>

        {settling && !service.archived ? (
          <p className="text-xs text-ink-500 dark:text-ink-400">
            {t('service.state.watching_node')}
          </p>
        ) : null}
      </div>
    </Card>
  )
}

/** What the panel says between the press and the node agreeing. */
function inFlightSentence(action: Action, name: string): string {
  switch (action) {
    case 'start':
      return t('service.state.in_flight.start', {name})
    case 'stop':
      return t('service.state.in_flight.stop', {name})
    default:
      return t('service.state.in_flight.restart', {name})
  }
}

function isSettling(status: ServiceSummary | null, intent: DesiredState): boolean {
  if (status === null) {
    return intent === 'RUNNING'
  }
  if (status.archived) {
    return false
  }
  if (status.drifting) {
    return true
  }
  return status.reportedState === 'PENDING' || status.reportedState === 'CREATING'
}

function shortId(id: string): string {
  return id.slice(0, 8)
}
