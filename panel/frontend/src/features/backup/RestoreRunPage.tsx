import {Head, usePage} from '@inertiajs/react'

import {
  Badge,
  ButtonLink,
  ByteSize,
  Card,
  CardFact,
  CardFacts,
  PageHeader,
  RelativeTime,
  Spinner,
  useCurrentOrganization,
} from '@/shell'
import type {MemberRole} from '@/shell'

import type {RestoreRunView} from './backupTypes'
import {restoreModeLabel, restoreSentence, restoreStateLabel, restoreStateTone} from './backupVocabulary'
import {useLiveRestores} from './useLiveRestores'

/**
 * `GET /backups/{organizationId}/restores/{restoreRunId}` - one restore, reporting itself.
 *
 * This page exists because of one sentence in design §8.3: restoring is a button. A button
 * that starts a job taking minutes needs somewhere to land, and without one the customer
 * is left on a list with a flash message and no way to find out what happened.
 *
 * The safety snapshot is the other thing this page is for. An in-place restore takes a
 * `PRE_RESTORE` snapshot of the live data before it overwrites anything, and the link to
 * it is the way back if the restore turns out to have been the wrong decision. It is shown
 * plainly rather than buried, because the moment somebody needs it is the moment they are
 * least able to go looking.
 */
type RestoreRunProps = {
  restore: RestoreRunView
  viewerRole: MemberRole
}

export default function RestoreRunPage() {
  const {restore} = usePage<RestoreRunProps>().props
  const organization = useCurrentOrganization()
  const organizationId = organization?.id ?? ''

  useLiveRestores(['restore'], !restore.finished)

  return (
    <div className="flex flex-col gap-4">
      <Head title={`Restore of ${restore.targetLabel}`} />

      <PageHeader
        title={`Restoring ${restore.targetLabel}`}
        description={restoreSentence(restore)}
        actions={
          <ButtonLink
            href={`/backups/${organizationId}/snapshots`}
            variant="secondary"
            block
            className="sm:w-auto"
          >
            Back to snapshots
          </ButtonLink>
        }
      />

      <div className="flex flex-wrap items-center gap-2">
        <Badge tone={restoreStateTone(restore.state)} dot pulse={!restore.finished}>
          {restoreStateLabel(restore.state)}
        </Badge>
        <Badge>{restoreModeLabel(restore.mode)}</Badge>
        {restore.finished ? null : (
          <span className="flex items-center gap-2 text-sm text-ink-500 dark:text-ink-400">
            <Spinner />
            This page follows it on its own.
          </span>
        )}
      </div>

      {restore.errorMessage ? (
        <p className="rounded-xl border border-failed/50 bg-failed/10 px-4 py-3 text-sm leading-relaxed">
          <span className="font-medium">It failed: </span>
          {restore.errorMessage}
        </p>
      ) : null}

      {restore.safetyRestorePointId ? (
        <Card
          title="The way back"
          description="Before anything was overwritten, a snapshot was taken of the data that was
            there. If this restore turns out to have been the wrong one, that snapshot is what you
            restore next."
          action={
            <ButtonLink
              href={`/backups/${organizationId}/snapshots`}
              variant="secondary"
              size="sm"
            >
              Find it
            </ButtonLink>
          }
        >
          <p className="font-mono text-xs break-all text-ink-600 dark:text-ink-400">
            {restore.safetyRestorePointId}
          </p>
        </Card>
      ) : null}

      <Card title="Progress" description="The node's own log, as it reports it." padded={false}>
        {restore.log && restore.log.trim().length > 0 ? (
          <pre className="max-h-96 overflow-auto px-4 py-3 font-mono text-xs leading-relaxed whitespace-pre-wrap text-ink-800 md:px-5 dark:text-ink-200">
            {restore.log}
          </pre>
        ) : (
          <p className="px-4 py-4 text-sm leading-relaxed text-ink-500 md:px-5 dark:text-ink-400">
            {restore.finished
              ? 'The node finished without sending a log. Nothing is missing - a small restore has little to say.'
              : 'Nothing yet. A restore emits a handful of lines over several minutes, and they appear here as the node sends them.'}
          </p>
        )}
      </Card>

      <Card title="Facts">
        <CardFacts>
          <CardFact label="What was restored">{restore.targetLabel}</CardFact>
          <CardFact label="Mode">{restoreModeLabel(restore.mode)}</CardFact>
          <CardFact label="Snapshot taken">
            <RelativeTime at={restore.snapshotTakenAt} fallback="unknown" />
          </CardFact>
          <CardFact label="Started">
            <RelativeTime at={restore.startedAt} fallback="not yet" />
          </CardFact>
          <CardFact label="Finished">
            <RelativeTime at={restore.finishedAt} fallback="still running" />
          </CardFact>
          <CardFact label="Bytes restored">
            <ByteSize bytes={restore.bytesRestored} fallback="not reported" />
          </CardFact>
          <CardFact label="Node">
            {restore.nodeId ? (
              <span className="font-mono text-xs">{restore.nodeId.slice(0, 8)}</span>
            ) : (
              <span className="text-ink-500 dark:text-ink-400">not assigned yet</span>
            )}
          </CardFact>
          <CardFact label="Started by">{restore.requestedBy ?? 'the platform'}</CardFact>
        </CardFacts>
      </Card>
    </div>
  )
}
