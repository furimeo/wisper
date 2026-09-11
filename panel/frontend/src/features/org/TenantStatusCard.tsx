import {router} from '@inertiajs/react'
import {useState} from 'react'

import {Button, Card, RelativeTime, Textarea, askConfirmation, useFormFields} from '@/shell'

import type {Organization} from './orgTypes'

/**
 * `POST /admin/organizations/{id}/suspend` and `/resume` - stopping a tenant creating
 * anything new, and letting it start again.
 *
 * The reason is required by the server and is shown to the customer, which is the whole
 * point of it: an organization that has silently stopped accepting new services looks like
 * a broken panel, and the sentence written here is the one that appears on their overview
 * instead.
 *
 * Suspension does not stop anything that is running. Their sites stay up, their containers
 * stay up, their data stays where it is - `QuotaExceeded.suspended` is raised on creation
 * and nowhere else. That is stated on the card because an operator reaching for this button
 * usually needs to know exactly how far it goes before they press it.
 */
export function TenantStatusCard({tenant}: {tenant: Organization}) {
  const form = useFormFields({reason: tenant.suspensionReason ?? ''})
  const [resuming, setResuming] = useState(false)

  async function suspend() {
    const confirmed = await askConfirmation({
      title: `Suspend ${tenant.name}?`,
      body:
        'They cannot create projects, services, domains, databases, backups or invitations ' +
        'until this is lifted. Everything already running keeps running, and the reason you ' +
        'wrote is shown to them.',
      confirmLabel: 'Suspend',
      tone: 'danger',
    })
    if (confirmed) {
      form.submit(`/admin/organizations/${tenant.id}/suspend`)
    }
  }

  if (tenant.suspended) {
    return (
      <Card
        title="Suspended"
        description={
          tenant.suspendedAt ? (
            <>
              Suspended <RelativeTime at={tenant.suspendedAt} />.
            </>
          ) : (
            'Suspended.'
          )
        }
        footer={
          <Button
            block
            className="sm:w-auto"
            loading={resuming}
            onClick={() => {
              setResuming(true)
              router.post(
                `/admin/organizations/${tenant.id}/resume`,
                {},
                {preserveScroll: true, onFinish: () => setResuming(false)},
              )
            }}
          >
            Resume this organization
          </Button>
        }
      >
        <p className="text-sm leading-relaxed text-ink-800 dark:text-ink-200">
          {tenant.suspensionReason || 'No reason was recorded on the record.'}
        </p>
        <p className="mt-2 text-sm text-ink-600 dark:text-ink-400">
          The customer sees this sentence on their organization screen. Resuming clears it and
          lets them create things again straight away.
        </p>
      </Card>
    )
  }

  return (
    <Card
      title="Suspend"
      description="Stops this tenant creating anything new. Nothing already running is touched."
      footer={
        <Button
          variant="danger"
          block
          className="sm:w-auto"
          loading={form.processing}
          onClick={() => void suspend()}
        >
          Suspend this organization
        </Button>
      }
    >
      <form
        className="flex flex-col gap-3"
        onSubmit={(event) => {
          event.preventDefault()
          void suspend()
        }}
      >
        <Textarea
          {...form.bind('reason')}
          label="Reason"
          required
          maxLength={500}
          hint="Required, and shown to the customer. Say what would lift it - an unpaid invoice, an abuse report, a migration in progress."
        />
        <button type="submit" className="hidden" aria-hidden="true" tabIndex={-1} />
      </form>
    </Card>
  )
}
