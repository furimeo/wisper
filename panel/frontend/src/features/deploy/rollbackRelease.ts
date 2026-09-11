import {router} from '@inertiajs/react'

import {askConfirmation} from '@/shell'

import type {DeploymentSummary} from './deployTypes'
import {shortCommit} from './deployVocabulary'

/**
 * `POST /services/{serviceId}/deployments/{deploymentId}/rollback`, behind a question.
 *
 * One tap, because that is the whole value of it: the release directory is still on the
 * node, so going back is a symlink swap rather than a rebuild, and a customer whose site
 * broke ninety seconds ago should not have to read a manual to undo it. The confirmation
 * exists because the button sits in a list of near-identical rows on a phone, and rolling
 * back to the wrong release is a second outage rather than the end of the first one.
 *
 * The wording names the release being restored and says what happens to the current one,
 * because "are you sure" tells somebody nothing they did not already know. A rollback is
 * not destructive - it writes a *new* deployment pointing at the old release, so the
 * history still reads forwards - and saying so is what makes the button pressable in a
 * hurry.
 */
export async function rollbackRelease(
  serviceId: string,
  deployment: DeploymentSummary,
): Promise<void> {
  const commit = shortCommit(deployment.commitSha)
  const what = deployment.commitSubject
    ? `“${deployment.commitSubject}”${commit ? ` (${commit})` : ''}`
    : commit
      ? `commit ${commit}`
      : 'that release'

  const confirmed = await askConfirmation({
    title: `Roll back to deployment #${deployment.sequence}?`,
    body:
      `Visitors get ${what} again, usually within seconds - the release is still on the ` +
      'node, so nothing is rebuilt. This is recorded as a new deployment, and whatever is ' +
      'live now stays in the history and can be rolled back to in turn.',
    confirmLabel: `Roll back to #${deployment.sequence}`,
    cancelLabel: 'Leave it as it is',
    tone: 'danger',
  })
  if (!confirmed) {
    return
  }
  router.post(
    `/services/${serviceId}/deployments/${deployment.id}/rollback`,
    {},
    {preserveScroll: true},
  )
}
