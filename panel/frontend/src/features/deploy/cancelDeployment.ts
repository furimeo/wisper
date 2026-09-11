import {router} from '@inertiajs/react'

import {askConfirmation} from '@/shell'

import type {DeploymentSummary} from './deployTypes'

/**
 * `POST /services/{serviceId}/deployments/{deploymentId}/cancel`, behind a question.
 *
 * A confirmation rather than a bare button because the two things this is next to on a
 * phone are "deploy again" and "roll back", and a thumb aiming for either of those and
 * landing here throws away a build that may be four minutes in.
 *
 * The server answers with a redirect and a flash, which the shell's `Toaster` shows, so
 * there is nothing to report here. `preserveScroll` keeps a customer who pressed cancel
 * halfway down a build log where they were.
 */
export async function cancelDeployment(
  serviceId: string,
  deployment: DeploymentSummary,
): Promise<void> {
  const confirmed = await askConfirmation({
    title: `Cancel deployment #${deployment.sequence}?`,
    body:
      'The build stops where it is. Whatever is live now stays live, and nothing that has ' +
      'already been published is undone.',
    confirmLabel: 'Cancel the build',
    cancelLabel: 'Leave it running',
    tone: 'danger',
  })
  if (!confirmed) {
    return
  }
  router.post(
    `/services/${serviceId}/deployments/${deployment.id}/cancel`,
    {},
    {preserveScroll: true},
  )
}
