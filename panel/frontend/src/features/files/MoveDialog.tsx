import {useEffect, useState} from 'react'

import {t} from '@/i18n'
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
      setError(t('files.move.same_folder_error'))
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
        toast.success(t('files.move.moved_toast', {count: moved}))
      }
      onClose()
      return
    }
    // Some of them were refused; the panel's own flash says why the last one was. Staying
    // open keeps the destination on screen so it can be corrected.
    setError(
      moved === 0
        ? t('files.move.nothing_moved_error')
        : t('files.move.partial_moved_error', {moved, count: entries.length}),
    )
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={
        entries.length === 1
          ? t('files.move.title_one')
          : t('files.move.title_many', {count: entries.length})
      }
      description={entries.length === 1 ? entries[0]?.path : undefined}
      size="sm"
      dismissible={!processing}
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={processing} block>
            {t('files.move.cancel')}
          </Button>
          <Button onClick={() => void submit()} loading={processing} block>
            {t('files.move.button')}
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
          label={t('files.move.into_label')}
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
          placeholder={t('files.move.into_placeholder')}
          hint={t('files.move.into_hint')}
        />
        <Checkbox
          label={t('files.move.replace_label')}
          checked={overwrite}
          onChange={(event) => setOverwrite(event.target.checked)}
          hint={t('files.move.replace_hint')}
        />
        {entries.length > 1 ? (
          <p className="text-sm text-ink-600 dark:text-ink-400">
            {t('files.move.keeping_names', {count: entries.length})}
          </p>
        ) : null}
      </form>
    </Modal>
  )
}
