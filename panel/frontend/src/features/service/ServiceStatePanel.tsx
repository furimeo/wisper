import {router} from '@inertiajs/react'
import {useState} from 'react'

import {Badge, Button, Card, RelativeTime, askConfirmation} from '@/shell'

import {ServiceStatusBadge} from './ServiceStatusBadge'
import type {DesiredState, ServiceSummary, ServiceView} from './serviceTypes'
import {healthLabel, statusSentence} from './serviceVocabulary'
import {useLiveServiceStatus} from './useLiveServiceStatus'

/**
 * What the service is doing, and the three buttons that change it.
 *
 * One component and not two, because the optimistic state is what couples them. Pressing
 * Stop has to change what the panel says about the service in the same frame as the press
 * - on a phone over 4G the round trip is long enough for a customer to press it again -
 * and the sentence it changes is the one two lines above the button. Splitting the
 * display from the controls would mean holding that half-second of intent in the page and
 * threading it back down into both.
 *
 * Intent and fact are drawn separately throughout. `service.desiredState` is what the
 * customer asked for and the panel owns it; `status.reportedState` is what the node last
 * saw and the node owns it (AGENTS.md §4.2). The case worth designing for is the one
 * where they disagree - asked to run, reported crashed - and a single pill cannot show
 * it.
 *
 * Stop and Restart ask first. Start does not: the failure mode of an accidental start is
 * a container that runs, and the failure mode of an accidental stop on a 375px screen is
 * a customer's site going down because a thumb landed two millimetres left.
 */
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
            title: `Stop ${service.name}?`,
            body: service.site
              ? 'The node stops serving this site until you start it again. The files and every ' +
                'release stay where they are.'
              : 'The container is stopped. Its volumes, its logs and its environment stay where ' +
                'they are, and starting it again brings it back.',
            confirmLabel: 'Stop it',
            tone: 'danger',
          }
        : {
            title: `Restart ${service.name}?`,
            body:
              'The container is stopped and started again, so whatever it is serving right now ' +
              'is interrupted for a few seconds.',
            confirmLabel: 'Restart it',
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
      title="State"
      action={status ? <ServiceStatusBadge status={status} /> : <Badge tone="neutral">Not placed</Badge>}
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
              Stop
            </Button>
          ) : (
            <Button
              block
              className="sm:w-auto"
              disabled={!writable || service.archived}
              loading={pending === 'start'}
              onClick={() => act('start')}
            >
              Start
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
              Restart
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
            <dt className="text-ink-500 dark:text-ink-400">You asked for</dt>
            <dd className="font-medium text-ink-900 dark:text-ink-100">
              {intent === 'RUNNING' ? 'Running' : 'Stopped'}
            </dd>
          </div>

          <div className="flex items-baseline justify-between gap-3 py-0.5">
            <dt className="text-ink-500 dark:text-ink-400">Node last reported</dt>
            <dd className="font-medium text-ink-900 dark:text-ink-100">
              <RelativeTime at={status?.reportedAt} fallback="never" />
            </dd>
          </div>

          {status?.health ? (
            <div className="flex items-baseline justify-between gap-3 py-0.5">
              <dt className="text-ink-500 dark:text-ink-400">Health check</dt>
              <dd className="font-medium text-ink-900 dark:text-ink-100">
                {healthLabel(status.health)}
              </dd>
            </div>
          ) : null}

          <div className="flex items-baseline justify-between gap-3 py-0.5">
            <dt className="text-ink-500 dark:text-ink-400">Node</dt>
            <dd className="font-mono text-xs text-ink-900 dark:text-ink-100">
              {status?.nodeId ? shortId(status.nodeId) : 'none yet'}
            </dd>
          </div>
        </dl>

        {settling && !service.archived ? (
          <p className="text-xs text-ink-500 dark:text-ink-400">
            Watching for the node to report. It reconciles every fifteen seconds, so this
            settles on its own.
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
      return `Asking the node to run ${name}. It picks the change up on its next reconcile.`
    case 'stop':
      return `Asking the node to stop ${name}. Its volumes and logs stay where they are.`
    default:
      return `Restarting ${name}.`
  }
}

/**
 * Whether the answer is expected to change shortly, which is what decides how often the
 * page asks for it.
 *
 * Nothing reported and nothing asked for is not settling - a stopped service nobody has
 * placed will report nothing for ever, and polling it every six seconds would spend a
 * customer's mobile data on a question with a permanent answer.
 */
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

/** A node id is a UUID; the first block is enough to tell two nodes apart on a phone. */
function shortId(id: string): string {
  return id.slice(0, 8)
}
