import {Link} from '@inertiajs/react'

import {t} from '@/i18n'
import {Badge, Icon} from '@/shell'

import type {OrganizationSummary} from './orgTypes'
import {roleLabel} from './roleVocabulary'

/**
 * One organization the account belongs to.
 *
 * The whole card is the link, because a 375px-wide target is one nobody misses and the
 * alternative is a chevron the size of a fingernail in the corner.
 *
 * A suspended tenant is still reachable and says why. Hiding it would leave the customer
 * with a project list that has stopped accepting anything and no screen explaining it -
 * the suspension reason is written by an operator precisely so it can be read here.
 */
export function OrganizationRow({organization}: {organization: OrganizationSummary}) {
  return (
    <li>
      <Link
        href={`/orgs/${organization.id}`}
        className="flex touch-target items-center gap-3 rounded-xl border border-ink-200 bg-white px-4 py-3.5 transition-colors hover:border-accent-500/60 dark:border-ink-800 dark:bg-ink-900"
      >
        <span className="min-w-0 flex-1">
          <span className="flex flex-wrap items-center gap-2">
            <span className="truncate text-sm font-semibold text-ink-900 dark:text-ink-100">
              {organization.name}
            </span>
            <Badge tone={organization.suspended ? 'failed' : 'neutral'}>
              {organization.suspended ? t('org.row.suspended') : roleLabel(organization.role)}
            </Badge>
          </span>

          <span className="mt-0.5 block truncate text-sm text-ink-500 dark:text-ink-400">
            /{organization.slug}
          </span>

          {organization.suspended ? (
            <span className="mt-1 block text-xs leading-relaxed text-failed">
              {organization.suspensionReason || t('org.row.suspendedDefaultReason')}
            </span>
          ) : null}
        </span>

        <Icon name="chevronRight" className="size-4 shrink-0 text-ink-400" />
      </Link>
    </li>
  )
}
