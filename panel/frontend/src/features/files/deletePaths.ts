import {router} from '@inertiajs/react'

import {filesBase} from './fileRequests'

/**
 * Deletes one path, or several, one request at a time.
 *
 * `POST /services/{id}/files/delete` takes a single path, and that is the right shape for
 * it: every delete is audited on its own, and a batch endpoint would have to invent an
 * answer for "four of these six worked". So a multi-select delete is a sequence of
 * ordinary writes, awaited one after another - Inertia cancels an in-flight visit when a
 * new one starts, so firing them together would delete the last one and silently drop the
 * rest.
 *
 * Each visit preserves component state, so the selection bar and any open dialog survive
 * while the listing underneath refreshes. The count of what actually went is returned, and
 * the caller says so: the server's own flash message only ever describes the last one.
 */
export async function deletePaths(
  serviceId: string,
  rootId: string,
  paths: string[],
  recursive: boolean,
): Promise<number> {
  let deleted = 0
  for (const path of paths) {
    const ok = await postDelete(serviceId, rootId, path, recursive)
    if (ok) {
      deleted += 1
    }
  }
  return deleted
}

function postDelete(
  serviceId: string,
  rootId: string,
  path: string,
  recursive: boolean,
): Promise<boolean> {
  return new Promise((resolve) => {
    let succeeded = false
    router.post(
      `${filesBase(serviceId)}/delete`,
      {rootId, path, recursive},
      {
        preserveState: true,
        preserveScroll: true,
        onSuccess: () => {
          succeeded = true
        },
        onFinish: () => resolve(succeeded),
      },
    )
  })
}
