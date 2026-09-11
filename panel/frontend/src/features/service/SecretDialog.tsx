import {router} from '@inertiajs/react'

import {Button, Checkbox, Input, Modal, Textarea, askConfirmation, useFormFields} from '@/shell'

import type {SecretView, ServiceView} from './serviceTypes'

/**
 * Setting a secret, and rotating or removing one that exists.
 *
 * The difference from `EnvVarDialog` is one field and it is the important one: there is no
 * current value to show. `ListSecrets` does not select the value column, so the panel has
 * nothing to put in the box - and rather than rendering an empty input that looks like the
 * secret is empty, the form says outright that a save replaces it and a blank save is
 * refused.
 *
 * Mounted only while open and keyed on the secret it points at, so `useFormFields` seeds
 * the fields once. Re-seeding from inside would be a second opinion about which secret
 * this is, and the two disagree the moment a save fails.
 *
 * `SetSecret` upserts on the name, so the name is fixed once the secret exists: typing a
 * new one would leave the old secret in place and quietly create a second. Renaming is
 * delete-then-add, which is what it actually is.
 */
export function SecretDialog({
  service,
  secret,
  onClose,
}: {
  service: ServiceView
  /** The secret being rotated, or null when this is a new one. */
  secret: SecretView | null
  onClose: () => void
}) {
  const editing = secret !== null
  const form = useFormFields({
    name: secret?.name ?? '',
    value: '',
    buildTime: secret?.buildTime ?? service.site,
  })

  function save() {
    form.submit(`/services/${service.id}/environment/secrets`, {onSuccess: () => onClose()})
  }

  async function remove() {
    if (!secret) {
      return
    }
    const confirmed = await askConfirmation({
      title: `Remove ${secret.name}?`,
      body:
        'The container stops seeing it the next time it starts, and the panel cannot give ' +
        'the value back afterwards.',
      confirmLabel: 'Remove it',
      tone: 'danger',
    })
    if (confirmed) {
      router.post(
        `/services/${service.id}/environment/secrets/delete`,
        {name: secret.name},
        {preserveScroll: true, onSuccess: () => onClose()},
      )
    }
  }

  return (
    <Modal
      open
      onClose={onClose}
      title={editing ? `Rotate ${secret.name}` : 'New secret'}
      description={
        editing
          ? 'The panel cannot show you the current value. Saving replaces it.'
          : 'Encrypted before it is stored. Nothing in the panel can show it back to you.'
      }
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={form.processing}>
            Cancel
          </Button>
          <Button loading={form.processing} onClick={save}>
            {editing ? 'Replace value' : 'Add secret'}
          </Button>
        </>
      }
    >
      <form
        className="flex flex-col gap-4"
        onSubmit={(event) => {
          event.preventDefault()
          save()
        }}
      >
        <Input
          {...form.bind('name')}
          label="Name"
          required
          disabled={editing}
          maxLength={256}
          autoComplete="off"
          autoCapitalize="characters"
          spellCheck={false}
          className="font-mono"
          placeholder="STRIPE_SECRET_KEY"
          hint={
            editing
              ? 'A name cannot be changed. Remove this one and add the new name.'
              : 'Letters, digits and underscores, not starting with a digit. WISPER_* is reserved, and a plain variable of the same name would clash.'
          }
        />

        <Textarea
          {...form.bind('value')}
          label={editing ? 'New value' : 'Value'}
          required
          maxLength={32768}
          spellCheck={false}
          autoComplete="off"
          className="font-mono"
          hint="Up to 32 KB. Anything larger belongs in a file on a volume, not in the environment."
        />

        <Checkbox
          {...form.check('buildTime')}
          disabled={service.site}
          label="Also available while it builds"
          hint={
            service.site
              ? 'A static site has no running process, so a secret is only ever read during the build.'
              : 'On for anything the build needs - a private registry token, a source-map upload key.'
          }
        />

        {editing ? (
          <Button variant="danger" block onClick={() => void remove()} disabled={form.processing}>
            Remove this secret
          </Button>
        ) : null}

        <button type="submit" className="hidden" aria-hidden="true" tabIndex={-1} />
      </form>
    </Modal>
  )
}
