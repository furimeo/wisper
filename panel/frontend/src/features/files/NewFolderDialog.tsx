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
      form.setError('name', 'A folder needs a name.')
      return
    }
    form.submit(`${filesBase(serviceId)}/folder`, {onSuccess: onClose})
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="New folder"
      description="It is created inside the folder you are looking at."
      size="sm"
      footer={
        <>
          <Button variant="secondary" onClick={onClose} block>
            Cancel
          </Button>
          <Button
            onClick={submit}
            loading={form.processing}
            disabled={form.data.name.trim() === ''}
            block
          >
            Create
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
          label="Name"
          autoFocus
          autoCapitalize="off"
          autoCorrect="off"
          spellCheck={false}
          placeholder="uploads"
          hint="Letters, digits, dots, dashes. No slashes - this makes one folder."
        />
      </form>
    </Modal>
  )
}
