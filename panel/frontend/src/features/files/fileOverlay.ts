import type {FileEntryView} from './fileTypes'

/**
 * Which dialog is up.
 *
 * <p>One value rather than a boolean per dialog, because two of them on screen at once is
 * never right and a set of independent flags makes that state reachable. The payload rides
 * along with the tag, so a rename dialog cannot be open without the entry it is renaming.
 *
 * <p>Its own file so that `FileManagerPage` and `FileOverlays` can share it without either
 * importing the other: the page owns the state, the overlays read it.
 */
export type Overlay =
  | {kind: 'none'}
  | {kind: 'newFolder'}
  | {kind: 'upload'}
  | {kind: 'rename'; entry: FileEntryView}
  | {kind: 'move'; entries: FileEntryView[]}
  | {kind: 'chmod'; entry: FileEntryView}
  | {kind: 'compress'; paths: string[]}
  | {kind: 'extract'; entry: FileEntryView}
  | {kind: 'measure'; path: string}
  | {kind: 'edit'; entry: FileEntryView}
