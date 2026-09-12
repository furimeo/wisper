import {ByteAmountField} from '@/features/service/ByteAmountField'
import {t} from '@/i18n'
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
      title={t('database.form.create.title')}
      description={t('database.form.create.description')}
      footer={
        <div className="flex flex-col gap-2 sm:flex-row-reverse">
          <Button
            block
            className="sm:w-auto"
            loading={form.processing}
            disabled={projects.length === 0}
            onClick={submit}
          >
            {t('database.form.create.submit')}
          </Button>
          <Button variant="ghost" block className="sm:w-auto" onClick={onClose}>
            {t('database.form.create.cancel')}
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
          label={t('database.form.create.project')}
          required
          options={projects.map((project) => ({value: project.id, label: project.label}))}
          hint={t('database.form.create.projectHint')}
        />

        <Select
          {...form.bind('engine')}
          label={t('database.form.create.engine')}
          required
          options={engines.map((engine) => ({value: engine, label: engineLabel(engine)}))}
        />

        <Input
          {...form.bind('name')}
          label={t('database.form.create.name')}
          required
          autoComplete="off"
          autoCapitalize="none"
          spellCheck={false}
          placeholder="shop_production"
          className="font-mono"
          hint={t('database.form.create.nameHint')}
        />

        <ByteAmountField
          label={t('database.form.create.sizeLimit')}
          name="quotaBytes"
          bytes={form.data.quotaBytes}
          onBytes={(value) => form.set('quotaBytes', value)}
          error={form.error('quotaBytes')}
          hint={t('database.form.create.sizeLimitHint')}
        />

        <Checkbox
          {...form.check('dedicated')}
          label={t('database.form.create.dedicated')}
          hint={t('database.form.create.dedicatedHint')}
        />

        <button type="submit" className="sr-only">
          {t('database.form.create.submit')}
        </button>
      </form>
    </Modal>
  )
}
