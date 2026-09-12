import {router} from '@inertiajs/react'
import {useState} from 'react'

import {t} from '@/i18n'
import {Button, Card, askConfirmation} from '@/shell'

import type {ServiceView} from './serviceTypes'

export function DeleteServiceCard({
  service,
  disabled,
}: {
  service: ServiceView
  disabled: boolean
}) {
  const [working, setWorking] = useState(false)

  async function confirmThenDelete() {
    const confirmed = await askConfirmation({
      title: t('service.delete.confirm_title', {name: service.name}),
      body: t('service.delete.confirm_body'),
      confirmLabel: t('service.delete.confirm_label'),
      tone: 'danger',
      requireText: service.slug,
      requireTextLabel: t('service.delete.require_text_label', {slug: service.slug}),
    })
    if (!confirmed) {
      return
    }
    setWorking(true)
    router.post(
      `/services/${service.id}/delete`,
      {confirmation: service.slug},
      {preserveScroll: true, onFinish: () => setWorking(false)},
    )
  }

  return (
    <Card
      title={t('service.delete.title')}
      description={
        service.site
          ? t('service.delete.description_site')
          : t('service.delete.description_app')
      }
    >
      <Button
        variant="danger"
        block
        className="sm:w-auto"
        disabled={disabled || working}
        onClick={() => void confirmThenDelete()}
      >
        {t('service.delete.button', {name: service.name})}
      </Button>

      {disabled ? (
        <p className="mt-3 text-sm text-ink-500 dark:text-ink-400">
          {t('service.delete.read_only_notice')}
        </p>
      ) : null}
    </Card>
  )
}
