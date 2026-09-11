import {useEffect, useId, useState} from 'react'

import {Button, Checkbox, Input, Modal} from '@/shell'

import {renamePath} from './movePaths'
import type {FileEntryView} from './fileTypes'

/**
 * Giving one entry a different name, in the folder it is already in.
 *
 * The field holds the name and not the path, which is the whole difference between this
 * and `MoveDialog`. F2 on a file called `nginx.conf` should present `nginx.conf` with the
 * stem selected and the suffix left alone - not `sites-enabled/nginx.conf`, where the
 * customer has to find the last slash before they can type. Moving somewhere else is a
 * different intention and has its own dialog; the underlying request is the same one,
 * because on a filesystem renaming and moving are the same operation.
 *
 * `overwrite` is off and stays off unless it is asked for. A rename that silently replaces
 * something is the one file-manager mistake with no undo.
 */
export function RenameDialog({
  entry,
  onClose,
  serviceId,
  rootId,
}: {
  /** The entry being renamed, or null when the dialog is shut. */
  entry: FileEntryView | null
  onClose: () => void
  serviceId: string
  rootId: string
}) {
  const fieldId = useId()
  const [name, setName] = useState('')
  const [overwrite, setOverwrite] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [processing, setProcessing] = useState(false)

  /*
   * The dialog is mounted once and handed a different entry each time it opens, so the
   * field has to follow. Keying the whole dialog on the path instead would throw away the
   * "processing" state mid-submit.
   *
   * The stem is selected and the extension is not: renaming is nearly always changing the
   * name and keeping the type, and selecting the whole string means retyping `.conf` every
   * time. The element is found by id rather than held in a ref because `Input` is the
   * shell's control and does not take one - and adding a ref to a primitive forty screens
   * share, for one selection range on one screen, is the wrong direction to push a change.
   */
  useEffect(() => {
    if (!entry) {
      return
    }
    setName(entry.name)
    setOverwrite(false)
    setError(null)
    const dot = entry.name.lastIndexOf('.')
    const stem = dot > 0 ? dot : entry.name.length
    // After the dialog has been shown, or `showModal` moves focus afterwards.
    const frame = window.requestAnimationFrame(() => {
      const field = document.getElementById(fieldId)
      if (field instanceof HTMLInputElement) {
        field.focus()
        field.setSelectionRange(0, stem)
      }
    })
    return () => window.cancelAnimationFrame(frame)
  }, [entry, fieldId])

  const submit = async () => {
    if (!entry) {
      return
    }
    const wanted = name.trim()
    if (wanted === '') {
      setError('It needs a name.')
      return
    }
    if (wanted.includes('/')) {
      setError('A name cannot contain a slash. Use Move to put it in another folder.')
      return
    }
    if (wanted === entry.name) {
      setError('That is what it is already called.')
      return
    }
    setProcessing(true)
    const renamed = await renamePath(serviceId, rootId, entry.path, wanted, overwrite)
    setProcessing(false)
    if (renamed) {
      onClose()
    }
  }

  return (
    <Modal
      open={entry !== null}
      onClose={onClose}
      title="Rename"
      description={entry?.path}
      size="sm"
      footer={
        <>
          <Button variant="secondary" onClick={onClose} block>
            Cancel
          </Button>
          <Button onClick={() => void submit()} loading={processing} block>
            Rename
          </Button>
        </>
      }
    >
      <form
        className="flex flex-col gap-4"
        onSubmit={(event) => {
          event.preventDefault()
          void submit()
        }}
      >
        <Input
          id={fieldId}
          label="Name"
          name="to"
          value={name}
          onChange={(event) => {
            setName(event.target.value)
            setError(null)
          }}
          error={error ?? undefined}
          autoCapitalize="off"
          autoCorrect="off"
          spellCheck={false}
          hint="Just the name. Renaming keeps it in this folder."
        />
        <Checkbox
          label="Replace anything already called that"
          checked={overwrite}
          onChange={(event) => setOverwrite(event.target.checked)}
          hint="Off by default. With this on, a file at the new name is overwritten and not recoverable."
        />
      </form>
    </Modal>
  )
}
