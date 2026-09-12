import {t} from '@/i18n'
import {Badge} from '@/shell'

import type {DeploymentSummary} from './deployTypes'
import {inFlight, statusLabel, statusTone} from './deployVocabulary'

export function DeploymentStatusBadge({deployment}: {deployment: DeploymentSummary}) {
  if (deployment.current && deployment.status === 'SUCCEEDED') {
    return (
      <Badge tone="running" dot>
        {t('deploy.badge.live')}
      </Badge>
    )
  }
  return (
    <Badge tone={statusTone(deployment.status)} dot pulse={inFlight(deployment)}>
      {statusLabel(deployment.status)}
    </Badge>
  )
}
