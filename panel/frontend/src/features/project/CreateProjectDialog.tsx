import {Button, Input, Modal, Select, Textarea, useFormFields, useOrganizations} from '@/shell'

import {deriveSlug, slugRule} from './deriveSlug'

/**
 * `POST /projects` - opening a project inside one organization.
 *
 * A sheet rather than a screen of its own: there is no `GET /projects/new` in
 * panel-http.md, and there should not be. Four fields, two of which are optional, is not
 * a page - it is the thing you do on the way to the page you wanted.
 *
 * The organization comes from the shared `organizations` prop, filtered to memberships
 * that have been accepted. An invitation is listed in the switcher and is not somewhere a
 * project can be put yet; a suspended tenant is offered and refused by the server, so it
 * is left out here rather than presented as a choice that always fails.
 */
type CreateProjectDialogProps = {
  open: boolean
  onClose: () => void
  /** Preselected when the dialog is opened from inside one organization's screens. */
  organizationId?: string | null
}

export function CreateProjectDialog({open, onClose, organizationId}: CreateProjectDialogProps) {
  const organizations = useOrganizations()
  const available = organizations.filter(
    (organization) => organization.accepted && !organization.suspended,
  )
  const preselected =
    organizationId && available.some((organization) => organization.id === organizationId)
      ? organizationId
      : (available[0]?.id ?? '')

  const form = useFormFields({
    organizationId: preselected,
    name: '',
    slug: '',
    description: '',
  })

  const derived = deriveSlug(form.data.name)

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="New project"
      description="A project groups the services that ship together and share an address book."
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={form.processing}>
            Cancel
          </Button>
          <Button
            loading={form.processing}
            disabled={available.length === 0}
            onClick={() =>
              form.submit('/projects', {
                onSuccess: () => {
                  form.reset()
                  onClose()
                },
              })
            }
          >
            Create project
          </Button>
        </>
      }
    >
      {available.length === 0 ? (
        <p className="text-sm text-ink-600 dark:text-ink-400">
          You are not a member of an organization yet. Accept an invitation, or open one from
          the Organizations screen, and a project can go in it.
        </p>
      ) : (
        <form
          className="flex flex-col gap-4"
          onSubmit={(event) => {
            event.preventDefault()
            form.submit('/projects', {
              onSuccess: () => {
                form.reset()
                onClose()
              },
            })
          }}
        >
          <Select
            {...form.bind('organizationId')}
            label="Organization"
            required
            options={available.map((organization) => ({
              value: organization.id,
              label: organization.name,
            }))}
          />

          <Input
            {...form.bind('name')}
            label="Name"
            required
            autoFocus
            maxLength={120}
            placeholder="Storefront"
            autoComplete="off"
          />

          <Input
            {...form.bind('slug')}
            label="Address"
            maxLength={63}
            placeholder={derived || 'storefront'}
            autoComplete="off"
            inputMode="url"
            hint={
              derived
                ? `Leave it empty and the address becomes "${derived}".`
                : slugRule(2)
            }
          />

          <Textarea
            {...form.bind('description')}
            label="Description"
            maxLength={500}
            hint="Optional. What this project is for, for whoever joins next."
          />

          {/* Submits on Enter from any field without a second visible button. */}
          <button type="submit" className="hidden" aria-hidden="true" tabIndex={-1} />
        </form>
      )}
    </Modal>
  )
}
