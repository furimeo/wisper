import {Badge} from '@/shell'

import type {DeploymentSummary} from './deployTypes'
import {inFlight, statusLabel, statusTone} from './deployVocabulary'

/**
 * Where one deployment stands, as a pill.
 *
 * "Live" is not a status - it is `SUCCEEDED` plus `current`, which the
 * `deployment_current_idx` partial unique index keeps to one row per service. It is shown
 * as its own pill because it is the question the list is opened to answer: of nine
 * successful deployments, exactly one is what visitors are being served, and a column of
 * nine identical green pills does not say which.
 *
 * A build still moving pulses. That is the only animation on the row, and it is what tells
 * somebody the page is live without a spinner in the corner they have to interpret.
 */
export function DeploymentStatusBadge({deployment}: {deployment: DeploymentSummary}) {
  if (deployment.current && deployment.status === 'SUCCEEDED') {
    return (
      <Badge tone="running" dot>
        Live
      </Badge>
    )
  }
  return (
    <Badge tone={statusTone(deployment.status)} dot pulse={inFlight(deployment)}>
      {statusLabel(deployment.status)}
    </Badge>
  )
}
