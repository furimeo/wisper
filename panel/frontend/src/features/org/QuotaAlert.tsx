import {cx} from '@/shell'

import type {QuotaAllowance} from './orgTypes'
import {QUOTA_WARNING_SHARE, quotaConsequence, quotaLabel, quotaSentence, quotaShare} from './quotaVocabulary'

/**
 * The one banner that says a limit is about to stop something working.
 *
 * `QuotaMeter` draws one allowance; this reads all thirteen and speaks up only about the
 * ones that matter. Thirteen bars on an overview is a wall nobody reads, and the number
 * that is about to refuse the next deploy is somewhere in the middle of it.
 *
 * Over the limit and near it are different sentences, not different shades. A tenant whose
 * plan was lowered underneath them is already over and needs to know that something has
 * stopped; a tenant at ninety per cent still has a choice to make, and telling them is what
 * makes it a choice rather than a surprise.
 *
 * Nothing renders when nothing is pressing. An empty reassurance box on every page is how
 * a warning becomes furniture.
 */
export function QuotaAlert({
  allowances,
  className,
}: {
  allowances: QuotaAllowance[]
  className?: string
}) {
  const over = allowances.filter((allowance) => allowance.limit > 0 && allowance.used > allowance.limit)
  const near = allowances.filter(
    (allowance) =>
      !over.includes(allowance) && quotaShare(allowance.used, allowance.limit) >= QUOTA_WARNING_SHARE,
  )

  if (over.length === 0 && near.length === 0) {
    return null
  }

  const worst = over.length > 0 ? 'over' : 'near'

  return (
    <div
      className={cx(
        'rounded-xl border px-4 py-3.5 md:px-5',
        worst === 'over' ? 'border-failed/40 bg-failed/10' : 'border-degraded/40 bg-degraded/10',
        className,
      )}
      role="status"
    >
      <p className="text-sm font-semibold text-ink-900 dark:text-ink-100">
        {worst === 'over'
          ? over.length === 1
            ? 'One limit has been passed'
            : `${over.length} limits have been passed`
          : near.length === 1
            ? 'One limit is nearly reached'
            : `${near.length} limits are nearly reached`}
      </p>

      <ul className="mt-2 flex flex-col gap-1.5">
        {[...over, ...near].map((allowance) => (
          <li key={allowance.resource} className="text-sm leading-relaxed text-ink-800 dark:text-ink-200">
            <span className="font-medium">{quotaLabel(allowance.resource)}</span>
            {' - '}
            {quotaSentence(allowance.resource, allowance.used, allowance.limit)}.{' '}
            <span className="text-ink-600 dark:text-ink-400">
              {quotaConsequence(allowance.resource)}
            </span>
          </li>
        ))}
      </ul>
    </div>
  )
}
