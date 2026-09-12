import {router} from '@inertiajs/react'
import {useState} from 'react'

import {t} from '@/i18n'
import {Button, Card, askConfirmation} from '@/shell'

import type {Project} from './projectTypes'

/**
 * Archive, restore, and the one button that cannot be taken back.
 *
 * Archiving is reversible and says so; deleting is not, and asks the customer to type the
 * project's address before the button does anything. The typing is not theatre - the
 * server checks the same string in `DeleteProject`, because a confirmation enforced only
 * in a browser is a confirmation that is not enforced.
 *
 * The count is in the sentence rather than in the heading: "this deletes 3 services and
 * everything in them" is read, where "are you sure?" is clicked past.
 */
export function ProjectLifecycleCard({
  project,
  serviceCount,
  mayArchive,
  mayDelete,
}: {
  project: Project
  serviceCount: number
  mayArchive: boolean
  mayDelete: boolean
}) {
  const [working, setWorking] = useState(false)
  const services = t('project.card.servicesCount', {count: serviceCount})

  function post(path: string, data?: Record<string, string>) {
    setWorking(true)
    router.post(path, data ?? {}, {
      preserveScroll: true,
      onFinish: () => setWorking(false),
    })
  }

  async function confirmDelete() {
    const confirmed = await askConfirmation({
      title: t('project.lifecycle.confirmDeleteTitle', {name: project.name}),
      body: t('project.lifecycle.confirmDeleteBody', {services}),
      confirmLabel: t('project.lifecycle.confirmDeleteBtn'),
      tone: 'danger',
      requireText: project.slug,
      requireTextLabel: t('project.lifecycle.confirmDeleteTypeLabel', {slug: project.slug}),
    })
    if (confirmed) {
      post(`/projects/${project.id}/delete`, {confirmation: project.slug})
    }
  }

  return (
    <Card
      title={project.archived ? t('project.lifecycle.archivedTitle') : t('project.lifecycle.activeTitle')}
      description={
        project.archived
          ? t('project.lifecycle.archivedDesc')
          : t('project.lifecycle.activeDesc', {services})
      }
    >
      <div className="flex flex-col gap-3 sm:flex-row sm:flex-wrap">
        {project.archived ? (
          <Button
            variant="secondary"
            block
            className="sm:w-auto"
            disabled={!mayArchive || working}
            onClick={() => post(`/projects/${project.id}/restore`)}
          >
            {t('project.lifecycle.restoreBtn')}
          </Button>
        ) : (
          <Button
            variant="secondary"
            block
            className="sm:w-auto"
            disabled={!mayArchive || working}
            onClick={() => post(`/projects/${project.id}/archive`)}
          >
            {t('project.lifecycle.archiveBtn')}
          </Button>
        )}

        <Button
          variant="danger"
          block
          className="sm:w-auto"
          disabled={!mayDelete || working}
          onClick={() => void confirmDelete()}
        >
          {t('project.lifecycle.deleteBtn')}
        </Button>
      </div>

      {mayDelete ? null : (
        <p className="mt-3 text-sm text-ink-500 dark:text-ink-400">
          {t('project.lifecycle.readOnlyNotice')}
        </p>
      )}
    </Card>
  )
}
