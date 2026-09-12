import {router} from '@inertiajs/react'
import {useState} from 'react'

import {t} from '@/i18n'
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
      title: t('org.tenantStatus.confirmTitle', {name: tenant.name}),
      body: t('org.tenantStatus.confirmBody'),
      confirmLabel: t('org.tenantStatus.confirmBtn'),
      tone: 'danger',
    })
    if (confirmed) {
      form.submit(`/admin/organizations/${tenant.id}/suspend`)
    }
  }

  if (tenant.suspended) {
    return (
      <Card
        title={t('org.tenantStatus.suspendedTitle')}
        description={
          tenant.suspendedAt ? (
            <>
              {t('org.tenantStatus.suspendedAt', {time: ''}).split('{time}')[0]}
              <RelativeTime at={tenant.suspendedAt} />
              {t('org.tenantStatus.suspendedAt', {time: ''}).split('{time}')[1]}
            </>
          ) : (
            t('org.tenantStatus.suspended')
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
            {t('org.tenantStatus.resumeBtn')}
          </Button>
        }
      >
        <p className="text-sm leading-relaxed text-ink-800 dark:text-ink-200">
          {tenant.suspensionReason || t('org.tenantStatus.fallbackReason')}
        </p>
        <p className="mt-2 text-sm text-ink-600 dark:text-ink-400">
          {t('org.tenantStatus.customerViewNotice')}
        </p>
      </Card>
    )
  }

  return (
    <Card
      title={t('org.tenantStatus.suspendTitle')}
      description={t('org.tenantStatus.suspendDesc')}
      footer={
        <Button
          variant="danger"
          block
          className="sm:w-auto"
          loading={form.processing}
          onClick={() => void suspend()}
        >
          {t('org.tenantStatus.suspendBtn')}
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
          label={t('org.tenantStatus.reason')}
          required
          maxLength={500}
          hint={t('org.tenantStatus.reasonHint')}
        />
        <button type="submit" className="hidden" aria-hidden="true" tabIndex={-1} />
      </form>
    </Card>
  )
}
