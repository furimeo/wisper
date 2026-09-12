import {t} from '@/i18n'
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
      <Card title={t('deploy.retention.app_title')}>
        <p className="text-sm leading-relaxed text-ink-700 dark:text-ink-300">
          {t('deploy.retention.app_body')}
        </p>
        <p className="mt-2 text-sm text-ink-500 dark:text-ink-400">
          {restorable.length === 0
            ? t('deploy.retention.app_restorable_none')
            : t('deploy.retention.app_restorable_count', {count: restorable.length})}
        </p>
      </Card>
    )
  }

  return (
    <Card
      title={t('deploy.retention.site_title')}
      action={
        <ButtonLink
          href={`/services/${target.serviceId}/settings`}
          variant="ghost"
          icon={<Icon name="settings" className="size-4" />}
        >
          {t('deploy.retention.change')}
        </ButtonLink>
      }
    >
      <p className="text-sm leading-relaxed text-ink-700 dark:text-ink-300">
        {t('deploy.retention.site_body', {
          count: target.keepReleases,
          suffix: target.keepReleases === 1 ? '' : 's',
        })}
      </p>

      <dl className="mt-3 grid grid-cols-2 gap-3">
        <div>
          <dt className="text-xs font-medium uppercase tracking-wide text-ink-500 dark:text-ink-400">
            {t('deploy.retention.live_now')}
          </dt>
          <dd className="text-sm text-ink-900 dark:text-ink-100">
            {live ? `#${live.sequence}` : t('deploy.retention.nothing_published')}
          </dd>
        </div>
        <div>
          <dt className="text-xs font-medium uppercase tracking-wide text-ink-500 dark:text-ink-400">
            {t('deploy.retention.can_rollback_to')}
          </dt>
          <dd className="text-sm tabular-nums text-ink-900 dark:text-ink-100">
            {restorable.length === 0
              ? t('deploy.retention.none_yet')
              : t('deploy.retention.releases_count', {count: restorable.length})}
          </dd>
        </div>
      </dl>

      <p className="mt-3 text-sm text-ink-500 dark:text-ink-400">
        {t('deploy.retention.footnote')}
      </p>
    </Card>
  )
}
