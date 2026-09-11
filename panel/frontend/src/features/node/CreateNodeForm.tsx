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
      title="Add a node"
      description="This creates the record and mints a bootstrap token in one step. The token is
        shown once, on the page you land on, and it is good for fifteen minutes."
      footer={
        <div className="flex flex-col gap-2 sm:flex-row-reverse">
          <Button block className="sm:w-auto" loading={form.processing} onClick={submit}>
            Create and issue a token
          </Button>
          <Button variant="ghost" block className="sm:w-auto" onClick={onClose}>
            Cancel
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
          label="Name"
          required
          maxLength={63}
          autoComplete="off"
          placeholder="fra-01"
          hint="What you will call this machine everywhere, and what you have to type to delete it."
        />

        <Input
          {...form.bind('publicAddress')}
          label="Public address"
          maxLength={255}
          autoComplete="off"
          placeholder="203.0.113.10"
          hint="The address customers' traffic reaches this machine on. It is what a domain's A
            record has to point at, and it is not how the panel reaches the node - the node dials
            out."
        />

        <Input
          {...form.bind('tags')}
          label="Tags"
          maxLength={500}
          autoComplete="off"
          placeholder="eu, ssd, general"
          hint="Comma separated. Placement filters on these, so a service that needs an SSD can be
            kept off a machine that has none."
        />

        <Textarea
          {...form.bind('description')}
          label="Notes"
          maxLength={500}
          rows={2}
          autoGrow
          hint="For the next operator: whose hardware it is, which rack, who to ring."
        />

        {/* Submits on Enter from any field without a visible duplicate of the footer button. */}
        <button type="submit" className="sr-only">
          Create node
        </button>
      </form>
    </Modal>
  )
}
