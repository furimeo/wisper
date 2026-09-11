import type {FileEntryView} from './fileTypes'

/**
 * What kind of thing an entry is, judged from its name.
 *
 * Only ever used to decide what to draw and which actions to offer - the icon beside a
 * row, whether "Unzip here" appears, whether the inline editor is worth suggesting. The
 * node decides what a file actually is; a customer who names a tarball `notes.txt` gets a
 * wrong icon and nothing worse than that.
 *
 * Extension matching is deliberately shallow. A table of four hundred suffixes would be
 * mostly wrong within a year, and the six buckets below are the ones that change what a
 * person can *do* with the file in this panel.
 */
export type FileCategory = 'folder' | 'archive' | 'image' | 'code' | 'text' | 'binary'

const ARCHIVE = new Set([
  'zip',
  'tar',
  'tgz',
  'gz',
  'bz2',
  'xz',
  'zst',
  '7z',
  'rar',
])

const IMAGE = new Set(['png', 'jpg', 'jpeg', 'gif', 'webp', 'avif', 'svg', 'ico', 'bmp'])

const CODE = new Set([
  'js',
  'jsx',
  'mjs',
  'cjs',
  'ts',
  'tsx',
  'json',
  'css',
  'scss',
  'less',
  'html',
  'htm',
  'xml',
  'py',
  'rb',
  'go',
  'rs',
  'java',
  'kt',
  'php',
  'sh',
  'bash',
  'sql',
  'yml',
  'yaml',
  'toml',
])

const TEXT = new Set(['txt', 'md', 'markdown', 'log', 'csv', 'tsv', 'ini', 'conf', 'env'])

/**
 * Names with no extension that are still plainly text.
 *
 * A container volume is full of these and treating `Dockerfile` as a binary blob would
 * hide the editor on the one file somebody opened the file manager to change.
 */
const KNOWN_TEXT_NAMES = new Set([
  'dockerfile',
  'makefile',
  'procfile',
  'caddyfile',
  'readme',
  'license',
  'changelog',
  '.env',
  '.gitignore',
  '.dockerignore',
  '.npmrc',
  '.editorconfig',
])

/** The suffix after the last dot, lower-cased, or the empty string. */
export function extensionOf(name: string): string {
  const dot = name.lastIndexOf('.')
  if (dot <= 0 || dot === name.length - 1) {
    return ''
  }
  return name.slice(dot + 1).toLowerCase()
}

export function categoryOf(entry: FileEntryView): FileCategory {
  if (entry.directory) {
    return 'folder'
  }
  const name = entry.name.toLowerCase()
  const extension = extensionOf(name)
  if (isArchiveName(entry.name)) {
    return 'archive'
  }
  if (IMAGE.has(extension)) {
    return 'image'
  }
  if (CODE.has(extension)) {
    return 'code'
  }
  if (TEXT.has(extension) || KNOWN_TEXT_NAMES.has(name)) {
    return 'text'
  }
  return 'binary'
}

/** Whether "Unzip here" is worth offering. `.tar.gz` is two suffixes and still one file. */
export function isArchiveName(name: string): boolean {
  const lower = name.toLowerCase()
  if (lower.endsWith('.tar.gz') || lower.endsWith('.tar.bz2') || lower.endsWith('.tar.xz')) {
    return true
  }
  return ARCHIVE.has(extensionOf(lower))
}

/**
 * Whether the inline editor should be offered for this entry.
 *
 * The size ceiling is the server's - `wisper.files.max-editable-bytes`, passed to the page
 * - because it is the server that decides how much of a file it will read. A symlink is
 * excluded: it is reported and never followed, so editing one would mean editing whatever
 * it points at, which may be outside the root entirely.
 */
export function isEditable(entry: FileEntryView, maxEditableBytes: number): boolean {
  return !entry.directory && !entry.symlink && entry.sizeBytes <= maxEditableBytes
}

/** `rwxr-xr-x`, from the nine permission bits, for a row that has the width for it. */
export function permissionText(mode: number): string {
  const flags = 'rwx'
  let out = ''
  for (let group = 2; group >= 0; group -= 1) {
    const bits = (mode >> (group * 3)) & 0b111
    for (let bit = 0; bit < 3; bit += 1) {
      out += (bits >> (2 - bit)) & 1 ? flags.charAt(bit) : '-'
    }
  }
  return out
}
