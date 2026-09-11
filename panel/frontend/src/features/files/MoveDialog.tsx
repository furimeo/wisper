import {useEffect, useState} from 'react'

import {Button, Checkbox, Input, Modal, toast} from '@/shell'

import {movePaths} from './movePaths'
import type {FileEntryView} from './fileTypes'

/**
 * Putting a selection somewhere else in the same tree.
 *
 * The destination is a folder, typed relative to the top of the tree, and the entries keep
 * their names. That is the operation somebody means when they select nine log files and
 * reach for Move; renaming one of them on the way is a different intention and has its own
 * dialog.
 *
 * A move is one request per entry, because the panel audits each one - so the dialog
 * reports how many actually moved rather than trusting the flash message, which only ever
 * describes the last of them. Nothing is created on the way: moving into a folder that
 * does not exist is refused by the node, and inventing the folder silently would leave a
 * customer with a directory they did not ask for and cannot find.
 */
export function MoveDialog({
  entries,
  onClose,
  serviceId,
  rootId,
  directory,
}: {
  /** What is being moved. Empty means the dialog is shut. */
  entries: FileEntryView[]
  onClose: () => void
  serviceId: string
  rootId: string
  /** The folder being browsed, which is where the field starts. */
  directory: string
}) {
  const [destination, setDestination] = useState(directory)
  const [overwrite, setOverwrite] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [processing, setProcessing] = useState(false)
  const open = entries.length > 0

  // Reopened over a different selection, possibly in a different folder.
  const key = entries.map((entry) => entry.path).join('\n')
  useEffect(() => {
    if (key !== '') {
      setDestination(directory)
      setOverwrite(false)
      setError(null)
    }
  }, [key, directory])

  const submit = async () => {
    const target = destination.trim().replace(/^\/+/, '').replace(/\/+$/, '')
    if (target === directory) {
      setError('That is the folder they are already in.')
      return
    }
    setProcessing(true)
    const moved = await movePaths(
      serviceId,
      rootId,
      entries.map((entry) => entry.path),
      target,
      overwrite,
    )
    setProcessing(false)
    if (moved === entries.length) {
      if (moved > 1) {
        toast.success(`Moved ${moved} entries.`)
      }
      onClose()
      return
    }
    // Some of them were refused; the panel's own flash says why the last one was. Staying
    // open keeps the destination on screen so it can be corrected.
    setError(
      moved === 0
        ? 'Nothing was moved. The message above says what the panel refused.'
        : `${moved} of ${entries.length} moved. The rest were refused - the message above says why.`,
    )
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={entries.length === 1 ? 'Move' : `Move ${entries.length} entries`}
      description={entries.length === 1 ? entries[0]?.path : undefined}
      size="sm"
      dismissible={!processing}
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={processing} block>
            Cancel
          </Button>
          <Button onClick={() => void submit()} loading={processing} block>
            Move
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
          label="Into which folder"
          name="destination"
          value={destination}
          onChange={(event) => {
            setDestination(event.target.value)
            setError(null)
          }}
          error={error ?? undefined}
          autoCapitalize="off"
          autoCorrect="off"
          spellCheck={false}
          placeholder="the top of this tree"
          hint="Relative to the top of this tree. The folder has to exist already."
        />
        <Checkbox
          label="Replace anything already there"
          checked={overwrite}
          onChange={(event) => setOverwrite(event.target.checked)}
          hint="Off by default, so a move cannot quietly overwrite a file of the same name."
        />
        {entries.length > 1 ? (
          <p className="text-sm text-ink-600 dark:text-ink-400">
            {entries.length} entries, keeping the names they have now.
          </p>
        ) : null}
      </form>
    </Modal>
  )
}
