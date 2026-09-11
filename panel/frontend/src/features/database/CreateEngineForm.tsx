import {Button, Input, Modal, Select, useFormFields} from '@/shell'

import type {EngineKind} from './databaseTypes'
import {engineLabel} from './databaseVocabulary'

/**
 * Putting an engine container on a node ahead of anybody asking for one.
 *
 * Most engines are never created here. `ChooseDatabaseEngine` makes one the first time a
 * customer asks for a database on a node that has none, which is what stops a fresh
 * platform answering its first customer with "an operator has to set up PostgreSQL first".
 * This exists for the operator who would rather it were already warm, or who is adding a
 * second engine to a node that only runs one.
 *
 * The node is typed as an id rather than chosen from a list, because `GET /admin/databases`
 * sends the engines and not the fleet - and the nodes worth choosing here are precisely the
 * ones that do not appear among the engines yet. The node list page shows the ids.
 */
interface EngineValues {
  nodeId: string
  engine: string
  [key: string]: string
}

const UUID_SHAPE = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/

export function CreateEngineForm({
  open,
  onClose,
  kinds,
}: {
  open: boolean
  onClose: () => void
  kinds: EngineKind[]
}) {
  const form = useFormFields<EngineValues>({nodeId: '', engine: kinds[0] ?? 'POSTGRES'})

  function submit() {
    if (!UUID_SHAPE.test(form.data.nodeId.trim())) {
      form.setError('nodeId', 'That is not a node id. Copy it from the node’s page under Nodes.')
      return
    }
    form.submit('/admin/databases', {
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
      title="Add an engine container"
      description="It goes into the node's spec and starts on its next reconcile. One shared
        instance per engine per node is the design; customers get a database and a login inside
        it, not a container each."
      footer={
        <div className="flex flex-col gap-2 sm:flex-row-reverse">
          <Button block className="sm:w-auto" loading={form.processing} onClick={submit}>
            Add it
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
        <Select
          {...form.bind('engine')}
          label="Engine"
          required
          options={kinds.map((kind) => ({value: kind, label: engineLabel(kind)}))}
        />

        <Input
          {...form.bind('nodeId')}
          label="Node id"
          required
          autoComplete="off"
          spellCheck={false}
          className="font-mono text-xs"
          placeholder="00000000-0000-0000-0000-000000000000"
          hint="Open Nodes, pick the machine, and copy the node id from its facts."
        />

        <button type="submit" className="sr-only">
          Add engine
        </button>
      </form>
    </Modal>
  )
}
