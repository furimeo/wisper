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
      title="Settings"
      description="Placement reads the tags; a domain's A record has to point at the public
        address."
      footer={
        <div className="flex flex-col gap-2 sm:flex-row-reverse">
          <Button
            block
            className="sm:w-auto"
            loading={form.processing}
            disabled={!form.dirty}
            onClick={() => form.submit(`/admin/nodes/${node.id}/settings`)}
          >
            Save
          </Button>
          {form.dirty ? (
            <Button variant="ghost" block className="sm:w-auto" onClick={form.reset}>
              Discard changes
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
          label="Public address"
          maxLength={255}
          autoComplete="off"
          placeholder="203.0.113.10"
          hint="Where customers' traffic arrives. The panel never dials this - the node dials out."
        />

        <Input
          {...form.bind('tags')}
          label="Tags"
          maxLength={500}
          autoComplete="off"
          placeholder="eu, ssd, general"
          hint="Comma separated. A service that requires a tag will only be placed on a node
            carrying it."
        />

        <Textarea
          {...form.bind('description')}
          label="Notes"
          rows={2}
          autoGrow
          maxLength={500}
          hint="For whoever reads this page next."
        />

        <Checkbox
          {...form.check('schedulable')}
          label="Accept new placements"
          hint="Off means the scheduler stops choosing this machine. Nothing already running on it
            is moved or stopped - that is what draining is for."
        />

        <button type="submit" className="sr-only">
          Save settings
        </button>
      </form>
    </Card>
  )
}
