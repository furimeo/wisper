import {useI18n} from '@/i18n'
import {Button, Input, Modal, Textarea, useFormFields} from '@/shell'

/**
 * Adding a machine to the fleet.
 *
 * One step, not two. Design §7.3 makes "create the record and receive a bootstrap token"
 * a single action, because that is what makes sixty seconds from install command to a node
 * holding workloads achievable - and because a record with no token is a row an operator
 * has to come back to and finish. The controller issues the token in the same request and
 * redirects to the node's page with it.
 *
 * Four fields and three of them optional. The name is the only thing the panel genuinely
 * needs, and it is also what has to be typed back to delete the node later, so it is worth
 * choosing rather than generating.
 */
export interface NodeFormValues {
  name: string
  description: string
  publicAddress: string
  tags: string
  [key: string]: string
}

export function CreateNodeForm({open, onClose}: {open: boolean; onClose: () => void}) {
  const {t} = useI18n()
  const form = useFormFields<NodeFormValues>({
    name: '',
    description: '',
    publicAddress: '',
    tags: '',
  })

  function submit() {
    form.submit('/admin/nodes', {
      onSuccess: () => {
        form.reset()
        onClose()
      },
    })
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={t('node.form.create.title')}
      description={t('node.form.create.description')}
      footer={
        <div className="flex flex-col gap-2 sm:flex-row-reverse">
          <Button block className="sm:w-auto" loading={form.processing} onClick={submit}>
            {t('node.form.create.submit')}
          </Button>
          <Button variant="ghost" block className="sm:w-auto" onClick={onClose}>
            {t('node.form.create.cancel')}
          </Button>
        </div>
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
          {...form.bind('name')}
          label={t('node.form.create.name')}
          required
          maxLength={63}
          autoComplete="off"
          placeholder={t('node.form.create.namePlaceholder')}
          hint={t('node.form.create.nameHint')}
        />

        <Input
          {...form.bind('publicAddress')}
          label={t('node.form.create.publicAddress')}
          maxLength={255}
          autoComplete="off"
          placeholder={t('node.form.create.publicAddressPlaceholder')}
          hint={t('node.form.create.publicAddressHint')}
        />

        <Input
          {...form.bind('tags')}
          label={t('node.form.create.tags')}
          maxLength={500}
          autoComplete="off"
          placeholder={t('node.form.create.tagsPlaceholder')}
          hint={t('node.form.create.tagsHint')}
        />

        <Textarea
          {...form.bind('description')}
          label={t('node.form.create.notes')}
          maxLength={500}
          rows={2}
          autoGrow
          hint={t('node.form.create.notesHint')}
        />

        {/* Submits on Enter from any field without a visible duplicate of the footer button. */}
        <button type="submit" className="sr-only">
          {t('node.form.create.submit')}
        </button>
      </form>
    </Modal>
  )
}
