import {router} from '@inertiajs/react'
import {useEffect} from 'react'

/**
 * Keeps a node screen's capacity and health current while somebody is watching it.
 *
 * A node reports on its own schedule - a heartbeat, a status batch after each fifteen
 * second reconcile - and the panel has no stream for it: `docs/contracts/pages.md` §7
 * lists every non-page endpoint, and none of them is a fleet feed. So this is a partial
 * reload of exactly the props that change, which `InertiaPage` honours through
 * `X-Inertia-Partial-Data`.
 *
 * Two cadences, because an operator checking the fleet from a phone on mobile data should
 * not pay for a question with a settled answer. A node mid-drain, mid-upgrade or catching
 * up on a generation changes soon; a fleet where everything is converged does not.
 *
 * Nothing runs while the tab is hidden, and returning to the tab asks straight away
 * rather than showing ten-minute-old capacity for the rest of an interval.
 */
const SETTLING_MS = 6_000

const STEADY_MS = 30_000

export function useLiveNodes(props: string[], settling: boolean, enabled = true): void {
  // The array is rebuilt by every render of the caller; its contents are what matter.
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
    const timer = window.setInterval(ask, settling ? SETTLING_MS : STEADY_MS)
    const onVisibilityChange = () => ask()
    document.addEventListener('visibilitychange', onVisibilityChange)

    return () => {
      window.clearInterval(timer)
      document.removeEventListener('visibilitychange', onVisibilityChange)
    }
  }, [enabled, key, settling])
}
