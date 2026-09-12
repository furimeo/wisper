import {t} from '@/i18n'
import {Button, Card, Checkbox, Input, Textarea, useFormFields} from '@/shell'

import type {NodeSummary} from './nodeTypes'

/**
 * What a node is *for*: how it is described, where customers reach it, and whether
 * placement is allowed to pick it.
 *
 * Separate from the operations card because these are edits and those are events. Nothing
 * here changes what the machine is doing right now - unticking "accept new placements"
 * stops the scheduler choosing it and touches nothing already running, which is the
 * difference between this and draining.
 *
 * The name is absent on purpose: it is what has to be typed to delete the node, so letting
 * it be edited would let somebody rename their way past the confirmation.
 */
interface SettingsValues {
  description: string
  publicAddress: string
  tags: string
  schedulable: boolean
  [key: string]: string | boolean
}

export function NodeSettingsForm({node}: {node: NodeSummary}) {
  const form = useFormFields<SettingsValues>({
    description: node.description ?? '',
    publicAddress: node.publicAddress ?? '',
    tags: node.tags.join(', '),
    schedulable: node.schedulable,
  })

  return (
    <Card
      title={t('node.settings.title')}
      description={t('node.settings.description')}
      footer={
        <div className="flex flex-col gap-2 sm:flex-row-reverse">
          <Button
            block
            className="sm:w-auto"
            loading={form.processing}
            disabled={!form.dirty}
            onClick={() => form.submit(`/admin/nodes/${node.id}/settings`)}
          >
            {t('node.settings.save')}
          </Button>
          {form.dirty ? (
            <Button variant="ghost" block className="sm:w-auto" onClick={form.reset}>
              {t('node.settings.discard')}
            </Button>
          ) : null}
        </div>
      }
    >
      <form
        className="flex flex-col gap-4"
        onSubmit={(event) => {
          event.preventDefault()
          form.submit(`/admin/nodes/${node.id}/settings`)
        }}
      >
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
          rows={2}
          autoGrow
          maxLength={500}
          hint={t('node.settings.notesHint')}
        />

        <Checkbox
          {...form.check('schedulable')}
          label={t('node.settings.schedulable')}
          hint={t('node.settings.schedulableHint')}
        />

        <button type="submit" className="sr-only">
          {t('node.settings.save')}
        </button>
      </form>
    </Card>
  )
}
