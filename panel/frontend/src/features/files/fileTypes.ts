/**
 * The `files` package's records, as they arrive on a page or out of a JSON endpoint.
 *
 * One interface per Java record in `lhqm.furimeo.wisper.files`, components in declaration
 * order, derived accessors marked. `docs/contracts/pages.md` §5 is the source of truth;
 * a field that is not there is not sent, and inventing one here only moves the failure to
 * runtime.
 *
 * Note which derived accessors survive Jackson: a zero-argument `isXxx()` is serialised as
 * `xxx`, so `FileText.isEditable()` arrives as `editable` - but `FileEntryView.isEditable(long)`
 * takes an argument and does not, and neither does `DirectoryPage.hasMore()` or
 * `UploadProgress.nextChunkIndex()`, which are computed here instead.
 */

/** `FileRootRef.FileRootKind`. A workload volume, or a static site's releases tree. */
export type FileRootKind = 'VOLUME' | 'SITE'

/**
 * One directory tree the file manager may operate in.
 *
 * `writable` is false for a site's releases: an edit made in place would be reverted by
 * the next deployment, so the panel refuses it rather than losing the customer's work
 * quietly.
 */
export interface FileRootRef {
  id: string
  kind: FileRootKind
  label: string
  writable: boolean
  /** The node's ceiling for this tree, or zero when it has none. */
  quotaBytes: number
}

/** One directory entry. `mode` is the low nine permission bits only. */
export interface FileEntryView {
  name: string
  path: string
  directory: boolean
  sizeBytes: number
  mode: number
  /** The same bits as the four digits a person types into a chmod field. */
  modeOctal: string
  modifiedAt: string | null
  /** Reported, never followed: a symlink is shown and refused as a target. */
  symlink: boolean
  symlinkTarget: string | null
  uid: number
  gid: number
}

/**
 * One page of a directory.
 *
 * `nextCursor` is empty rather than absent on the last page, which is what
 * `DirectoryPage.of` produces from the node's answer. `total` is the whole directory, so
 * a list can say "showing 200 of 40,312" instead of implying it is complete.
 */
export interface DirectoryPage {
  path: string
  entries: FileEntryView[]
  nextCursor: string | null
  total: number
}

/** What a folder holds, measured on demand because it costs a tree walk. */
export interface DirectorySizeView {
  path: string
  bytes: number
  fileCount: number
  directoryCount: number
  /** The node stopped at its walk budget: the figure is a floor, not an answer. */
  approximate: boolean
  quotaBytes: number
}

/** A file's contents for the inline editor. `editable` is derived from `isEditable()`. */
export interface FileText {
  path: string
  text: string
  byteLength: number
  /** The read hit its ceiling. The editor opens read-only: saving would truncate. */
  truncated: boolean
  editable: boolean
}

/** Half-open, like every other range in this contract. */
export interface ByteRange {
  start: number
  endExclusive: number
}

/**
 * What the node already has of an upload, and where to carry on from.
 *
 * `received` is ranges rather than a count because a dropped connection leaves holes, not
 * a clean prefix. `nextOffset` is the start of the first hole, which is where a resuming
 * client begins.
 */
export interface UploadProgress {
  sessionId: string
  /** False when the node has never heard of this session, or swept it. Start again. */
  known: boolean
  totalBytes: number
  receivedBytes: number
  received: ByteRange[]
  nextOffset: number
  chunkSize: number
  expiresAt: string | null
  complete: boolean
}

/** The node's acknowledgement of one chunk. */
export interface ChunkReceipt {
  sessionId: string
  chunkIndex: number
  receivedBytes: number
  totalBytes: number
  /** Where the node wants the next chunk - not always the end of this one. */
  nextOffset: number
  nextChunkIndex: number
  complete: boolean
}

/** A newly opened shell. `columns` and `rows` are what the PTY took after clamping. */
export interface TerminalView {
  sessionId: string
  containerId: string
  columns: number
  rows: number
}

/**
 * The size frame the panel sends as soon as a socket attaches.
 *
 * Not a page prop and not JSON: it is decoded from a binary frame by `terminalFrames.ts`.
 * It is here because it is the shape of a thing the `files` package sends, which is what
 * this file is for.
 */
export interface TerminalReady {
  columns: number
  rows: number
  containerId: string
}

/** The end frame: the shell is over, and this is what it exited with. */
export interface TerminalExit {
  code: number
  reason: string
}
