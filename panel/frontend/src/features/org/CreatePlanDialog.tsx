import {Button, Input, Modal, Textarea, useFormFields} from '@/shell'

/**
 * `POST /admin/plans` - a new tier.
 *
 * Three fields and no limits. `CreatePlan` starts every one of the thirteen at zero, and
 * the dialog says so rather than pretending a plan is usable the moment it is created: a
 * tier with all limits at zero refuses everything, which is the correct default for
 * something half-configured and a nasty surprise for anybody who assumed otherwise and
 * made it the default.
 *
 * The code is the short constant an operator types and reads in an audit line; the name is
 * what a customer sees. Both are asked for because using one for both produces either
 * `PRO_ANNUAL_2026` on a customer's overview or `Pro (annual)` in a log line.
 */
export function CreatePlanDialog({open, onClose}: {open: boolean; onClose: () => void}) {
  const form = useFormFields({code: '', name: '', description: ''})

  function create() {
    form.submit('/admin/plans', {
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
      title="New plan"
      description="Every limit starts at zero, so set them before anybody is moved onto it."
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={form.processing}>
            Cancel
          </Button>
          <Button loading={form.processing} onClick={create}>
            Create plan
          </Button>
        </>
      }
    >
      <form
        className="flex flex-col gap-4"
        onSubmit={(event) => {
          event.preventDefault()
          create()
        }}
      >
        <Input
          {...form.bind('code')}
          label="Code"
          required
          autoFocus
          maxLength={40}
          autoComplete="off"
          autoCapitalize="characters"
          spellCheck={false}
          className="font-mono"
          placeholder="pro"
          hint="Short, stable, and what appears in audit lines. It is not shown to customers as a title."
        />

        <Input
          {...form.bind('name')}
          label="Name"
          required
          maxLength={120}
          autoComplete="off"
          placeholder="Pro"
          hint="What the customer sees on their organization screen."
        />

        <Textarea
          {...form.bind('description')}
          label="Description"
          maxLength={500}
          hint="Optional. One line about who this tier is for."
        />

        <button type="submit" className="hidden" aria-hidden="true" tabIndex={-1} />
      </form>
    </Modal>
  )
}
