import {t} from '@/i18n'
import {cx} from '@/shell'

import type {QuotaAllowance} from './orgTypes'
import {
  QUOTA_WARNING_SHARE,
  quotaConsequence,
  quotaLabel,
  quotaSentence,
  quotaShare,
  quotaSourceLabel,
} from './quotaVocabulary'

/**
 * One limit, drawn.
 *
 * The bar is the small part. The point of this component is the sentence that appears at
 * ninety per cent: a customer who is told "you are at 90% of your disk - you cannot
 * attach or grow a volume" can act, and a customer who discovers it when a form is
 * refused cannot. So the warning is not a colour change, it is words, and it names the
 * thing that will stop working.
 *
 * Over the limit is drawn full and red rather than clamped. A tenant whose plan was
 * lowered underneath them is over quota and needs to see it.
 */
export function QuotaMeter({
  allowance,
  className,
}: {
  allowance: QuotaAllowance
  className?: string
}) {
  const {resource, used, limit, source} = allowance
  const share = quotaShare(used, limit)
  const over = limit > 0 && used > limit
  const near = !over && share >= QUOTA_WARNING_SHARE
  const percent = Math.round(share * 100)

  return (
    <div className={cx('py-3', className)}>
      <div className="flex items-baseline justify-between gap-3">
        <span className="text-sm font-medium text-ink-800 dark:text-ink-200">
          {quotaLabel(resource)}
        </span>
        <span
          className={cx(
            'text-sm tabular-nums',
            over ? 'text-failed' : near ? 'text-degraded' : 'text-ink-500 dark:text-ink-400',
          )}
        >
          {quotaSentence(resource, used, limit)}
        </span>
      </div>

      <div
        className="mt-1.5 h-1.5 w-full overflow-hidden rounded-full bg-ink-200 dark:bg-ink-800"
        role="img"
        aria-label={`${quotaLabel(resource)}: ${quotaSentence(resource, used, limit)} used, ${percent}%`}
      >
        <div
          className={cx(
            'h-full rounded-full',
            over ? 'bg-failed' : near ? 'bg-degraded' : 'bg-accent-500',
          )}
          style={{width: `${Math.max(over ? 100 : percent, used > 0 ? 2 : 0)}%`}}
        />
      </div>

      {over || near ? (
        <p
          className={cx(
            'mt-1.5 text-xs leading-relaxed',
            over ? 'text-failed' : 'text-degraded',
          )}
        >
          {over
            ? t('org.quota.overTheLimit', {consequence: quotaConsequence(resource)})
            : t('org.quota.nearTheLimit', {
                percent,
                label: quotaLabel(resource).toLowerCase(),
                consequence: quotaConsequence(resource),
              })}
        </p>
      ) : source === 'UNSET' ? (
        <p className="mt-1.5 text-xs text-ink-500 dark:text-ink-400">
          {t('org.quota.source.unsetHint')}
        </p>
      ) : source === 'ORGANIZATION_OVERRIDE' ? (
        <p className="mt-1.5 text-xs text-ink-500 dark:text-ink-400">
          {quotaSourceLabel(source)}.
        </p>
      ) : null}
    </div>
  )
}
