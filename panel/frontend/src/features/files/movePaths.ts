import {router} from '@inertiajs/react'

import {filesBase} from './fileRequests'

/**
 * Moves entries into a folder, one request at a time.
 *
 * `POST /services/{id}/files/rename` takes one `from` and one `to`, and that is the right
 * shape for it: every move is audited on its own, and a batch endpoint would have to
 * invent an answer for "four of these six worked". So moving a multi-selection is a
 * sequence of ordinary writes, awaited one after another - Inertia cancels an in-flight
 * visit when a new one starts, so firing them together would move the last one and
 * silently drop the rest.
 *
 * The destination is a directory and the name is kept. A move that also renamed would be
 * a different operation with a different dialog, and `RenameDialog` is that operation.
 *
 * The count of what actually moved is returned, because the server's own flash message
 * only ever describes the last one.
 */
export async function movePaths(
  serviceId: string,
  rootId: string,
  paths: string[],
  destination: string,
  overwrite: boolean,
): Promise<number> {
  let moved = 0
  for (const from of paths) {
    const name = from.split('/').pop() ?? from
    const to = destination === '' ? name : `${destination}/${name}`
    if (to === from) {
      continue
    }
    if (await post(serviceId, rootId, from, to, overwrite)) {
      moved += 1
    }
  }
  return moved
}

/** Renames one entry in place: the same endpoint, with the folder left alone. */
export function renamePath(
  serviceId: string,
  rootId: string,
  from: string,
  name: string,
  overwrite: boolean,
): Promise<boolean> {
  const parent = from.includes('/') ? from.slice(0, from.lastIndexOf('/')) : ''
  const to = parent === '' ? name : `${parent}/${name}`
  return post(serviceId, rootId, from, to, overwrite)
}

function post(
  serviceId: string,
  rootId: string,
  from: string,
  to: string,
  overwrite: boolean,
): Promise<boolean> {
  return new Promise((resolve) => {
    let succeeded = false
    router.post(
      `${filesBase(serviceId)}/rename`,
      {rootId, from, to, overwrite},
      {
        // The listing underneath refreshes while the dialog that started this stays up,
        // so a failure lands on the form the customer is still looking at.
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
