import {Link} from '@inertiajs/react'
import type {ReactNode} from 'react'

import {Badge, Card, DataList, EmptyState, Icon, RelativeTime} from '@/shell'
import {t} from '@/i18n'

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
        label={t('audit.list.label')}
        empty={
          <EmptyState
            icon={<Icon name="audit" />}
            title={t('audit.list.empty.title')}
            description={t('audit.list.empty.description')}
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
            header: t('audit.list.column.when'),
            cell: (entry) => <RelativeTime at={entry.occurredAt} className="text-xs" />,
          },
          {
            key: 'actor',
            header: t('audit.list.column.actor'),
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
            header: t('audit.list.column.action'),
            cell: (entry) => (
              <NarrowLink filter={filter} overrides={{action: entry.action, offset: null}}>
                {verbOf(entry.action)}
              </NarrowLink>
            ),
          },
          {
            key: 'target',
            header: t('audit.list.column.target'),
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
            header: t('audit.list.column.tenant'),
            cell: (entry) =>
              entry.organizationId === null ? (
                <span className="text-ink-400">{t('audit.list.tenant.platform')}</span>
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
            header: t('audit.list.column.detail'),
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
            header: t('audit.list.column.outcome'),
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
