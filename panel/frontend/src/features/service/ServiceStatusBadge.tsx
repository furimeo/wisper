import {Badge} from '@/shell'

import type {ServiceSummary} from './serviceTypes'
import {reportedStateLabel, reportedStateTone} from './serviceVocabulary'

/**
 * What a node last said about one service, as a pill.
 *
 * Fact, not intent. `desiredState` is what the customer asked for and lives next to the
 * power buttons; this is what the node reports, and the two are shown separately because
 * the case worth seeing is the one where they disagree - asked to run, reported crashed.
 *
 * Three states are not a colour: nothing reported yet ("Not placed"), a service the
 * customer stopped, and an archived one. Painting any of those red teaches people that red
 * means nothing.
 */
export function ServiceStatusBadge({status}: {status: ServiceSummary}) {
  if (status.archived) {
    return <Badge tone="neutral">Archived</Badge>
  }
  if (status.reportedState === null) {
    return (
      <Badge tone="neutral" dot pulse={status.wantedRunning}>
        {status.wantedRunning ? 'Placing' : 'Stopped'}
      </Badge>
    )
  }

  const moving = status.reportedState === 'CREATING' || status.reportedState === 'PENDING'
  return (
    <Badge tone={reportedStateTone(status.reportedState)} dot pulse={moving}>
      {reportedStateLabel(status.reportedState)}
    </Badge>
  )
}
