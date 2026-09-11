import {Card, CardFact, CardFacts, CopyButton, RelativeTime} from '@/shell'

import type {DeploymentSummary, DeploymentTarget} from './deployTypes'
import {
  durationOf,
  formatDuration,
  shortCommit,
  sourceLabel,
  triggerLabel,
} from './deployVocabulary'

/**
 * What was deployed, by whom, from where, and onto what.
 *
 * Every value here is a fact from the row rather than a summary of several: a customer
 * comparing "the deploy that worked" with "the deploy that did not" is comparing commits,
 * branches and nodes one at a time, and a paragraph would make them read all of it to find
 * the one that differs. A missing value says so in words - a webhook has no person behind
 * it, an app has no release directory - because an em dash with no explanation reads as a
 * bug.
 */
export function DeploymentFacts({
  deployment,
  target,
}: {
  deployment: DeploymentSummary
  target: DeploymentTarget
}) {
  const commit = shortCommit(deployment.commitSha)

  return (
    <Card title="Details">
      <CardFacts>
        <CardFact label="Source">{sourceLabel(deployment.source)}</CardFact>

        <CardFact label="Started by">
          {deployment.triggeredBy ?? triggerLabel(deployment.trigger)}
        </CardFact>

        <CardFact label="Branch or tag">
          {deployment.gitRef ?? 'Not from a repository'}
        </CardFact>

        <CardFact label="Commit">
          {commit === null ? (
            'Not from a repository'
          ) : (
            <span className="flex flex-wrap items-center gap-2">
              <code className="font-mono">{commit}</code>
              <CopyButton
                value={deployment.commitSha ?? commit}
                describedAs="Copy the full commit hash"
              />
            </span>
          )}
        </CardFact>

        {deployment.commitSubject ? (
          <CardFact label="Commit message">{deployment.commitSubject}</CardFact>
        ) : null}

        {deployment.commitAuthor ? (
          <CardFact label="Author">{deployment.commitAuthor}</CardFact>
        ) : null}

        {deployment.rolledBackFromSequence === null ? null : (
          <CardFact label="Restored">
            Deployment #{deployment.rolledBackFromSequence}
          </CardFact>
        )}

        <CardFact label="Queued">
          <RelativeTime at={deployment.queuedAt} />
        </CardFact>

        <CardFact label="Finished">
          {deployment.finishedAt === null ? (
            'Still running'
          ) : (
            <RelativeTime at={deployment.finishedAt} />
          )}
        </CardFact>

        <CardFact label="Took">{formatDuration(durationOf(deployment))}</CardFact>

        <CardFact label="Node">
          {deployment.nodeId === null ? (
            'No node has taken it yet'
          ) : (
            <code className="font-mono text-xs break-all">{deployment.nodeId}</code>
          )}
        </CardFact>

        <CardFact label="Release">
          {deployment.releasePath === null ? (
            target.kind === 'APP'
              ? 'An app has no release directory - it runs an image'
              : 'Nothing has been published for this deployment'
          ) : (
            <code className="font-mono text-xs break-all">{deployment.releasePath}</code>
          )}
        </CardFact>
      </CardFacts>
    </Card>
  )
}
