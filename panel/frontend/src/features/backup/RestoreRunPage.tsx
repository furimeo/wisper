import {Head, usePage} from '@inertiajs/react'

import {t} from '@/i18n'
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
      <Head title={t('backup.restoreRun.title', {target: restore.targetLabel})} />

      <PageHeader
        title={t('backup.restoreRun.heading', {target: restore.targetLabel})}
        description={restoreSentence(restore)}
        actions={
          <ButtonLink
            href={`/backups/${organizationId}/snapshots`}
            variant="secondary"
            block
            className="sm:w-auto"
          >
            {t('backup.restoreRun.back')}
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
            {t('backup.restoreRun.followingNotice')}
          </span>
        )}
      </div>

      {restore.errorMessage ? (
        <p className="rounded-xl border border-failed/50 bg-failed/10 px-4 py-3 text-sm leading-relaxed">
          <span className="font-medium">{t('backup.restoreRun.failedNotice')}</span>
          {restore.errorMessage}
        </p>
      ) : null}

      {restore.safetyRestorePointId ? (
        <Card
          title={t('backup.restoreRun.safetyTitle')}
          description={t('backup.restoreRun.safetyDesc')}
          action={
            <ButtonLink
              href={`/backups/${organizationId}/snapshots`}
              variant="secondary"
              size="sm"
            >
              {t('backup.restoreRun.findIt')}
            </ButtonLink>
          }
        >
          <p className="font-mono text-xs break-all text-ink-600 dark:text-ink-400">
            {restore.safetyRestorePointId}
          </p>
        </Card>
      ) : null}

      <Card title={t('backup.restoreRun.progressTitle')} description={t('backup.restoreRun.progressDesc')} padded={false}>
        {restore.log && restore.log.trim().length > 0 ? (
          <pre className="max-h-96 overflow-auto px-4 py-3 font-mono text-xs leading-relaxed whitespace-pre-wrap text-ink-800 md:px-5 dark:text-ink-200">
            {restore.log}
          </pre>
        ) : (
          <p className="px-4 py-4 text-sm leading-relaxed text-ink-500 md:px-5 dark:text-ink-400">
            {restore.finished
              ? t('backup.restoreRun.emptyFinished')
              : t('backup.restoreRun.emptyRunning')}
          </p>
        )}
      </Card>

      <Card title={t('backup.restoreRun.factsTitle')}>
        <CardFacts>
          <CardFact label={t('backup.restoreRun.factWhat')}>{restore.targetLabel}</CardFact>
          <CardFact label={t('backup.restoreRun.factMode')}>{restoreModeLabel(restore.mode)}</CardFact>
          <CardFact label={t('backup.restoreRun.factTaken')}>
            <RelativeTime at={restore.snapshotTakenAt} fallback={t('backup.restoreRun.unknown')} />
          </CardFact>
          <CardFact label={t('backup.restoreRun.factStarted')}>
            <RelativeTime at={restore.startedAt} fallback={t('backup.restoreRun.notYet')} />
          </CardFact>
          <CardFact label={t('backup.restoreRun.factFinished')}>
            <RelativeTime at={restore.finishedAt} fallback={t('backup.restoreRun.stillRunning')} />
          </CardFact>
          <CardFact label={t('backup.restoreRun.factBytes')}>
            <ByteSize bytes={restore.bytesRestored} fallback={t('database.adminDetail.facts.notReported')} />
          </CardFact>
          <CardFact label={t('backup.restoreRun.factNode')}>
            {restore.nodeId ? (
              <span className="font-mono text-xs">{restore.nodeId.slice(0, 8)}</span>
            ) : (
              <span className="text-ink-500 dark:text-ink-400">{t('backup.restoreRun.notAssigned')}</span>
            )}
          </CardFact>
          <CardFact label={t('backup.restoreRun.factBy')}>{restore.requestedBy ?? t('backup.restoreRun.platform')}</CardFact>
        </CardFacts>
      </Card>
    </div>
  )
}
