import {router} from '@inertiajs/react'
import {useState} from 'react'

import {Button, Card, askConfirmation} from '@/shell'

import type {ServiceView} from './serviceTypes'

/**
 * The one button on the settings screen that cannot be taken back.
 *
 * The address has to be typed before it does anything, and the typing is not theatre:
 * `ServiceSettingsController` checks the same string and answers with a message under the
 * field when it does not match. A confirmation enforced only in a browser is a
 * confirmation that is not enforced.
 *
 * The sentence says what is actually removed, because "delete" here means something
 * narrower than it sounds. The row goes, the node stops running the workload, and the
 * bytes under `/var/lib/wisper/volumes/` stay until an operator purges them - a panel
 * that erases a customer's disk because a row disappeared has one bad afternoon and no
 * customers.
 */
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
      title: `Delete ${service.name}?`,
      body:
        'The service is removed from the node and from this project. Volume data is kept on ' +
        'the node until an operator purges it; everything else about this cannot be undone.',
      confirmLabel: 'Delete service',
      tone: 'danger',
      requireText: service.slug,
      requireTextLabel: `Type ${service.slug} to confirm`,
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
      title="Delete"
      description={
        service.site
          ? 'Removes the site, its releases and its domains. The published files stay on the node until they are purged.'
          : 'Removes the container, its environment, its volumes mounts and its scheduled commands. Volume data stays on the node until it is purged.'
      }
    >
      <Button
        variant="danger"
        block
        className="sm:w-auto"
        disabled={disabled || working}
        onClick={() => void confirmThenDelete()}
      >
        Delete {service.name}
      </Button>

      {disabled ? (
        <p className="mt-3 text-sm text-ink-500 dark:text-ink-400">
          You have read access to this organization, so this is off.
        </p>
      ) : null}
    </Card>
  )
}
