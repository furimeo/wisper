import {t} from '@/i18n'
import {Card, CardFact, CardFacts, CopyButton, RelativeTime} from '@/shell'

import type {DeploymentSummary, DeploymentTarget} from './deployTypes'
import {
  durationOf,
  formatDuration,
  shortCommit,
  sourceLabel,
  triggerLabel,
} from './deployVocabulary'

export function DeploymentFacts({
  deployment,
  target,
}: {
  deployment: DeploymentSummary
  target: DeploymentTarget
}) {
  const commit = shortCommit(deployment.commitSha)

  return (
    <Card title={t('deploy.facts.title')}>
      <CardFacts>
        <CardFact label={t('deploy.facts.source')}>{sourceLabel(deployment.source)}</CardFact>

        <CardFact label={t('deploy.facts.started_by')}>
          {deployment.triggeredBy ?? triggerLabel(deployment.trigger)}
        </CardFact>

        <CardFact label={t('deploy.facts.branch_or_tag')}>
          {deployment.gitRef ?? t('deploy.facts.not_from_repo')}
        </CardFact>

        <CardFact label={t('deploy.facts.commit')}>
          {commit === null ? (
            t('deploy.facts.not_from_repo')
          ) : (
            <span className="flex flex-wrap items-center gap-2">
              <code className="font-mono">{commit}</code>
              <CopyButton
                value={deployment.commitSha ?? commit}
                describedAs={t('deploy.facts.copy_hash')}
              />
            </span>
          )}
        </CardFact>

        {deployment.commitSubject ? (
          <CardFact label={t('deploy.facts.commit_message')}>{deployment.commitSubject}</CardFact>
        ) : null}

        {deployment.commitAuthor ? (
          <CardFact label={t('deploy.facts.author')}>{deployment.commitAuthor}</CardFact>
        ) : null}

        {deployment.rolledBackFromSequence === null ? null : (
          <CardFact label={t('deploy.facts.restored')}>
            {t('deploy.facts.restored_value', {sequence: deployment.rolledBackFromSequence})}
          </CardFact>
        )}

        <CardFact label={t('deploy.facts.queued')}>
          <RelativeTime at={deployment.queuedAt} />
        </CardFact>

        <CardFact label={t('deploy.facts.finished')}>
          {deployment.finishedAt === null ? (
            t('deploy.facts.still_running')
          ) : (
            <RelativeTime at={deployment.finishedAt} />
          )}
        </CardFact>

        <CardFact label={t('deploy.facts.took')}>{formatDuration(durationOf(deployment))}</CardFact>

        <CardFact label={t('deploy.facts.node')}>
          {deployment.nodeId === null ? (
            t('deploy.facts.no_node_yet')
          ) : (
            <code className="font-mono text-xs break-all">{deployment.nodeId}</code>
          )}
        </CardFact>

        <CardFact label={t('deploy.facts.release')}>
          {deployment.releasePath === null ? (
            target.kind === 'APP'
              ? t('deploy.facts.app_no_release_dir')
              : t('deploy.facts.nothing_published')
          ) : (
            <code className="font-mono text-xs break-all">{deployment.releasePath}</code>
          )}
        </CardFact>
      </CardFacts>
    </Card>
  )
}
