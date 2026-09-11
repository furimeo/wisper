/**
 * The upload session id, remembered next to the file it belongs to.
 *
 * `files.proto` puts the session id in the browser's hands precisely so a resume can
 * survive more than a dropped socket. Held only in React state it survives a lost
 * connection and nothing else; held here it also survives the tab being reloaded, the
 * phone locking until Safari discards the page, and the panel restarting - the customer
 * picks the same file again and the upload carries on from the first gap instead of from
 * zero, which on a 300 MB file over 4G is the difference between annoying and unusable.
 *
 * The key is what makes that safe: the same name, the same size and the same modification
 * time, in the same destination directory of the same root of the same service. Anything
 * else is a different upload and gets a different id. Reusing an id across two different
 * files would append one to the other's parts, which is worse than starting again.
 *
 * Entries expire, because the node sweeps abandoned parts after `wisper.files.orphan-chunk-ttl`
 * - a day by default - and an id whose parts have been swept is an id that makes a resume
 * silently restart. Twelve hours is comfortably inside that.
 */

const PREFIX = 'wisper.upload.'

const MAX_AGE_MS = 12 * 3600_000

interface StoredSession {
  sessionId: string
  storedAt: number
}

/** Everything that has to match for two picks to be the same upload. */
export interface UploadIdentity {
  serviceId: string
  rootId: string
  /** The destination path, relative to the root, including the file name. */
  path: string
  sizeBytes: number
  lastModified: number
}

/**
 * The id for this file, reused when there is one to reuse.
 *
 * Minting is `crypto.randomUUID`, which fits the panel's `[A-Za-z0-9_-]{8,64}` rule
 * because a UUID is hex and dashes. The fallback matters on a page served over plain
 * HTTP, where `randomUUID` is not exposed: `getRandomValues` still is.
 */
export function sessionIdFor(identity: UploadIdentity): string {
  const key = keyOf(identity)
  const existing = read(key)
  if (existing) {
    return existing
  }
  const minted = mintId()
  write(key, minted)
  return minted
}

/** Called when the upload finished or was abandoned: the id will never resume again. */
export function forgetSession(identity: UploadIdentity): void {
  try {
    window.localStorage.removeItem(keyOf(identity))
  } catch {
    // Private mode, or storage disabled. The upload works; only the resume across a
    // reload does not, and there is nothing useful to tell the customer about that.
  }
}

function keyOf(identity: UploadIdentity): string {
  return (
    PREFIX +
    [
      identity.serviceId,
      identity.rootId,
      identity.path,
      identity.sizeBytes,
      identity.lastModified,
    ].join('|')
  )
}

function read(key: string): string | null {
  try {
    const raw = window.localStorage.getItem(key)
    if (!raw) {
      return null
    }
    const stored = JSON.parse(raw) as Partial<StoredSession>
    if (typeof stored.sessionId !== 'string' || typeof stored.storedAt !== 'number') {
      return null
    }
    if (Date.now() - stored.storedAt > MAX_AGE_MS) {
      window.localStorage.removeItem(key)
      return null
    }
    return stored.sessionId
  } catch {
    return null
  }
}

function write(key: string, sessionId: string): void {
  const entry: StoredSession = {sessionId, storedAt: Date.now()}
  try {
    window.localStorage.setItem(key, JSON.stringify(entry))
  } catch {
    // Quota, or storage disabled. Nothing here is required for the upload to run.
  }
}

function mintId(): string {
  const random = globalThis.crypto
  if (random && typeof random.randomUUID === 'function') {
    return random.randomUUID()
  }
  const bytes = new Uint8Array(16)
  if (random && typeof random.getRandomValues === 'function') {
    random.getRandomValues(bytes)
  } else {
    for (let index = 0; index < bytes.length; index += 1) {
      bytes[index] = Math.floor(Math.random() * 256)
    }
  }
  let out = ''
  for (const byte of bytes) {
    out += byte.toString(16).padStart(2, '0')
  }
  return out
}
