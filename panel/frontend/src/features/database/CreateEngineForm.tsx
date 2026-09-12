import {t} from '@/i18n'
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
      form.setError('nodeId', t('database.adminForm.invalidNodeId'))
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
      title={t('database.adminForm.title')}
      description={t('database.adminForm.description')}
      footer={
        <div className="flex flex-col gap-2 sm:flex-row-reverse">
          <Button block className="sm:w-auto" loading={form.processing} onClick={submit}>
            {t('database.adminForm.add')}
          </Button>
          <Button variant="ghost" block className="sm:w-auto" onClick={onClose}>
            {t('database.adminForm.cancel')}
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
          label={t('database.adminForm.engine')}
          required
          options={kinds.map((kind) => ({value: kind, label: engineLabel(kind)}))}
        />

        <Input
          {...form.bind('nodeId')}
          label={t('database.adminForm.nodeId')}
          required
          autoComplete="off"
          spellCheck={false}
          className="font-mono text-xs"
          placeholder="00000000-0000-0000-0000-000000000000"
          hint={t('database.adminForm.nodeIdHint')}
        />

        <button type="submit" className="sr-only">
          {t('database.adminForm.add')}
        </button>
      </form>
    </Modal>
  )
}
