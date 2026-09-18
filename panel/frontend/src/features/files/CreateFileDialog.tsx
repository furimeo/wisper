import {t} from '@/i18n'
import {Button, Input, Modal, useFormFields} from '@/shell'

import {filesBase} from './fileRequests'

/**
 * Create a new blank file in the directory being browsed.
 *
 * `POST /services/{id}/files/file`
 */
export function CreateFileDialog({
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
  /** The directory the file goes in, relative to the root. */
  path: string
}) {
  const form = useFormFields({rootId, path, name: ''})

  const submit = () => {
    if (form.data.name.trim() === '') {
      form.setError('name', t('files.new_file.empty_error'))
      return
    }
    form.submit(`${filesBase(serviceId)}/file`, {onSuccess: onClose})
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={t('files.new_file.title')}
      description={t('files.new_file.description')}
      size="sm"
      footer={
        <>
          <Button variant="secondary" onClick={onClose} block>
            {t('files.new_file.cancel')}
          </Button>
          <Button
            onClick={submit}
            loading={form.processing}
            disabled={form.data.name.trim() === ''}
            block
          >
            {t('files.new_file.create')}
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
          label={t('files.new_file.name_label')}
          autoFocus
          autoCapitalize="off"
          autoCorrect="off"
          spellCheck={false}
          placeholder={t('files.new_file.name_placeholder')}
          hint={t('files.new_file.name_hint')}
        />
      </form>
    </Modal>
  )
}
