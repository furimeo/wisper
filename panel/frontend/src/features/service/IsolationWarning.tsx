import {t} from '@/i18n'

import type {ServiceView} from './serviceTypes'
import {runcWarning} from './serviceVocabulary'

export function IsolationWarning({service}: {service: ServiceView}) {
  if (service.runtimeIsolation !== 'RUNC') {
    return null
  }

  return (
    <div className="rounded-xl border border-degraded/40 bg-degraded/10 px-4 py-3">
      <p className="text-sm font-medium text-ink-900 dark:text-ink-100">{runcWarning()}</p>
      {service.isolationReason ? (
        <p className="mt-1 text-sm leading-relaxed text-ink-700 dark:text-ink-300">
          {t('service.isolation_warning.reason_given', {reason: service.isolationReason})}
        </p>
      ) : null}
    </div>
  )
}
