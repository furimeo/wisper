import {t} from '@/i18n'
import {Badge} from '@/shell'

import type {ServiceSummary} from './serviceTypes'
import {reportedStateLabel, reportedStateTone} from './serviceVocabulary'

export function ServiceStatusBadge({status}: {status: ServiceSummary}) {
  if (status.archived) {
    return <Badge tone="neutral">{t('service.badge.archived')}</Badge>
  }
  if (status.reportedState === null) {
    return (
      <Badge tone="neutral" dot pulse={status.wantedRunning}>
        {status.wantedRunning ? t('service.badge.placing') : t('service.badge.stopped')}
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
