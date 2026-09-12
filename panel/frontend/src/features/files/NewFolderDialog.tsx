import {t} from '@/i18n'
import {Button, Input, Modal, useFormFields} from '@/shell'

import {filesBase} from './fileRequests'

/**
 * Create a folder in the directory being browsed.
 *
 * `POST /services/{id}/files/folder`, which redirects back here with a flash message -
 * the panel's answer to every write (`docs/contracts/panel-http.md`). The name is sent as
 * a name and never as a path: `RelativePath.resolve` refuses one containing a slash, so a
 * customer who types `a/b` is told rather than quietly getting one folder called `a/b` or
 * two called `a` and `b`.
 */
export function NewFolderDialog({
  open,
  onClose,
  serviceId,
  rootId,
  path,
}: {
  open: boolean
  onClose: () => void
  serviceId: string
  rootId: string
  /** The directory the folder goes in, relative to the root. */
  path: string
}) {
  const form = useFormFields({rootId, path, name: ''})

  const submit = () => {
    if (form.data.name.trim() === '') {
      form.setError('name', t('files.new_folder.empty_error'))
      return
    }
    form.submit(`${filesBase(serviceId)}/folder`, {onSuccess: onClose})
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={t('files.new_folder.title')}
      description={t('files.new_folder.description')}
      size="sm"
      footer={
        <>
          <Button variant="secondary" onClick={onClose} block>
            {t('files.new_folder.cancel')}
          </Button>
          <Button
            onClick={submit}
            loading={form.processing}
            disabled={form.data.name.trim() === ''}
            block
          >
            {t('files.new_folder.create')}
          </Button>
        </>
      }
    >
      <form
        onSubmit={(event) => {
          event.preventDefault()
          submit()
        }}
      >
        <Input
          {...form.bind('name')}
          label={t('files.new_folder.name_label')}
          autoFocus
          autoCapitalize="off"
          autoCorrect="off"
          spellCheck={false}
          placeholder={t('files.new_folder.name_placeholder')}
          hint={t('files.new_folder.name_hint')}
        />
      </form>
    </Modal>
  )
}
