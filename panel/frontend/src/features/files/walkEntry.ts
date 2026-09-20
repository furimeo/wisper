/**
 * Walks a dropped folder into a flat list of files with their relative paths.
 *
 * The drag-and-drop API gives us `FileSystemEntry` objects through
 * `DataTransferItem.webkitGetAsEntry`, and a directory entry has to be read
 * recursively: `createReader().readEntries` delivers children in batches, each
 * child is itself an entry, and a file entry resolves to a `File` through a
 * callback. None of that is Promise-shaped, so this module is the promisification.
 *
 * The relative path is `entry.fullPath` with the leading slash stripped - the
 * browser reports it as `/myproject/src/main.go`, and what the upload queue wants
 * is `myproject/src/main.go` to append to the directory the customer is standing
 * in. The folder the customer dropped becomes a subdirectory of the current one,
 * which is what every file manager does: dropping `myproject/` into `/data`
 * creates `/data/myproject/...`.
 */

/** One file found inside a dropped tree, and where it goes. */
export interface DroppedFile {
  file: File
  /** Path relative to the drop root, e.g. `myproject/src/main.go`. Never empty. */
  relativePath: string
}

/**
 * Walks a single `FileSystemEntry` (file or directory) into a flat file list.
 *
 * Call this with the top-level entry from `webkitGetAsEntry`; it recurses into
 * every directory and returns every file beneath it, in the order the reader
 * yields them.
 */
export async function walkEntry(entry: FileSystemEntry): Promise<DroppedFile[]> {
  if (entry.isFile) {
    const file = await fileOf(entry as FileSystemFileEntry)
    return [{file, relativePath: relativeOf(entry)}]
  }
  if (entry.isDirectory) {
    return walkDirectory(entry as FileSystemDirectoryEntry)
  }
  return []
}

/** Walks an array of top-level entries, collecting every file beneath each. */
export async function walkEntries(entries: FileSystemEntry[]): Promise<DroppedFile[]> {
  const results: DroppedFile[] = []
  for (const entry of entries) {
    const files = await walkEntry(entry)
    results.push(...files)
  }
  return results
}

async function walkDirectory(dir: FileSystemDirectoryEntry): Promise<DroppedFile[]> {
  const reader = dir.createReader()
  const collected: DroppedFile[] = []
  // readEntries delivers a batch (commonly 100 on Chrome), not the whole list.
  // An empty result is the only end signal the API gives.
  let batch: FileSystemEntry[] = []
  do {
    batch = await readEntries(reader)
    for (const child of batch) {
      const files = await walkEntry(child)
      collected.push(...files)
    }
  } while (batch.length > 0)
  return collected
}

function readEntries(reader: FileSystemDirectoryReader): Promise<FileSystemEntry[]> {
  return new Promise((resolve, reject) => {
    reader.readEntries(resolve, reject)
  })
}

function fileOf(entry: FileSystemFileEntry): Promise<File> {
  return new Promise((resolve, reject) => {
    entry.file(resolve, reject)
  })
}

/** `/myproject/src/main.go` → `myproject/src/main.go`. */
function relativeOf(entry: FileSystemEntry): string {
  const path = entry.fullPath.replace(/^\/+/, '')
  if (!path) {
    // A file dropped at the root of the transfer should not be nameless, but
    // the contract is that fullPath always has the name. Fall back rather than
    // enqueue a file with an empty path.
    return (entry as FileSystemFileEntry).name ?? 'file'
  }
  return path
}
