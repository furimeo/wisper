import {router} from '@inertiajs/react'
import {useState} from 'react'

import {Button, Input, Select} from '@/shell'

import type {AuditFilter, AuditOutcome} from './auditTypes'
import {AUDIT_PATH, auditUrl} from './auditQuery'
import {domainLabel, fromLocalInput, outcomeLabel, toLocalInput, verbOf} from './auditVocabulary'

/**
 * Narrowing the trail.
 *
 * A GET, not a POST: the filter belongs in the URL so an operator can paste "everything
 * this account did to node 3 between two and four" into a ticket and have it still mean
 * that tomorrow. It is also why the form seeds itself from the echoed filter rather than
 * from component state that a reload would lose.
 *
 * The action list is the `actions` prop, grouped by the word before the dot, exactly as
 * `AuditAction.byDomain()` produced it. Never a hand-written copy: an action added to the
 * server's vocabulary appears in this dropdown the same day, and one that does not exist
 * cannot be chosen.
 *
 * The three id fields take a UUID because that is what the trail stores and what the query
 * accepts. Nobody types one - they arrive by pressing "only this actor" on a row, or by
 * pasting from the page of the thing being investigated - so they are last, and the
 * dropdowns that people do use are first.
 */
export function AuditFilterForm({
  filter,
  actions,
  targetKinds,
  outcomes,
  onDone,
}: {
  filter: AuditFilter
  /** Domain to actions, in the contract's order. */
  actions: Record<string, string[]>
  targetKinds: string[]
  outcomes: AuditOutcome[]
  /** Called after a submit, so a drawer can close itself. */
  onDone?: () => void
}) {
  const [draft, setDraft] = useState(() => ({
    action: filter.action ?? '',
    targetKind: filter.targetKind ?? '',
    outcome: filter.outcome ?? '',
    from: toLocalInput(filter.from),
    to: toLocalInput(filter.to),
    organizationId: filter.organizationId ?? '',
    accountId: filter.accountId ?? '',
    nodeId: filter.nodeId ?? '',
    targetId: filter.targetId ?? '',
    limit: String(filter.limit),
  }))

  function set(name: keyof typeof draft, value: string) {
    setDraft((current) => ({...current, [name]: value}))
  }

  function apply() {
    router.get(
      auditUrl(filter, {
        action: draft.action,
        targetKind: draft.targetKind,
        outcome: draft.outcome,
        from: fromLocalInput(draft.from),
        to: fromLocalInput(draft.to),
        organizationId: draft.organizationId,
        accountId: draft.accountId,
        nodeId: draft.nodeId,
        targetId: draft.targetId,
        limit: draft.limit,
        // A narrowed list starts at its own beginning; keeping page four of the old
        // filter is how an operator concludes that nothing matched.
        offset: null,
      }),
      {},
      {preserveScroll: true, preserveState: false},
    )
    onDone?.()
  }

  return (
    <form
      className="flex flex-col gap-3"
      onSubmit={(event) => {
        event.preventDefault()
        apply()
      }}
    >
      <Select
        label="Action"
        value={draft.action}
        onChange={(event) => set('action', event.target.value)}
      >
        <option value="">Every action</option>
        {Object.entries(actions).map(([domain, group]) => (
          <optgroup key={domain} label={domainLabel(domain)}>
            {group.map((action) => (
              <option key={action} value={action}>
                {verbOf(action)}
              </option>
            ))}
          </optgroup>
        ))}
      </Select>

      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <Select
          label="Kind of object"
          value={draft.targetKind}
          onChange={(event) => set('targetKind', event.target.value)}
          options={[
            {value: '', label: 'Anything'},
            ...targetKinds.map((kind) => ({value: kind, label: domainLabel(kind)})),
          ]}
        />

        <Select
          label="Outcome"
          value={draft.outcome}
          onChange={(event) => set('outcome', event.target.value)}
          options={[
            {value: '', label: 'However it ended'},
            ...outcomes.map((outcome) => ({value: outcome, label: outcomeLabel(outcome)})),
          ]}
          hint="Refused is the one an incident is usually reconstructed from."
        />
      </div>

      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <Input
          label="From"
          type="datetime-local"
          value={draft.from}
          onChange={(event) => set('from', event.target.value)}
        />
        <Input
          label="To"
          type="datetime-local"
          value={draft.to}
          onChange={(event) => set('to', event.target.value)}
          hint="Exclusive, so consecutive pages of one day do not overlap."
        />
      </div>

      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <Input
          label="Organization id"
          value={draft.organizationId}
          onChange={(event) => set('organizationId', event.target.value)}
          autoComplete="off"
          spellCheck={false}
          className="font-mono text-xs"
          placeholder="paste a UUID"
        />
        <Input
          label="Account id"
          value={draft.accountId}
          onChange={(event) => set('accountId', event.target.value)}
          autoComplete="off"
          spellCheck={false}
          className="font-mono text-xs"
          placeholder="paste a UUID"
        />
        <Input
          label="Node id"
          value={draft.nodeId}
          onChange={(event) => set('nodeId', event.target.value)}
          autoComplete="off"
          spellCheck={false}
          className="font-mono text-xs"
          placeholder="paste a UUID"
        />
        <Input
          label="Object id"
          value={draft.targetId}
          onChange={(event) => set('targetId', event.target.value)}
          autoComplete="off"
          spellCheck={false}
          className="font-mono text-xs"
          placeholder="paste a UUID"
          hint="Only meaningful together with a kind."
        />
      </div>

      <Select
        label="Rows per page"
        value={draft.limit}
        onChange={(event) => set('limit', event.target.value)}
        options={[
          {value: '25', label: '25'},
          {value: '50', label: '50'},
          {value: '100', label: '100'},
          {value: '200', label: '200'},
        ]}
      />

      <div className="flex flex-col gap-2 sm:flex-row-reverse">
        <Button type="submit" block className="sm:w-auto">
          Apply
        </Button>
        <Button
          type="button"
          variant="ghost"
          block
          className="sm:w-auto"
          onClick={() => {
            router.get(AUDIT_PATH, {}, {preserveScroll: true, preserveState: false})
            onDone?.()
          }}
        >
          Clear everything
        </Button>
      </div>
    </form>
  )
}
