import {router} from '@inertiajs/react'

import {Button, Checkbox, Input, Modal, Textarea, askConfirmation, useFormFields} from '@/shell'

import type {EnvVar, ServiceView} from './serviceTypes'

/**
 * Adding a variable, and everything that can be done to one that exists.
 *
 * Mounted only while it is open, and keyed on the variable it is pointed at, so the
 * fields are seeded by `useFormFields` rather than by an effect that copies props into
 * state. The list is what decides which variable this is; re-seeding from inside would be
 * a second opinion about the same thing, and the two disagree the moment a save fails.
 * Nothing remounts it during a save - the key does not change - so the messages the
 * server sent stay under the boxes that produced them.
 *
 * `SetEnvVar` upserts on the name, so the name is fixed once the variable exists: typing
 * a new one would leave the old variable in place and quietly create a second. The box is
 * therefore read-only when editing, and renaming is delete-then-add, which is what it
 * actually is.
 *
 * A textarea for the value, not an input. Half of what goes in here is a connection
 * string or a PEM block, and a single-line box shows forty characters of a four-hundred
 * character value with no way to see the rest on a phone.
 *
 * The build-time switch is forced on for a static site and says why. A site has no
 * running process, so `SetEnvVar` refuses a variable that is not available during the
 * build - offering a state the server always rejects is a form that teaches people to
 * distrust it.
 */
export function EnvVarDialog({
  service,
  variable,
  onClose,
}: {
  service: ServiceView
  /** The variable being changed, or null when this is a new one. */
  variable: EnvVar | null
  onClose: () => void
}) {
  const editing = variable !== null
  const form = useFormFields({
    name: variable?.name ?? '',
    value: variable?.value ?? '',
    buildTime: variable?.buildTime ?? service.site,
  })

  function save() {
    form.submit(`/services/${service.id}/environment/variables`, {onSuccess: () => onClose()})
  }

  async function remove() {
    if (!variable) {
      return
    }
    const confirmed = await askConfirmation({
      title: `Remove ${variable.name}?`,
      body: 'The container stops seeing it the next time it starts.',
      confirmLabel: 'Remove it',
      tone: 'danger',
    })
    if (confirmed) {
      router.post(
        `/services/${service.id}/environment/variables/delete`,
        {name: variable.name},
        {preserveScroll: true, onSuccess: () => onClose()},
      )
    }
  }

  return (
    <Modal
      open
      onClose={onClose}
      title={editing ? variable.name : 'New variable'}
      description={
        editing
          ? 'The new value reaches the container the next time it starts.'
          : 'A plain variable: its value is readable on this screen and by anyone who can open it.'
      }
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={form.processing}>
            Cancel
          </Button>
          <Button loading={form.processing} onClick={save}>
            {editing ? 'Save' : 'Add variable'}
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
          placeholder="DATABASE_URL"
          hint={
            editing
              ? 'A name cannot be changed. Remove this one and add the new name.'
              : 'Letters, digits and underscores, not starting with a digit. WISPER_* is reserved.'
          }
        />

        <Textarea
          {...form.bind('value')}
          label="Value"
          maxLength={32768}
          spellCheck={false}
          className="font-mono"
          hint="Anything but a null byte. Put anything larger than 32 KB on a volume instead."
        />

        <Checkbox
          {...form.check('buildTime')}
          disabled={service.site}
          label="Also available while it builds"
          hint={
            service.site
              ? 'A static site has no running process, so a variable is only ever read during the build.'
              : 'On for anything the build needs - an npm token, a public API base URL.'
          }
        />

        {editing ? (
          <Button variant="danger" block onClick={() => void remove()} disabled={form.processing}>
            Remove this variable
          </Button>
        ) : null}

        <button type="submit" className="hidden" aria-hidden="true" tabIndex={-1} />
      </form>
    </Modal>
  )
}
