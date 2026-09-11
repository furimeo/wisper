import {router} from '@inertiajs/react'
import {useEffect} from 'react'

/**
 * Keeps the `status` prop on a service screen current while somebody is watching it.
 *
 * The panel owns intent and the node owns fact (AGENTS.md §4.2), so pressing Start
 * changes `service.desiredState` immediately and changes `status.reportedState` not at
 * all - the node picks the new spec up on its next reconcile, up to fifteen seconds
 * later, and only then does a status report come back. Without something like this the
 * customer presses Start, the page says "asked to run, node reports stopped", and stays
 * that way until they reload by hand. That reads as a broken platform, and it is the
 * exact moment when the platform is working.
 *
 * It is a partial reload rather than a stream because there is no status stream to
 * subscribe to: `docs/contracts/pages.md` §7 lists every non-page endpoint the panel
 * offers, and the SSE feeds are deployment logs, container logs, metrics and the
 * terminal. `InertiaPage` honours `X-Inertia-Partial-Data`, so asking for two props costs
 * one query for the service and one for its status - cheaper than the emitter, the
 * heartbeat and the tunnel timeout a fourth stream would need, at the resolution the
 * fifteen-second reconcile loop actually delivers.
 *
 * Two cadences, because polling something that will not change is how a panel burns a
 * customer's data allowance on 4G. While the service is settling - a request in flight, a
 * container being created, intent and fact disagreeing - the answer changes soon and is
 * worth asking for; once it has settled, this is a background check. Neither runs while
 * the tab is hidden, and coming back to the tab asks immediately rather than waiting out
 * the rest of an interval.
 */
const SETTLING_MS = 6_000

const STEADY_MS = 30_000

/** The props a service screen re-reads. Everything else on the page is already right. */
const LIVE_PROPS = ['service', 'status']

export function useLiveServiceStatus(settling: boolean, enabled = true): void {
  useEffect(() => {
    if (!enabled) {
      return
    }

    const ask = () => {
      if (document.visibilityState === 'visible') {
        router.reload({only: LIVE_PROPS})
      }
    }
    const timer = window.setInterval(ask, settling ? SETTLING_MS : STEADY_MS)

    // Coming back to a tab that has been in the background for ten minutes should not
    // show ten-minute-old state for another thirty seconds.
    const onVisibilityChange = () => ask()
    document.addEventListener('visibilitychange', onVisibilityChange)

    return () => {
      window.clearInterval(timer)
      document.removeEventListener('visibilitychange', onVisibilityChange)
    }
  }, [enabled, settling])
}
