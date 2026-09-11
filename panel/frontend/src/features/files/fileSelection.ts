import {useCallback, useMemo, useState} from 'react'

import type {FileEntryView} from './fileTypes'

/**
 * What is ticked, and which row the keyboard is on.
 *
 * Two pieces of state that look separate and are not. Shift-click needs an anchor, the
 * anchor is the last row touched, and the last row touched is also the row Enter opens and
 * F2 renames - so a file manager that keeps a "selection" and a "focused row" in different
 * places ends up with a shift-click that extends from somewhere the customer is not
 * looking. One hook owns both.
 *
 * Selection is held as paths rather than indices. The list underneath is re-sorted by a
 * column header, extended by "load more" and replaced wholesale by the redirect after a
 * delete; an index survives none of those and a path survives all of them. Entries that
 * have gone are dropped on the next render simply by not being found, which is why nothing
 * here has to be told that a file was deleted.
 */
export interface FileSelection {
  paths: ReadonlySet<string>
  /** The selected entries, in the order the list is showing them. */
  entries: FileEntryView[]
  count: number
  /** The row the keyboard is on, as an index into the ordered list. -1 when none. */
  cursor: number
  /** True when the list is non-empty and every row of it is ticked. */
  all: boolean
  isSelected: (path: string) => boolean
  /** A plain click or a tap: this row alone, and the anchor moves here. */
  select: (index: number) => void
  /** A checkbox, or Ctrl/Cmd-click: add or remove one row without disturbing the rest. */
  toggle: (index: number) => void
  /** Shift-click, or Shift with an arrow key: everything between the anchor and here. */
  extendTo: (index: number) => void
  selectAll: () => void
  clear: () => void
  /** Moves the keyboard row without changing what is ticked. */
  moveCursor: (delta: number) => void
  setCursor: (index: number) => void
}

export function useFileSelection(ordered: FileEntryView[]): FileSelection {
  const [paths, setPaths] = useState<ReadonlySet<string>>(() => new Set())
  const [cursor, setCursorIndex] = useState(-1)
  // Where a shift-range starts. Not the same as the cursor once a range has been dragged
  // out: extending twice from one anchor must not accumulate.
  const [anchor, setAnchor] = useState(-1)

  const entries = useMemo(
    () => ordered.filter((entry) => paths.has(entry.path)),
    [ordered, paths],
  )

  const setCursor = useCallback((index: number) => {
    setCursorIndex(index)
    setAnchor(index)
  }, [])

  const select = useCallback(
    (index: number) => {
      const entry = ordered[index]
      if (!entry) {
        return
      }
      setPaths(new Set([entry.path]))
      setCursorIndex(index)
      setAnchor(index)
    },
    [ordered],
  )

  const toggle = useCallback(
    (index: number) => {
      const entry = ordered[index]
      if (!entry) {
        return
      }
      setPaths((current) => {
        const next = new Set(current)
        if (next.has(entry.path)) {
          next.delete(entry.path)
        } else {
          next.add(entry.path)
        }
        return next
      })
      setCursorIndex(index)
      setAnchor(index)
    },
    [ordered],
  )

  const extendTo = useCallback(
    (index: number) => {
      if (index < 0 || index >= ordered.length) {
        return
      }
      // A shift-click with nothing selected yet behaves as a plain click, which is what
      // every list in every operating system does and what stops the range starting at
      // whichever row happened to be first.
      const from = anchor === -1 ? index : anchor
      const [start, end] = from <= index ? [from, index] : [index, from]
      const next = new Set<string>()
      for (let at = start; at <= end; at += 1) {
        const entry = ordered[at]
        if (entry) {
          next.add(entry.path)
        }
      }
      setPaths(next)
      setCursorIndex(index)
      setAnchor(from)
    },
    [anchor, ordered],
  )

  const selectAll = useCallback(() => {
    setPaths(new Set(ordered.map((entry) => entry.path)))
    if (ordered.length > 0 && anchor === -1) {
      setAnchor(0)
    }
  }, [ordered, anchor])

  const clear = useCallback(() => {
    setPaths(new Set())
  }, [])

  const moveCursor = useCallback(
    (delta: number) => {
      if (ordered.length === 0) {
        return
      }
      // From nowhere, Down goes to the top and Up goes to the bottom. Anything else makes
      // the first arrow press after opening a folder do nothing visible.
      const from = cursor === -1 ? (delta > 0 ? -1 : ordered.length) : cursor
      const next = Math.min(ordered.length - 1, Math.max(0, from + delta))
      setCursorIndex(next)
      setAnchor(next)
    },
    [cursor, ordered.length],
  )

  return {
    paths,
    entries,
    count: paths.size,
    cursor: cursor < ordered.length ? cursor : -1,
    all: ordered.length > 0 && ordered.every((entry) => paths.has(entry.path)),
    isSelected: (path) => paths.has(path),
    select,
    toggle,
    extendTo,
    selectAll,
    clear,
    moveCursor,
    setCursor,
  }
}
