import {router} from '@inertiajs/react'

import {t} from '@/i18n'
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
    title: t('deploy.cancel.confirm_title', {sequence: deployment.sequence}),
    body: t('deploy.cancel.confirm_body'),
    confirmLabel: t('deploy.cancel.confirm_button'),
    cancelLabel: t('deploy.cancel.cancel_button'),
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
