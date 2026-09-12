import {useEffect} from 'react'

import {t} from '@/i18n'
import {Button, Checkbox, Input, Modal, useFormFields} from '@/shell'

import {filesBase} from './fileRequests'
import type {FileEntryView} from './fileTypes'

/**
 * Unpack an archive into a directory, on the node.
 *
 * The other half of {@link ArchiveDialog}, and the reason a customer on a phone can get a
 * whole project onto a volume: compress it locally, upload one file over a connection
 * that keeps dropping, unzip it here.
 *
 * The destination defaults to the folder the archive is in rather than to a new folder
 * named after it. That is what `tar -x` does and what a zip full of loose files needs;
 * the alternative would quietly nest a project one level deeper than its build expects.
 * `overwrite` is off, so an extraction that would replace existing files says so instead.
 */
export function ExtractDialog({
  archive,
  onClose,
  serviceId,
  rootId,
  directory,
}: {
  /** The archive to unpack, or null when the dialog is shut. */
  archive: FileEntryView | null
  onClose: () => void
  serviceId: string
  rootId: string
  /** The folder being browsed, which is where the contents go by default. */
  directory: string
}) {
  const form = useFormFields({
    rootId,
    archive: archive?.path ?? '',
    destination: directory,
    overwrite: false,
  })
  const {patch} = form

  useEffect(() => {
    if (archive) {
      patch({archive: archive.path, destination: directory, overwrite: false})
    }
  }, [archive, directory, patch])

  const submit = () => {
    form.submit(`${filesBase(serviceId)}/extract`, {onSuccess: onClose})
  }

  return (
    <Modal
      open={archive !== null}
      onClose={onClose}
      title={t('files.extract.title')}
      description={archive ? t('files.extract.description', {name: archive.name}) : undefined}
      size="sm"
      footer={
        <>
          <Button variant="secondary" onClick={onClose} block>
            {t('files.extract.cancel')}
          </Button>
          <Button onClick={submit} loading={form.processing} block>
            {t('files.extract.unzip')}
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
          {...form.bind('destination')}
          label={t('files.extract.into_label')}
          autoCapitalize="off"
          autoCorrect="off"
          spellCheck={false}
          placeholder={t('files.extract.into_placeholder')}
          hint={t('files.extract.into_hint')}
        />
        <Checkbox
          {...form.check('overwrite')}
          label={t('files.extract.overwrite_label')}
          hint={t('files.extract.overwrite_hint')}
        />
      </form>
    </Modal>
  )
}
