import {t} from '@/i18n'
import {Button, Card, Input, Textarea, useFormFields} from '@/shell'

import type {Project} from './projectTypes'

/**
 * `POST /projects/{id}` - the name and the description, and nothing else.
 *
 * The address is deliberately not editable and there is no input for it. `RenameForm` on
 * the server has no `slug` component, so a box here would be a promise the panel cannot
 * keep: URLs do not move, because something is always still pointing at the old one.
 */
export function RenameProjectForm({project, disabled}: {project: Project; disabled: boolean}) {
  const form = useFormFields({
    name: project.name,
    description: project.description ?? '',
  })

  return (
    <Card
      title={t('project.rename.title')}
      description={t('project.rename.description')}
      footer={
        <Button
          block
          className="sm:w-auto"
          loading={form.processing}
          disabled={disabled || !form.dirty}
          onClick={() => form.submit(`/projects/${project.id}`)}
        >
          {t('project.rename.save')}
        </Button>
      }
    >
      <form
        className="flex flex-col gap-4"
        onSubmit={(event) => {
          event.preventDefault()
          form.submit(`/projects/${project.id}`)
        }}
      >
        <Input
          {...form.bind('name')}
          label={t('project.rename.name')}
          required
          maxLength={120}
          disabled={disabled}
          autoComplete="off"
        />
        <Textarea
          {...form.bind('description')}
          label={t('project.rename.descLabel')}
          maxLength={500}
          disabled={disabled}
          hint={t('project.rename.descHint')}
        />
        <p className="text-sm text-ink-500 dark:text-ink-400">
          {t('project.rename.addressNote', {slug: project.slug})}
        </p>
        <button type="submit" className="hidden" aria-hidden="true" tabIndex={-1} />
      </form>
    </Card>
  )
}
