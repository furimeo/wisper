import {router} from '@inertiajs/react'
import {useEffect} from 'react'

/**
 * Keeps a database screen current while the platform still owes it work.
 *
 * Provisioning and dropping are both desired-state changes: the panel writes the intent,
 * the node picks it up on its next reconcile, and a status report comes back. Without
 * something like this the customer presses "create", the page says "being created", and it
 * says that until they reload by hand - which reads as broken at the exact moment the
 * platform is working.
 *
 * A partial reload rather than a stream, because there is no database feed to subscribe
 * to: `docs/contracts/pages.md` §7 lists every non-page endpoint and the SSE ones are
 * logs, metrics and the terminal.
 *
 * `PENDING` and `DELETING` are worth six seconds; everything else is a background check
 * that mostly catches a size measurement. Neither runs while the tab is hidden.
 */
const IN_FLIGHT_MS = 6_000

const STEADY_MS = 60_000

export function useLiveDatabases(props: string[], inFlight: boolean, enabled = true): void {
  const key = props.join(',')

  useEffect(() => {
    if (!enabled) {
      return
    }

    const ask = () => {
      if (document.visibilityState === 'visible') {
        router.reload({only: key.split(',')})
      }
    }
    const timer = window.setInterval(ask, inFlight ? IN_FLIGHT_MS : STEADY_MS)
    const onVisibilityChange = () => ask()
    document.addEventListener('visibilitychange', onVisibilityChange)

    return () => {
      window.clearInterval(timer)
      document.removeEventListener('visibilitychange', onVisibilityChange)
    }
  }, [enabled, inFlight, key])
}
