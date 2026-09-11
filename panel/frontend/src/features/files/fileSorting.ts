import type {FileEntryView} from './fileTypes'

/**
 * The order a directory is listed in.
 *
 * Folders come first whatever is being sorted by, in both directions. That is not a
 * stylistic borrowing from every other file manager: a directory's size is unknown until
 * somebody walks it and its modification time is when its contents last changed, so
 * interleaving folders into a size or date ranking sorts them by values that do not mean
 * the same thing as the files' ones. Keeping them in their own block above is the only
 * honest arrangement, and it is also the one that puts the way *out* of a deep tree at the
 * top of the list.
 *
 * The node already returns entries folders-first and then by name, so the default order
 * here costs nothing and matches what arrived. Anything else is applied in the browser and
 * therefore only to the entries that have been fetched - `FileList` says so when a
 * directory has more pages, because a "largest first" that silently ranks the first two
 * hundred of forty thousand entries is worse than no sorting at all.
 */
export type SortKey = 'name' | 'size' | 'modified'

export type SortDirection = 'asc' | 'desc'

export interface SortOrder {
  key: SortKey
  direction: SortDirection
}

/** What the node sent, so the first render re-sorts nothing. */
export const DEFAULT_SORT: SortOrder = {key: 'name', direction: 'asc'}

/**
 * Numeric, case-insensitive, and aware of the locale's alphabet.
 *
 * `numeric` is what puts `backup-9.sql` above `backup-10.sql`, which is the single
 * difference somebody notices between a file list that feels right and one that does not.
 */
const NAMES = new Intl.Collator(undefined, {numeric: true, sensitivity: 'base'})

/**
 * What clicking a column header does.
 *
 * A different column starts ascending, because that is what "sort by size" means before
 * anybody has expressed an opinion; the same column flips. Name is the exception in
 * reverse - a second click on a column already sorted descending returns to ascending
 * rather than to no sorting at all, since "no sorting" is not a state a list can show.
 */
export function nextOrder(current: SortOrder, key: SortKey): SortOrder {
  if (current.key !== key) {
    return {key, direction: 'asc'}
  }
  return {key, direction: current.direction === 'asc' ? 'desc' : 'asc'}
}

/** A new array in the requested order. The input is left alone; React compares by identity. */
export function sortEntries(entries: FileEntryView[], order: SortOrder): FileEntryView[] {
  const sign = order.direction === 'asc' ? 1 : -1
  return [...entries].sort((left, right) => {
    if (left.directory !== right.directory) {
      return left.directory ? -1 : 1
    }
    return sign * compare(left, right, order.key)
  })
}

function compare(left: FileEntryView, right: FileEntryView, key: SortKey): number {
  if (key === 'size') {
    const bySize = left.sizeBytes - right.sizeBytes
    // Two files of the same size are still two files, and a list whose order changes
    // between renders is a list whose checkboxes land on the wrong row.
    return bySize === 0 ? NAMES.compare(left.name, right.name) : bySize
  }
  if (key === 'modified') {
    const byTime = timeOf(left) - timeOf(right)
    return byTime === 0 ? NAMES.compare(left.name, right.name) : byTime
  }
  return NAMES.compare(left.name, right.name)
}

/**
 * The modification time as a number.
 *
 * `modifiedAt` is null for an entry the node could not stat, and those sort as the oldest
 * thing in the directory rather than being scattered by `NaN` - a comparator that returns
 * `NaN` produces an order that depends on the sort algorithm's pivots.
 */
function timeOf(entry: FileEntryView): number {
  if (entry.modifiedAt === null) {
    return 0
  }
  const parsed = Date.parse(entry.modifiedAt)
  return Number.isNaN(parsed) ? 0 : parsed
}
