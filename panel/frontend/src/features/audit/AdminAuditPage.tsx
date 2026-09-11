import {Head, usePage} from '@inertiajs/react'
import {useState} from 'react'

import {Button, Card, Drawer, PageHeader, Pagination, useIsWide} from '@/shell'

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
      <Head title="Audit log" />

      <PageHeader
        title="Audit log"
        description="Who did what, to what, and how it ended - including everything that was
          refused. A trail that recorded only what succeeded could not reconstruct an incident."
        actions={
          wide ? null : (
            <Button variant="secondary" block onClick={() => setFiltering(true)}>
              {active === 0 ? 'Filter' : `Filter (${active})`}
            </Button>
          )
        }
      />

      {wide ? (
        <Card title="Filter" description="Everything here goes into the URL, so it can be shared.">
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
          title="Filter the trail"
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
          {refusals === 1
            ? 'One entry on this page is a refusal.'
            : `${refusals} entries on this page are refusals.`}{' '}
          Somebody asked for something they were not allowed to have. That is usually a
          permission that needs granting, and occasionally it is not.
        </p>
      ) : null}

      <AuditEntryList entries={page.entries} filter={filter} />

      <Pagination
        total={page.total}
        offset={page.offset}
        pageSize={page.pageSize}
        unit="entries"
        hrefFor={(offset) => auditUrl(filter, {offset})}
      />
    </div>
  )
}
