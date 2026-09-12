import {Head, usePage} from '@inertiajs/react'
import {useState} from 'react'

import {Button, Card, Drawer, PageHeader, Pagination, useIsWide} from '@/shell'
import {t} from '@/i18n'

import {AuditEntryList} from './AuditEntryList'
import {AuditFilterForm} from './AuditFilterForm'
import type {AuditFilter, AuditLogPage, AuditOutcome} from './auditTypes'
import {activeFilterCount, auditUrl} from './auditQuery'

/**
 * `GET /admin/audit` - every state-changing action on the platform, every tenant.
 *
 * Read-only, and there is nothing on this page that could change an entry. Nothing in the
 * `audit` package edits or deletes one either, so there is no button that could exist.
 *
 * The filter is in the URL rather than in component state, which is what lets an operator
 * paste "what this account did to this node between two and four" into a ticket. The pager
 * carries the same filter for the same reason - a pager that quietly drops it is how an
 * audit screen becomes useless at the moment somebody is relying on it.
 *
 * On a phone the filter lives in a drawer behind a button that counts what is active,
 * because ten controls above a list is a list nobody scrolls to.
 */
type AdminAuditProps = {
  page: AuditLogPage
  /** The sanitised query, echoed back. */
  filter: AuditFilter
  /** `AuditAction.byDomain()`: the vocabulary, grouped, in the contract's order. */
  actions: Record<string, string[]>
  targetKinds: string[]
  outcomes: AuditOutcome[]
}

export default function AdminAuditPage() {
  const {page, filter, actions, targetKinds, outcomes} = usePage<AdminAuditProps>().props
  const wide = useIsWide()
  const [filtering, setFiltering] = useState(false)

  const active = activeFilterCount(filter)
  const refusals = page.entries.filter((entry) => entry.refusal).length

  return (
    <div className="flex flex-col gap-4">
      <Head title={t('audit.title')} />

      <PageHeader
        title={t('audit.title')}
        description={t('audit.description')}
        actions={
          wide ? null : (
            <Button variant="secondary" block onClick={() => setFiltering(true)}>
              {active === 0 ? t('audit.filter') : t('audit.filterWithCount', {count: active})}
            </Button>
          )
        }
      />

      {wide ? (
        <Card title={t('audit.filterCard.title')} description={t('audit.filterCard.description')}>
          <AuditFilterForm
            filter={filter}
            actions={actions}
            targetKinds={targetKinds}
            outcomes={outcomes}
          />
        </Card>
      ) : (
        <Drawer
          open={filtering}
          onClose={() => setFiltering(false)}
          side="right"
          title={t('audit.drawer.title')}
        >
          <AuditFilterForm
            filter={filter}
            actions={actions}
            targetKinds={targetKinds}
            outcomes={outcomes}
            onDone={() => setFiltering(false)}
          />
        </Drawer>
      )}

      {refusals > 0 ? (
        <p className="rounded-xl border border-degraded/50 bg-degraded/10 px-4 py-3 text-sm leading-relaxed">
          {t('audit.refusals.notice', {count: refusals})}{' '}
          {t('audit.refusals.body')}
        </p>
      ) : null}

      <AuditEntryList entries={page.entries} filter={filter} />

      <Pagination
        total={page.total}
        offset={page.offset}
        pageSize={page.pageSize}
        unit={t('audit.unit')}
        hrefFor={(offset) => auditUrl(filter, {offset})}
      />
    </div>
  )
}
