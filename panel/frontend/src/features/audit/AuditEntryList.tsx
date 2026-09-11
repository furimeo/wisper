import {Link} from '@inertiajs/react'
import type {ReactNode} from 'react'

import {Badge, Card, DataList, EmptyState, Icon, RelativeTime} from '@/shell'

import type {AuditFilter, AuditLogEntry} from './auditTypes'
import type {FilterOverrides} from './auditQuery'
import {auditUrl} from './auditQuery'
import {
  actorKindLabel,
  entrySentence,
  outcomeLabel,
  outcomeTone,
  targetOf,
  verbOf,
} from './auditVocabulary'

/**
 * The trail itself.
 *
 * On a phone each entry is one sentence - "Alice was refused permission to delete node
 * fra-01" - because a six-column table at 375px is six columns of two words. On a desktop
 * the columns come back, because comparing forty rows is exactly what a wide screen is
 * for.
 *
 * Every id in a row is a link that narrows the list to it. That is the whole workflow of
 * an investigation: find one interesting entry, then ask what else that actor did, then
 * what else happened to that object - and doing it by copying UUIDs into a filter form is
 * how people give up half-way.
 */
export function AuditEntryList({
  entries,
  filter,
}: {
  entries: AuditLogEntry[]
  filter: AuditFilter
}) {
  return (
    <Card padded={false}>
      <DataList
        items={entries}
        keyOf={(entry) => entry.id}
        label="audit entries"
        empty={
          <EmptyState
            icon={<Icon name="audit" />}
            title="Nothing matches"
            description="Either nothing like this has happened, or the filter is narrower than you
              meant. Every state-changing action on the platform is written here, including the
              ones that were refused."
          />
        }
        primary={(entry) => (
          <span className="flex items-center gap-2">
            <span className="truncate">{verbOf(entry.action)}</span>
            <Badge tone={outcomeTone(entry.outcome)} dot={entry.outcome !== 'SUCCEEDED'}>
              {outcomeLabel(entry.outcome)}
            </Badge>
          </span>
        )}
        secondary={(entry) => (
          <span className="line-clamp-3">
            {entrySentence(entry)}
            {entry.detail ? ` ${entry.detail}` : ''}
          </span>
        )}
        trailing={(entry) => (
          <RelativeTime
            at={entry.occurredAt}
            className="text-xs text-ink-500 dark:text-ink-400"
          />
        )}
        columns={[
          {
            key: 'when',
            header: 'When',
            cell: (entry) => <RelativeTime at={entry.occurredAt} className="text-xs" />,
          },
          {
            key: 'actor',
            header: 'Who',
            cell: (entry) => (
              <div className="flex flex-col gap-0.5">
                <NarrowLink
                  filter={filter}
                  overrides={
                    entry.actorAccountId
                      ? {accountId: entry.actorAccountId, offset: null}
                      : entry.nodeId
                        ? {nodeId: entry.nodeId, offset: null}
                        : null
                  }
                >
                  {entry.actorLabel}
                </NarrowLink>
                <span className="text-xs text-ink-500 dark:text-ink-400">
                  {actorKindLabel(entry.actorKind)}
                </span>
              </div>
            ),
          },
          {
            key: 'action',
            header: 'Did',
            cell: (entry) => (
              <NarrowLink filter={filter} overrides={{action: entry.action, offset: null}}>
                {verbOf(entry.action)}
              </NarrowLink>
            ),
          },
          {
            key: 'target',
            header: 'To',
            cell: (entry) => (
              <NarrowLink
                filter={filter}
                overrides={
                  entry.targetKind
                    ? {
                        targetKind: entry.targetKind,
                        targetId: entry.targetId,
                        offset: null,
                      }
                    : null
                }
              >
                {targetOf(entry)}
              </NarrowLink>
            ),
          },
          {
            key: 'organization',
            header: 'Tenant',
            cell: (entry) =>
              entry.organizationId === null ? (
                <span className="text-ink-400">platform</span>
              ) : (
                <NarrowLink
                  filter={filter}
                  overrides={{organizationId: entry.organizationId, offset: null}}
                >
                  <span className="font-mono text-xs">{entry.organizationId.slice(0, 8)}</span>
                </NarrowLink>
              ),
          },
          {
            key: 'detail',
            header: 'Detail',
            cell: (entry) => (
              <span className="text-xs text-ink-600 dark:text-ink-400">
                {entry.detail ?? '-'}
                {entry.remoteAddress ? (
                  <span className="block font-mono">{entry.remoteAddress}</span>
                ) : null}
              </span>
            ),
          },
          {
            key: 'outcome',
            header: 'Outcome',
            align: 'right',
            cell: (entry) => (
              <Badge tone={outcomeTone(entry.outcome)} dot={entry.outcome !== 'SUCCEEDED'}>
                {outcomeLabel(entry.outcome)}
              </Badge>
            ),
          },
        ]}
      />
    </Card>
  )
}

/**
 * A cell that narrows the list to itself, or renders plainly when there is nothing to
 * narrow by - a system actor has no account id, and an action against no object has no
 * target to filter on.
 */
function NarrowLink({
  filter,
  overrides,
  children,
}: {
  filter: AuditFilter
  overrides: FilterOverrides | null
  children: ReactNode
}) {
  if (overrides === null) {
    return <span className="text-ink-500 dark:text-ink-400">{children}</span>
  }
  return (
    <Link
      href={auditUrl(filter, overrides)}
      className="text-accent-600 hover:underline dark:text-accent-400"
    >
      {children}
    </Link>
  )
}
