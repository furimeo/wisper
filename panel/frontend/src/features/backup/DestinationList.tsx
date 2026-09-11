import {router} from '@inertiajs/react'
import type {ReactNode} from 'react'
import {useState} from 'react'

import {
  Badge,
  Button,
  ByteSize,
  Card,
  EmptyState,
  Icon,
  RelativeTime,
  askConfirmation,
} from '@/shell'

import type {DestinationView} from './backupTypes'
import {destinationKindLabel, destinationSentence, destinationSummary} from './backupVocabulary'

/**
 * Every destination this scope can see, with the button that proves one works.
 *
 * Check is the important control here and it is on every row rather than behind an
 * overflow. A bucket whose credentials are wrong accepts the configuration happily and
 * fails at three in the morning, once, quietly - so "never checked" is drawn as a warning
 * and checking is one press.
 *
 * A platform-wide row appears on a customer's page and is not editable there. Its
 * `editable` is false and the operator screen owns it; both this and the use-case behind
 * every write check, which is deliberate - the page decides what to draw and the server
 * decides what may happen.
 */
export function DestinationList({
  basePath,
  destinations,
  writable,
  onEdit,
}: {
  /** `/backups/{organizationId}/destinations` or `/admin/backups/destinations`. */
  basePath: string
  destinations: DestinationView[]
  writable: boolean
  onEdit: (destination: DestinationView) => void
}) {
  const [pending, setPending] = useState<string | null>(null)

  function post(destination: DestinationView, action: string) {
    setPending(`${destination.id}:${action}`)
    router.post(
      `${basePath}/${destination.id}/${action}`,
      {},
      {preserveScroll: true, onFinish: () => setPending(null)},
    )
  }

  async function remove(destination: DestinationView) {
    const confirmed = await askConfirmation({
      title: `Delete “${destination.name}”?`,
      body: 'Nothing stored there is touched. The panel just stops offering it as somewhere to write.',
      confirmLabel: 'Delete it',
      tone: 'danger',
    })
    if (confirmed) {
      post(destination, 'delete')
    }
  }

  if (destinations.length === 0) {
    return (
      <Card>
        <EmptyState
          icon={<Icon name="backup" />}
          title="No destinations yet"
          description="A backup needs somewhere to go. An S3-compatible bucket is the useful
            answer - it survives the machine - and a path on the node is there for the cases where
            offsite is not an option."
        />
      </Card>
    )
  }

  return (
    <ul className="flex flex-col gap-3">
      {destinations.map((destination) => (
        <li key={destination.id}>
          <Card
            title={
              <span className="flex items-center gap-2">
                <span className="truncate">{destination.name}</span>
                <Badge>{destinationKindLabel(destination.kind)}</Badge>
                {destination.platformWide ? <Badge tone="accent">platform</Badge> : null}
              </span>
            }
            description={destinationSummary(destination)}
            action={
              <Badge
                tone={
                  destination.lastCheckError
                    ? 'failed'
                    : !destination.enabled
                      ? 'neutral'
                      : destination.provenReachable
                        ? 'running'
                        : 'degraded'
                }
                dot
              >
                {destination.lastCheckError
                  ? 'Check failed'
                  : !destination.enabled
                    ? 'Off'
                    : destination.provenReachable
                      ? 'Reachable'
                      : 'Never checked'}
              </Badge>
            }
            footer={
              writable && destination.editable ? (
                <div className="flex flex-col gap-2 sm:flex-row">
                  <Button
                    variant="secondary"
                    block
                    className="sm:w-auto"
                    loading={pending === `${destination.id}:verify`}
                    onClick={() => post(destination, 'verify')}
                  >
                    Check it
                  </Button>
                  <Button
                    variant="secondary"
                    block
                    className="sm:w-auto"
                    onClick={() => onEdit(destination)}
                  >
                    Edit
                  </Button>
                  <Button
                    variant="ghost"
                    block
                    className="text-failed sm:ml-auto sm:w-auto"
                    disabled={!destination.removable}
                    loading={pending === `${destination.id}:delete`}
                    onClick={() => void remove(destination)}
                  >
                    Delete
                  </Button>
                </div>
              ) : (
                <p className="text-sm text-ink-500 dark:text-ink-400">
                  {destination.platformWide
                    ? 'Provided by the platform. Anybody here can back up to it; only an operator can change it.'
                    : 'Your role here is read-only, so this is shown but not editable.'}
                </p>
              )
            }
          >
            <p className="text-sm leading-relaxed text-ink-700 dark:text-ink-300">
              {destinationSentence(destination)}
            </p>

            <dl className="mt-3 grid grid-cols-2 gap-x-4 gap-y-2 text-sm sm:grid-cols-4">
              <Fact label="Schedules" value={String(destination.policyCount)} />
              <Fact label="Snapshots" value={String(destination.snapshotCount)} />
              <Fact
                label="Stored"
                value={<ByteSize bytes={destination.storedBytes} />}
              />
              <Fact
                label="Last checked"
                value={<RelativeTime at={destination.lastCheckedAt} fallback="never" />}
              />
            </dl>

            {writable && destination.editable && !destination.removable ? (
              <p className="mt-3 text-xs text-ink-500 dark:text-ink-400">
                It cannot be deleted while a schedule points at it or a snapshot sits on it.
              </p>
            ) : null}
          </Card>
        </li>
      ))}
    </ul>
  )
}

function Fact({label, value}: {label: string; value: ReactNode}) {
  return (
    <div className="min-w-0">
      <dt className="text-xs text-ink-500 dark:text-ink-400">{label}</dt>
      <dd className="truncate tabular-nums text-ink-900 dark:text-ink-100">{value}</dd>
    </div>
  )
}
