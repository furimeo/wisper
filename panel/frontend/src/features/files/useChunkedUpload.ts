import {useCallback, useEffect, useMemo, useSyncExternalStore} from 'react'

import type {FileEntryView} from './fileTypes'
import {
  cancelUpload,
  clearFinishedUploads,
  currentUploads,
  dismissUpload,
  enqueueUpload,
  enqueueUploadAt,
  onUploadStored,
  resumeUpload,
  subscribeToUploads,
} from './uploadQueue'
import type {UploadItem} from './uploadQueue'

/**
 * The upload queue, bound to the directory that is on screen.
 *
 * The queue itself lives in `uploadQueue.ts` and outlives this component - Inertia
 * remounts a page on every visit, and an upload that died when the customer opened
 * another folder would be the opposite of resumable. What this hook adds is the part that
 * is about *this* screen: which uploads belong to the service being browsed, where a
 * newly picked file should land, and telling the page when the node has actually stored
 * something so the listing can catch up.
 *
 * Filtered by service rather than by folder on purpose. Somebody who uploads a file into
 * `logs/` and then walks up a level has not stopped caring whether it arrived, and a
 * progress row that vanishes on navigation reads as an upload that was cancelled.
 */
export interface ChunkedUpload {
  /** Everything being uploaded into this service, newest destination first. */
  items: UploadItem[]
  /** At least one file is queued or in flight. */
  busy: boolean
  /** How far through the whole queue, 0-100, or null when nothing is running. */
  percent: number | null
  /** Queue files into the directory currently being browsed. */
  add: (files: Iterable<File>) => void
  /** Queue files with explicit relative paths, for folder upload. */
  addWithPaths: (files: Iterable<{file: File; relativePath: string}>) => void
  cancel: (id: string) => void
  resume: (id: string) => void
  dismiss: (id: string) => void
  clearFinished: () => void
}

export function useChunkedUpload(
  serviceId: string,
  rootId: string,
  directory: string,
  onStored?: (entry: FileEntryView) => void,
): ChunkedUpload {
  const all = useSyncExternalStore(subscribeToUploads, currentUploads, currentUploads)

  const items = useMemo(
    () => all.filter((item) => item.serviceId === serviceId),
    [all, serviceId],
  )

  useEffect(() => {
    if (!onStored) {
      return
    }
    return onUploadStored((entry, storedService, storedRoot) => {
      // Only what landed in the tree being looked at: another service's upload finishing
      // is not a reason to re-query this directory.
      if (storedService === serviceId && storedRoot === rootId) {
        onStored(entry)
      }
    })
  }, [serviceId, rootId, onStored])

  const add = useCallback(
    (files: Iterable<File>) => {
      for (const file of files) {
        enqueueUpload(serviceId, rootId, directory, file)
      }
    },
    [serviceId, rootId, directory],
  )

  const addWithPaths = useCallback(
    (files: Iterable<{file: File; relativePath: string}>) => {
      for (const {file, relativePath} of files) {
        const path = directory ? `${directory}/${relativePath}` : relativePath
        enqueueUploadAt(serviceId, rootId, path, file)
      }
    },
    [serviceId, rootId, directory],
  )

  // Anything that has not reached a terminal state keeps the bar alive. An interrupted
  // upload is still pending: the customer expects to see it carrying on, not for the bar
  // to vanish the moment a flaky connection parks a chunk. Only `done`, `failed` and
  // `cancelled` are over.
  const busy = items.some(
    (item) => item.status === 'queued' || item.status === 'uploading' || item.status === 'interrupted',
  )

  const percent = useMemo(() => {
    // The whole queue counts, including interrupted: a file that has 240 MB on the node
    // is 240 MB through, whether the radio dropped a second ago or not. Failed and
    // cancelled files are excluded so a refusal does not drag the bar down.
    const active = items.filter(
      (item) => item.status !== 'cancelled' && item.status !== 'failed',
    )
    const total = active.reduce((sum, item) => sum + item.totalBytes, 0)
    if (!busy || total <= 0) {
      return null
    }
    const sent = active.reduce((sum, item) => sum + item.sentBytes, 0)
    return Math.min(100, Math.round((sent / total) * 100))
  }, [items, busy])

  return {
    items,
    busy,
    percent,
    add,
    addWithPaths,
    cancel: cancelUpload,
    resume: resumeUpload,
    dismiss: dismissUpload,
    clearFinished: clearFinishedUploads,
  }
}
