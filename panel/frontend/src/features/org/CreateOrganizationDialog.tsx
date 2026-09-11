import {Button, Input, Modal, Select, useFormFields} from '@/shell'

import {deriveSlug, slugRule} from '@/features/project/deriveSlug'

import type {Plan} from './orgTypes'

/**
 * `POST /orgs` - opening a new tenant.
 *
 * A sheet rather than a screen: there is no `GET /orgs/new` in panel-http.md and there
 * should not be. Three fields, one of them optional, is what you do on the way to the page
 * you wanted.
 *
 * The address is required here, unlike a project's. `CreateOrganization` does not derive
 * one from the name - a tenant's slug is the thing every URL underneath it is built from,
 * and guessing it for somebody is a decision they cannot take back. The derived form is
 * offered as a placeholder and a one-tap fill instead.
 *
 * The plan list is whatever the server said is selectable. Leaving it unset means the
 * platform default, which is what a customer signing themselves up gets - and when there
 * is no default plan at all, `CreateOrganization` refuses and the message says so rather
 * than opening a tenant with every limit at zero.
 */
export function CreateOrganizationDialog({
  open,
  onClose,
  plans,
}: {
  open: boolean
  onClose: () => void
  /** `PlanRepository.findSelectable()` - the tiers a new tenant may start on. */
  plans: Plan[]
}) {
  const form = useFormFields({name: '', slug: '', planId: ''})
  const derived = deriveSlug(form.data.name)

  function create() {
    form.submit('/orgs', {
      onSuccess: () => {
        form.reset()
        onClose()
      },
    })
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="New organization"
      description="A tenant of its own: separate members, separate plan, separate limits."
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={form.processing}>
            Cancel
          </Button>
          <Button loading={form.processing} onClick={create}>
            Create organization
          </Button>
        </>
      }
    >
      <form
        className="flex flex-col gap-4"
        onSubmit={(event) => {
          event.preventDefault()
          create()
        }}
      >
        <Input
          {...form.bind('name')}
          label="Name"
          required
          autoFocus
          maxLength={120}
          autoComplete="off"
          placeholder="Northwind"
        />

        <Input
          {...form.bind('slug')}
          label="Address"
          required
          maxLength={63}
          autoComplete="off"
          inputMode="url"
          spellCheck={false}
          placeholder={derived || 'northwind'}
          hint={slugRule(2)}
          suffix={
            derived && form.data.slug !== derived ? (
              <button
                type="button"
                onClick={() => form.set('slug', derived)}
                className="rounded px-1 text-xs font-medium text-accent-600 dark:text-accent-400"
              >
                Use {derived}
              </button>
            ) : undefined
          }
        />

        <Select
          {...form.bind('planId')}
          label="Plan"
          options={[
            {value: '', label: 'The platform default'},
            ...plans.map((plan) => ({value: plan.id, label: planOption(plan)})),
          ]}
          hint={
            plans.length === 0
              ? 'No plan is selectable right now. The default one is used, and an operator can move you later.'
              : 'An operator can move the organization to another plan afterwards.'
          }
        />

        <button type="submit" className="hidden" aria-hidden="true" tabIndex={-1} />
      </form>
    </Modal>
  )
}

/** "Starter (default)" - the marker matters, because it is what an empty choice means. */
function planOption(plan: Plan): string {
  return plan.isDefault ? `${plan.name} (default)` : plan.name
}
