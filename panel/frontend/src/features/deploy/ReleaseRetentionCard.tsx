import {ButtonLink, Card, Icon} from '@/shell'

import type {DeploymentSummary, DeploymentTarget} from './deployTypes'

/**
 * How far back a rollback can reach, and why it stops there.
 *
 * A rollback is instant because the release directory is still on the node - and that is
 * also the reason it has a floor. The node keeps `keepReleases` directories under
 * `releases/` and sweeps the rest; the panel deletes the row when the directory goes,
 * because a rollback button that fails is worse than no button. So this card exists to
 * answer the question a customer only asks in an emergency: how many versions back can I
 * actually go, right now.
 *
 * An app has no release directory - it deploys an image - so its history is never swept
 * and the numbers here would be a fiction. It gets a different sentence instead of a
 * hidden card, because "why does my app not show this" is a worse question than the
 * answer.
 */
export function ReleaseRetentionCard({
  target,
  deployments,
}: {
  target: DeploymentTarget
  deployments: DeploymentSummary[]
}) {
  const live = deployments.find((one) => one.current) ?? null
  const restorable = deployments.filter(
    (one) => one.status === 'SUCCEEDED' && !one.current,
  )

  if (target.kind === 'APP') {
    return (
      <Card title="Rolling back">
        <p className="text-sm leading-relaxed text-ink-700 dark:text-ink-300">
          An app is deployed as an image, so nothing on the node is swept and every
          successful deployment in this list stays available to roll back to. Rolling back
          restarts the exact digest that was running, not whatever the tag points at today.
        </p>
        <p className="mt-2 text-sm text-ink-500 dark:text-ink-400">
          {restorable.length === 0
            ? 'There is no earlier successful deployment to go back to yet.'
            : `${countLabel(restorable.length)} to roll back to.`}
        </p>
      </Card>
    )
  }

  return (
    <Card
      title="Release retention"
      action={
        <ButtonLink
          href={`/services/${target.serviceId}/settings`}
          variant="ghost"
          icon={<Icon name="settings" className="size-4" />}
        >
          Change
        </ButtonLink>
      }
    >
      <p className="text-sm leading-relaxed text-ink-700 dark:text-ink-300">
        The node keeps the last{' '}
        <span className="font-semibold tabular-nums">{target.keepReleases}</span> release
        {target.keepReleases === 1 ? '' : 's'} of this site on disk. Anything older is
        deleted from the node and drops out of this list, because a rollback to a directory
        that is gone would fail.
      </p>

      <dl className="mt-3 grid grid-cols-2 gap-3">
        <div>
          <dt className="text-xs font-medium uppercase tracking-wide text-ink-500 dark:text-ink-400">
            Live now
          </dt>
          <dd className="text-sm text-ink-900 dark:text-ink-100">
            {live ? `#${live.sequence}` : 'Nothing published yet'}
          </dd>
        </div>
        <div>
          <dt className="text-xs font-medium uppercase tracking-wide text-ink-500 dark:text-ink-400">
            Can roll back to
          </dt>
          <dd className="text-sm tabular-nums text-ink-900 dark:text-ink-100">
            {restorable.length === 0 ? 'None yet' : countLabel(restorable.length)}
          </dd>
        </div>
      </dl>

      <p className="mt-3 text-sm text-ink-500 dark:text-ink-400">
        The live release is never swept, and neither is the one a live rollback points back
        at - so a site serving release #3 after six later deploys keeps #3 whatever the
        limit says.
      </p>
    </Card>
  )
}

function countLabel(count: number): string {
  return count === 1 ? '1 release' : `${count} releases`
}
