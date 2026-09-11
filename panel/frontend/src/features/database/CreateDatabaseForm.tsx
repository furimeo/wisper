import {ByteAmountField} from '@/features/service/ByteAmountField'
import {Button, Checkbox, Input, Modal, Select, useFormFields} from '@/shell'

import type {EngineKind, ManagedDatabaseView} from './databaseTypes'
import {engineLabel} from './databaseVocabulary'

/**
 * Asking for a database.
 *
 * Four decisions and three of them have a sensible answer already: which project it
 * belongs to, which engine, what it is called, and how large it may get. Only the name is
 * genuinely the customer's to think about, and it is also the string they will have to
 * type back to drop it.
 *
 * The project list is derived from the databases already on the page, because that is what
 * `GET /databases` sends. A customer with no database yet therefore has nothing to pick,
 * and the list page says so and points at Projects rather than showing a form that cannot
 * be completed.
 */
export interface DatabaseFormValues {
  projectId: string
  engine: string
  name: string
  quotaBytes: string
  dedicated: boolean
  [key: string]: string | boolean
}

export interface ProjectChoice {
  id: string
  label: string
}

/** One entry per project the customer already has a database in, organization included. */
export function projectChoices(databases: ManagedDatabaseView[]): ProjectChoice[] {
  const choices = new Map<string, ProjectChoice>()
  for (const database of databases) {
    choices.set(database.projectId, {
      id: database.projectId,
      label: `${database.organizationName} · ${database.projectName}`,
    })
  }
  return [...choices.values()].sort((left, right) => left.label.localeCompare(right.label))
}

const DEFAULT_QUOTA_BYTES = String(1024 * 1024 * 1024)

export function CreateDatabaseForm({
  open,
  onClose,
  engines,
  projects,
}: {
  open: boolean
  onClose: () => void
  engines: EngineKind[]
  projects: ProjectChoice[]
}) {
  const form = useFormFields<DatabaseFormValues>({
    projectId: projects[0]?.id ?? '',
    engine: engines[0] ?? 'POSTGRES',
    name: '',
    quotaBytes: DEFAULT_QUOTA_BYTES,
    dedicated: false,
  })

  function submit() {
    form.submit('/databases', {
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
      title="New database"
      description="It is created on the engine already running on the node your project's services
        are placed on, with a login of its own that can reach nothing else."
      footer={
        <div className="flex flex-col gap-2 sm:flex-row-reverse">
          <Button
            block
            className="sm:w-auto"
            loading={form.processing}
            disabled={projects.length === 0}
            onClick={submit}
          >
            Create it
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
          {...form.bind('projectId')}
          label="Project"
          required
          options={projects.map((project) => ({value: project.id, label: project.label}))}
          hint="The database belongs to a project, which is what decides who can reach it."
        />

        <Select
          {...form.bind('engine')}
          label="Engine"
          required
          options={engines.map((engine) => ({value: engine, label: engineLabel(engine)}))}
        />

        <Input
          {...form.bind('name')}
          label="Name"
          required
          autoComplete="off"
          autoCapitalize="none"
          spellCheck={false}
          placeholder="shop_production"
          className="font-mono"
          hint="Lower-case letters, digits and underscores, starting with a letter. This is the
            name you type back to drop it."
        />

        <ByteAmountField
          label="Size limit"
          name="quotaBytes"
          bytes={form.data.quotaBytes}
          onBytes={(value) => form.set('quotaBytes', value)}
          error={form.error('quotaBytes')}
          hint="Measured by the node on its own schedule. You can change it later without
            touching the data."
        />

        <Checkbox
          {...form.check('dedicated')}
          label="Give this organization its own engine container"
          hint="Shared is the default and it is the right answer for almost everything: a
            PostgreSQL container costs 30-50MB sitting idle, and one per customer does not
            scale. A dedicated instance isolates noisy neighbours at that cost."
        />

        <button type="submit" className="sr-only">
          Create database
        </button>
      </form>
    </Modal>
  )
}
