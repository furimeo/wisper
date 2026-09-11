import {router} from '@inertiajs/react'
import {useState} from 'react'

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
  const services = `${serviceCount} ${serviceCount === 1 ? 'service' : 'services'}`

  function post(path: string, data?: Record<string, string>) {
    setWorking(true)
    router.post(path, data ?? {}, {
      preserveScroll: true,
      onFinish: () => setWorking(false),
    })
  }

  async function confirmDelete() {
    const confirmed = await askConfirmation({
      title: `Delete ${project.name}?`,
      body:
        `This removes the project and ${services} in it. Volume data stays on the node until ` +
        'it is purged, and nothing else about this can be undone.',
      confirmLabel: 'Delete project',
      tone: 'danger',
      requireText: project.slug,
      requireTextLabel: `Type ${project.slug} to confirm`,
    })
    if (confirmed) {
      post(`/projects/${project.id}/delete`, {confirmation: project.slug})
    }
  }

  return (
    <Card
      title={project.archived ? 'Archived' : 'Archive or delete'}
      description={
        project.archived
          ? 'Nothing in this project is running. Restore it and the services come back stopped.'
          : `Archiving stops everything and deletes nothing. Deleting removes the project and ${services}.`
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
            Restore project
          </Button>
        ) : (
          <Button
            variant="secondary"
            block
            className="sm:w-auto"
            disabled={!mayArchive || working}
            onClick={() => post(`/projects/${project.id}/archive`)}
          >
            Archive project
          </Button>
        )}

        <Button
          variant="danger"
          block
          className="sm:w-auto"
          disabled={!mayDelete || working}
          onClick={() => void confirmDelete()}
        >
          Delete project
        </Button>
      </div>

      {mayDelete ? null : (
        <p className="mt-3 text-sm text-ink-500 dark:text-ink-400">
          Deleting a project is an owner or admin decision. A developer may break a service and
          should not be able to remove the folder holding six of them.
        </p>
      )}
    </Card>
  )
}
