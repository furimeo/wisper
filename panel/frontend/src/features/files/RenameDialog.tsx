import {useEffect} from 'react'

import {Button, Checkbox, Input, Modal, useFormFields} from '@/shell'

import {filesBase} from './fileRequests'
import type {FileEntryView} from './fileTypes'

/**
 * Rename, and move, which on a filesystem are the same operation.
 *
 * `POST /services/{id}/files/rename` takes `from` and `to` as two paths, so typing a new
 * name renames and typing `logs/old.txt` moves. One dialog rather than two because a
 * second one would have to explain the difference, and there is not one - the panel
 * shows the whole destination path and lets the customer edit any part of it.
 *
 * `overwrite` is off and stays off unless it is asked for. A move that silently replaces
 * something is the one file-manager mistake with no undo.
 */
export function RenameDialog({
  entry,
  onClose,
  serviceId,
  rootId,
}: {
  /** The entry being moved, or null when the dialog is shut. */
  entry: FileEntryView | null
  onClose: () => void
  serviceId: string
  rootId: string
}) {
  const form = useFormFields({
    rootId,
    from: entry?.path ?? '',
    to: entry?.path ?? '',
    overwrite: false,
  })
  const {patch} = form

  // The dialog is mounted once and given a different entry each time it opens, so the
  // fields have to follow. Keying the whole dialog on the path instead would throw away
  // the "processing" state mid-submit.
  useEffect(() => {
    if (entry) {
      patch({from: entry.path, to: entry.path, overwrite: false})
    }
  }, [entry, patch])

  const submit = () => {
    const target = form.data.to.trim()
    if (target === '') {
      form.setError('to', 'Give it somewhere to go.')
      return
    }
    if (target === form.data.from) {
      form.setError('to', 'That is where it already is.')
      return
    }
    form.submit(`${filesBase(serviceId)}/rename`, {onSuccess: onClose})
  }

  return (
    <Modal
      open={entry !== null}
      onClose={onClose}
      title="Rename or move"
      description={entry ? `Currently ${entry.path}` : undefined}
      size="sm"
      footer={
        <>
          <Button variant="secondary" onClick={onClose} block>
            Cancel
          </Button>
          <Button onClick={submit} loading={form.processing} block>
            Move
          </Button>
        </>
      }
    >
      <form
        className="flex flex-col gap-4"
        onSubmit={(event) => {
          event.preventDefault()
          submit()
        }}
      >
        <Input
          {...form.bind('to')}
          label="New path"
          autoFocus
          autoCapitalize="off"
          autoCorrect="off"
          spellCheck={false}
          hint="Relative to the top of this tree. Change the last part to rename it, or the whole path to move it."
        />
        <Checkbox
          {...form.check('overwrite')}
          label="Replace anything already there"
          hint="Off by default. With this on, a file at the destination is overwritten and not recoverable."
        />
      </form>
    </Modal>
  )
}
