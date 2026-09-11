import {router} from '@inertiajs/react'
import {useEffect} from 'react'

/**
 * Follows a restore, or a snapshot being written, without a second streaming surface.
 *
 * `RestoreRunController` says why this is a poll rather than SSE: a restore emits tens of
 * lines over minutes, not the thousands a build emits over the same period, so the seam
 * stays one ordinary GET instead of an emitter, a heartbeat and a tunnel timeout to
 * operate for a screen most customers see twice a year.
 *
 * It stops the moment nothing is moving. A finished restore's page is a record, and a
 * record does not need re-fetching every four seconds - which matters here because this is
 * the screen somebody leaves open on a phone while they wait.
 */
const MOVING_MS = 4_000

const STEADY_MS = 60_000

export function useLiveRestores(props: string[], moving: boolean, enabled = true): void {
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
    const timer = window.setInterval(ask, moving ? MOVING_MS : STEADY_MS)
    const onVisibilityChange = () => ask()
    document.addEventListener('visibilitychange', onVisibilityChange)

    return () => {
      window.clearInterval(timer)
      document.removeEventListener('visibilitychange', onVisibilityChange)
    }
  }, [enabled, key, moving])
}
