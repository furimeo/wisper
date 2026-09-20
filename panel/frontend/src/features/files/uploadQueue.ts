import {FileRequestFailed, abortUpload, completeUpload, openUpload, sendChunk} from './fileRequests'
import type {FileEntryView} from './fileTypes'
import {forgetSession, sessionIdFor} from './resumableSessions'
import type {UploadIdentity} from './resumableSessions'
import {sha256Hex} from './uploadChecksum'

/**
 * The uploads this browser is running, and the loop that runs them.
 *
 * A module-level store rather than component state, for one concrete reason: Inertia
 * remounts the page component on every visit that does not ask to preserve state - its
 * React adapter keys the page on `Date.now()` - so an upload owned by `FileManagerPage`
 * would be killed the moment the customer tapped into another folder to see where their
 * last file went. Holding it here means an upload survives navigation, exactly as it
 * survives a dropped connection.
 *
 * The protocol is `docs/contracts/pages.md` §7: open a session, send chunks by index,
 * complete. Three properties matter more than the code that implements them.
 *
 * **The offset comes from the node, never from the client.** Each acknowledgement says
 * where the next chunk goes; a resume asks the node what it has rather than assuming the
 * parts it sent arrived. That is what makes an upload interrupted at 240 MB continue at
 * 240 MB after the phone comes out of a lift.
 *
 * **A refusal and an interruption are different states.** A 413 or a 403 will answer the
 * same way forever, so the file is marked failed and says why. A dropped socket, a 502
 * from the tunnel or a phone with no signal is `interrupted`: it retries with backoff,
 * and when the browser comes back online every interrupted upload is picked up again
 * without the customer touching anything.
 *
 * **Nothing is held in memory that is not being sent.** One chunk at a time per file,
 * two files at a time, and the chunk is released as soon as it is acknowledged. A phone
 * cannot hold a 2 GB file, and the reason the panel chunks at all is that it must not try.
 *
 * Over three hundred lines, deliberately (AGENTS.md §3.2). The checksum, the remembered
 * session id and the React binding are already separate files; what remains is one state
 * machine, and a queue whose scheduler, retry policy and progress live in three files is
 * a queue whose invariants are enforced in none of them.
 */
export type UploadStatus =
  | 'queued'
  | 'uploading'
  | 'interrupted'
  | 'failed'
  | 'done'
  | 'cancelled'

export interface UploadItem {
  /** The session id, minted by the browser and reused by a resume. */
  id: string
  serviceId: string
  rootId: string
  name: string
  /** Where the finished file goes, relative to the root. */
  path: string
  totalBytes: number
  sentBytes: number
  status: UploadStatus
  /** Why it stopped, in a sentence. Null while nothing has gone wrong. */
  message: string | null
  /** The node already held part of this file when the session opened. */
  resumed: boolean
}

/** At most two at once: a phone's radio and its memory are both the limit here. */
const MAX_ACTIVE = 2

/** Attempts per request before a file is parked as interrupted. */
const ATTEMPTS = 4

const BASE_BACKOFF_MS = 800

interface Job {
  item: UploadItem
  file: File
  identity: UploadIdentity
  controller: AbortController
  cancelled: boolean
  running: boolean
}

const jobs = new Map<string, Job>()
const listeners = new Set<() => void>()
const stored = new Set<(entry: FileEntryView, serviceId: string, rootId: string) => void>()

let snapshot: UploadItem[] = []
let active = 0

/** Cancelled by the customer. Not an error worth reporting anywhere. */
class UploadCancelled extends Error {}

/** The network gave up on this attempt. It will be tried again. */
class UploadInterrupted extends Error {}

export function subscribeToUploads(listener: () => void): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}

/** A stable array, so `useSyncExternalStore` does not re-render on every tick. */
export function currentUploads(): UploadItem[] {
  return snapshot
}

/** Called with each file the node has actually stored, so a listing can refresh. */
export function onUploadStored(
  listener: (entry: FileEntryView, serviceId: string, rootId: string) => void,
): () => void {
  stored.add(listener)
  return () => {
    stored.delete(listener)
  }
}

/**
 * Queues one file.
 *
 * `directory` is where the customer is browsing; the file keeps that destination even if
 * they navigate somewhere else while it uploads.
 */
export function enqueueUpload(
  serviceId: string,
  rootId: string,
  directory: string,
  file: File,
): void {
  const path = directory ? `${directory}/${file.name}` : file.name
  enqueueUploadAt(serviceId, rootId, path, file)
}

/**
 * Queues one file at an explicit destination path, which may include subdirectories.
 *
 * Used by folder upload: `path` is `directory/relativePath`, e.g.
 * `myproject/src/main.go`. The node creates every parent directory at completion
 * (`upload_complete.go` calls `MkdirAll` on the parent), so a tree of files sent
 * one at a time reconstructs the folder structure on the other side.
 */
export function enqueueUploadAt(
  serviceId: string,
  rootId: string,
  path: string,
  file: File,
): void {
  const identity: UploadIdentity = {
    serviceId,
    rootId,
    path,
    sizeBytes: file.size,
    lastModified: file.lastModified,
  }
  const id = sessionIdFor(identity)

  const existing = jobs.get(id)
  if (existing && existing.item.status !== 'done' && !existing.cancelled) {
    // The same file picked twice while it is still going. Resuming is what was meant.
    resumeUpload(id)
    return
  }

  jobs.set(id, {
    item: {
      id,
      serviceId,
      rootId,
      name: file.name,
      path,
      totalBytes: file.size,
      sentBytes: 0,
      status: 'queued',
      message: null,
      resumed: false,
    },
    file,
    identity,
    controller: new AbortController(),
    cancelled: false,
    running: false,
  })
  publish()
  pump()
}

/** Stops an upload and drops the parts the node is holding against the customer's quota. */
export function cancelUpload(id: string): void {
  const job = jobs.get(id)
  if (!job || job.item.status === 'done') {
    return
  }
  job.cancelled = true
  job.controller.abort()
  forgetSession(job.identity)
  patch(id, {status: 'cancelled', message: 'Cancelled.'})
  void abortUpload(job.item.serviceId, job.item.rootId, id).catch(() => {
    // The node sweeps orphaned parts on its own TTL. Failing to tell it now is not
    // something to put in front of somebody who has already moved on.
  })
}

/** Picks an interrupted or failed upload back up, from whatever the node already holds. */
export function resumeUpload(id: string): void {
  const job = jobs.get(id)
  if (!job || job.running || job.item.status === 'done') {
    return
  }
  job.cancelled = false
  job.controller = new AbortController()
  patch(id, {status: 'queued', message: null})
  pump()
}

/** Takes one finished row off the list. */
export function dismissUpload(id: string): void {
  const job = jobs.get(id)
  if (!job || job.running) {
    return
  }
  jobs.delete(id)
  publish()
}

/** Clears everything that has stopped, leaving what is still going. */
export function clearFinishedUploads(): void {
  for (const [id, job] of jobs) {
    if (!job.running && job.item.status !== 'queued') {
      jobs.delete(id)
    }
  }
  publish()
}

/**
 * Restarts everything the network stopped.
 *
 * Registered once, at module load, because the browser coming back online is the moment
 * a customer expects their upload to carry on - not the moment they are expected to find
 * a button.
 */
if (typeof window !== 'undefined') {
  window.addEventListener('online', () => {
    for (const [id, job] of jobs) {
      if (job.item.status === 'interrupted') {
        resumeUpload(id)
      }
    }
  })
}

function pump(): void {
  for (const [, job] of jobs) {
    if (active >= MAX_ACTIVE) {
      return
    }
    if (job.item.status === 'queued' && !job.running) {
      job.running = true
      active += 1
      void run(job).finally(() => {
        job.running = false
        active -= 1
        pump()
      })
    }
  }
}

async function run(job: Job): Promise<void> {
  const {serviceId, rootId} = job.item
  patch(job.item.id, {status: 'uploading', message: null})
  try {
    const opened = await attempt(job, () =>
      openUpload(serviceId, rootId, job.item.id, job.item.path, job.file.size, false),
    )
    const chunkSize = opened.chunkSize > 0 ? opened.chunkSize : 8 * 1024 * 1024
    patch(job.item.id, {
      sentBytes: Math.min(opened.receivedBytes, job.file.size),
      resumed: opened.receivedBytes > 0,
    })

    let offset = opened.complete ? job.file.size : opened.nextOffset
    while (offset < job.file.size) {
      if (job.cancelled) {
        throw new UploadCancelled()
      }
      const index = Math.floor(offset / chunkSize)
      const start = index * chunkSize
      const end = Math.min(start + chunkSize, job.file.size)
      const blob = job.file.slice(start, end)
      const sha256 = await sha256Hex(blob)
      const receipt = await attempt(job, () =>
        sendChunk(serviceId, rootId, job.item.id, index, sha256, blob, job.controller.signal),
      )
      patch(job.item.id, {sentBytes: Math.min(receipt.receivedBytes, job.file.size)})
      // Never behind where we already were: the node may tell the client to skip ahead
      // over parts an earlier attempt delivered, and must not be able to stall the loop.
      offset = receipt.nextOffset > offset ? receipt.nextOffset : end
      if (receipt.complete) {
        break
      }
    }

    const entry = await attempt(job, () => completeUpload(serviceId, rootId, job.item.id))
    forgetSession(job.identity)
    patch(job.item.id, {status: 'done', sentBytes: job.file.size, message: null})
    for (const listener of stored) {
      listener(entry, serviceId, rootId)
    }
  } catch (failure) {
    if (job.cancelled || failure instanceof UploadCancelled) {
      patch(job.item.id, {status: 'cancelled', message: 'Cancelled.'})
      return
    }
    if (failure instanceof FileRequestFailed && !isTransient(failure.status)) {
      // The panel refused it, and will refuse it again. Say what it said.
      patch(job.item.id, {status: 'failed', message: failure.message})
      return
    }
    patch(job.item.id, {
      status: 'interrupted',
      message:
        failure instanceof Error && failure.message
          ? failure.message
          : 'The connection dropped. Nothing was lost - this carries on from where it stopped.',
    })
  }
}

/**
 * One request, retried while retrying can help.
 *
 * Offline is not retried in a loop: the `online` listener above restarts the file, which
 * costs nothing and does not keep a timer alive in a backgrounded tab on a phone.
 */
async function attempt<T>(job: Job, call: () => Promise<T>): Promise<T> {
  let last: unknown
  for (let round = 0; round < ATTEMPTS; round += 1) {
    if (job.cancelled) {
      throw new UploadCancelled()
    }
    if (typeof navigator !== 'undefined' && navigator.onLine === false) {
      throw new UploadInterrupted('Waiting for a connection. This will carry on by itself.')
    }
    try {
      return await call()
    } catch (failure) {
      if (job.cancelled) {
        throw new UploadCancelled()
      }
      if (failure instanceof FileRequestFailed && !isTransient(failure.status)) {
        throw failure
      }
      last = failure
      await sleep(BASE_BACKOFF_MS * 2 ** round + Math.random() * 400)
    }
  }
  throw new UploadInterrupted(
    last instanceof FileRequestFailed
      ? last.message
      : 'The connection dropped. Nothing was lost - this carries on from where it stopped.',
  )
}

/** Whether asking again could plausibly answer differently. */
function isTransient(status: number): boolean {
  return status === 408 || status === 429 || status >= 500
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => {
    window.setTimeout(resolve, ms)
  })
}

function patch(id: string, changes: Partial<UploadItem>): void {
  const job = jobs.get(id)
  if (!job) {
    return
  }
  job.item = {...job.item, ...changes}
  publish()
}

function publish(): void {
  snapshot = [...jobs.values()].map((job) => job.item)
  for (const listener of listeners) {
    listener()
  }
}
