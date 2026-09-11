import type {ServiceView} from './serviceTypes'
import {RUNC_WARNING} from './serviceVocabulary'

/**
 * The banner next to a service that is not running under gVisor.
 *
 * The escape hatch is deliberate - `runsc` cannot run everything, and a platform with no
 * way out turns "gVisor cannot do this" into "your app is broken" - but it is only safe
 * to offer while it stays visible. A weaker sandbox nobody can see is the one that gets
 * chosen by default six months later, so the reason the customer gave is printed back at
 * them on the two screens where the service is looked at.
 *
 * It renders nothing for a service under gVisor. There is no banner saying "this is
 * configured correctly": a notice that is always there is a notice nobody reads.
 */
export function IsolationWarning({service}: {service: ServiceView}) {
  if (service.runtimeIsolation !== 'RUNC') {
    return null
  }

  return (
    <div className="rounded-xl border border-degraded/40 bg-degraded/10 px-4 py-3">
      <p className="text-sm font-medium text-ink-900 dark:text-ink-100">{RUNC_WARNING}</p>
      {service.isolationReason ? (
        <p className="mt-1 text-sm leading-relaxed text-ink-700 dark:text-ink-300">
          Reason given: {service.isolationReason}
        </p>
      ) : null}
    </div>
  )
}
