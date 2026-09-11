import {csrfHeaders} from '@/shell'

import type {
  ChunkReceipt,
  DirectoryPage,
  DirectorySizeView,
  FileEntryView,
  FileText,
  UploadProgress,
} from './fileTypes'

/**
 * The file manager's requests that are not Inertia visits.
 *
 * Six of the fourteen file operations answer JSON to `fetch` rather than rendering a
 * page, and every one of them is here so the components stay about what they draw.
 * Paging a directory, measuring a folder, opening a file in the editor and the five
 * upload calls all happen inside a page that is already on screen - answering them with a
 * full page render would push a history entry, re-run the shared props and throw away the
 * customer's scroll position, which on a phone is most of what they were looking at.
 *
 * Everything that changes the tree - mkdir, rename, delete, chmod, archive, extract, the
 * editor's save - is an Inertia `router.post` at its call site instead, because those
 * redirect and carry a flash message back (`docs/contracts/panel-http.md`). The one
 * exception is the upload, which is a series of XHRs by necessity and is still CSRF
 * protected: `SecurityConfig` exempts only `/api/**` and `/webhooks/**`.
 */

/** A request the panel refused or could not answer, with the sentence it sent. */
export class FileRequestFailed extends Error {
  readonly status: number

  constructor(status: number, message: string) {
    super(message)
    this.name = 'FileRequestFailed'
    this.status = status
  }
}

/** The prefix every file endpoint for one service hangs off. */
export function filesBase(serviceId: string): string {
  return `/services/${serviceId}/files`
}

/** Where a directory lives as a page URL, for a link or an Inertia visit. */
export function browseHref(
  serviceId: string,
  rootId: string,
  path: string,
  hidden: boolean,
): string {
  const query = new URLSearchParams({rootId, path})
  if (hidden) {
    query.set('hidden', 'true')
  }
  return `${filesBase(serviceId)}?${query.toString()}`
}

/** A download is a plain navigation: the response is `Content-Disposition: attachment`. */
export function downloadHref(serviceId: string, rootId: string, path: string): string {
  const query = new URLSearchParams({rootId, path})
  return `${filesBase(serviceId)}/download?${query.toString()}`
}

/** One page of a directory. `cursor` comes from the previous page's `nextCursor`. */
export function listDirectory(
  serviceId: string,
  rootId: string,
  path: string,
  cursor: string | null,
  hidden: boolean,
  signal?: AbortSignal,
): Promise<DirectoryPage> {
  const query = new URLSearchParams({rootId, path, hidden: String(hidden)})
  if (cursor) {
    query.set('cursor', cursor)
  }
  return readJson<DirectoryPage>(`${filesBase(serviceId)}/list?${query.toString()}`, {signal})
}

/** How big a folder is. Its own request because it walks the tree. */
export function measureDirectory(
  serviceId: string,
  rootId: string,
  path: string,
  signal?: AbortSignal,
): Promise<DirectorySizeView> {
  const query = new URLSearchParams({rootId, path})
  return readJson<DirectorySizeView>(`${filesBase(serviceId)}/size?${query.toString()}`, {signal})
}

/** The beginning of a file, as text, for the inline editor. */
export function readFileText(
  serviceId: string,
  rootId: string,
  path: string,
  signal?: AbortSignal,
): Promise<FileText> {
  const query = new URLSearchParams({rootId, path})
  return readJson<FileText>(`${filesBase(serviceId)}/content?${query.toString()}`, {signal})
}

/**
 * Opens or reopens an upload session.
 *
 * Called again after every interruption, including one that restarted the panel: the
 * answer says what the node already holds, and the client carries on from `nextOffset`
 * rather than from zero.
 */
export function openUpload(
  serviceId: string,
  rootId: string,
  sessionId: string,
  path: string,
  totalBytes: number,
  overwrite: boolean,
): Promise<UploadProgress> {
  return postForm<UploadProgress>(`${filesBase(serviceId)}/uploads`, {
    rootId,
    sessionId,
    path,
    totalBytes: String(totalBytes),
    overwrite: String(overwrite),
  })
}

/** The cheap poll: what the node has, without resending the metadata. */
export function uploadState(
  serviceId: string,
  rootId: string,
  sessionId: string,
): Promise<UploadProgress> {
  const query = new URLSearchParams({rootId})
  return readJson<UploadProgress>(
    `${filesBase(serviceId)}/uploads/${encodeURIComponent(sessionId)}?${query.toString()}`,
  )
}

/**
 * One chunk.
 *
 * The offset is never sent - the node derives it from `index`, because an upload that
 * trusts a client-supplied offset can be made to write anywhere in the file.
 */
export function sendChunk(
  serviceId: string,
  rootId: string,
  sessionId: string,
  index: number,
  sha256: string | null,
  chunk: Blob,
  signal?: AbortSignal,
): Promise<ChunkReceipt> {
  const body = new FormData()
  body.append('rootId', rootId)
  body.append('index', String(index))
  if (sha256) {
    body.append('sha256', sha256)
  }
  body.append('chunk', chunk, 'chunk')
  return send<ChunkReceipt>(
    `${filesBase(serviceId)}/uploads/${encodeURIComponent(sessionId)}/chunks`,
    body,
    signal,
  )
}

/** Assemble the parts, verify, move into place. Until this succeeds there is no file. */
export function completeUpload(
  serviceId: string,
  rootId: string,
  sessionId: string,
): Promise<FileEntryView> {
  return postForm<FileEntryView>(
    `${filesBase(serviceId)}/uploads/${encodeURIComponent(sessionId)}/complete`,
    {rootId},
  )
}

/** The customer cancelled. Drops the parts now rather than leaving them against quota. */
export function abortUpload(
  serviceId: string,
  rootId: string,
  sessionId: string,
): Promise<UploadProgress> {
  return postForm<UploadProgress>(
    `${filesBase(serviceId)}/uploads/${encodeURIComponent(sessionId)}/abort`,
    {rootId},
  )
}

function postForm<T>(url: string, fields: Record<string, string>): Promise<T> {
  const body = new FormData()
  for (const [name, value] of Object.entries(fields)) {
    body.append(name, value)
  }
  return send<T>(url, body)
}

async function send<T>(url: string, body: FormData, signal?: AbortSignal): Promise<T> {
  const response = await fetch(url, {
    method: 'POST',
    // The controllers read `@RequestParam`, which the container fills from a parsed form
    // body. No `Content-Type` header: the browser has to add the multipart boundary.
    headers: csrfHeaders({Accept: 'application/json'}),
    credentials: 'same-origin',
    body,
    signal,
  })
  return unwrap<T>(response)
}

async function readJson<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, {
    ...init,
    headers: {Accept: 'application/json'},
    credentials: 'same-origin',
  })
  return unwrap<T>(response)
}

/**
 * The body, or the failure as a sentence somebody can act on.
 *
 * A refused file operation answers with the panel's error page, which is HTML - so the
 * status is read first and the body is only parsed when it claims to be JSON. Without
 * that a 403 surfaces as "Unexpected token <", which tells the customer nothing and sends
 * whoever reads the report looking for a parser bug.
 */
async function unwrap<T>(response: Response): Promise<T> {
  if (response.ok) {
    return (await response.json()) as T
  }
  throw new FileRequestFailed(response.status, await failureMessage(response))
}

async function failureMessage(response: Response): Promise<string> {
  if (response.status === 401 || response.status === 419) {
    return 'Your session has expired. Sign in again and the panel will pick up where it was.'
  }
  if (response.status === 403) {
    return 'The panel refused that. Your role may have changed while this page was open.'
  }
  if (response.status === 404) {
    return 'That is not there any more. Reload the folder to see what it holds now.'
  }
  const type = response.headers.get('Content-Type') ?? ''
  if (type.includes('application/json')) {
    try {
      const body = (await response.json()) as {message?: unknown; reason?: unknown}
      const message = typeof body.message === 'string' ? body.message : null
      const reason = typeof body.reason === 'string' ? body.reason : null
      if (message || reason) {
        return message ?? reason ?? ''
      }
    } catch {
      // Fall through to the generic sentence: an unparseable error body is still an error.
    }
  }
  return `The panel answered ${response.status}. Nothing was changed.`
}
