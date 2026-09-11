import {isArchiveName, isEditable} from './fileKinds'
import type {FileEntryView} from './fileTypes'

/**
 * What a customer can do to one entry, and which of those apply to it.
 *
 * One list, used by both shapes of the file list: the swipe actions and the overflow
 * sheet on a phone, the row menu on a desktop table. Deriving it once is what stops the
 * two disagreeing about whether a read-only root offers Delete - which is the kind of
 * difference nobody notices until somebody taps it on a phone and gets a flash message
 * they cannot explain.
 *
 * Every entry here maps to something the panel really does: the six form posts in
 * `FileMutationController`, the download in `FileTransferController`, the size walk in
 * `FileBrowseController`, and the editor's read. Nothing is offered that has no endpoint.
 */
export type FileActionKind =
  | 'open'
  | 'edit'
  | 'download'
  | 'rename'
  | 'chmod'
  | 'delete'
  | 'compress'
  | 'extract'
  | 'measure'

export interface FileAction {
  kind: FileActionKind
  label: string
  tone: 'neutral' | 'danger'
}

export interface FileActionContext {
  /** False for a viewer, and for a static site's releases tree. */
  canWrite: boolean
  /** `wisper.files.max-editable-bytes`, from the page's props. */
  maxEditableBytes: number
}

/**
 * The actions for one entry, in the order they belong in a menu.
 *
 * Read-only actions first, because they are the ones somebody reaches for most and the
 * ones that are always there. Delete is last and is the only one marked `danger`.
 */
export function actionsFor(entry: FileEntryView, context: FileActionContext): FileAction[] {
  const actions: FileAction[] = []

  if (entry.directory) {
    actions.push({kind: 'open', label: 'Open', tone: 'neutral'})
    actions.push({kind: 'measure', label: 'Measure size', tone: 'neutral'})
  } else if (!entry.symlink) {
    // A symlink is reported and never followed, so downloading one would mean asking the
    // node for whatever it points at - which may not be inside this root at all.
    actions.push({kind: 'download', label: 'Download', tone: 'neutral'})
  }

  if (isEditable(entry, context.maxEditableBytes)) {
    actions.push({kind: 'edit', label: 'Edit', tone: 'neutral'})
  }

  if (context.canWrite) {
    actions.push({kind: 'rename', label: 'Rename or move', tone: 'neutral'})
    actions.push({kind: 'compress', label: 'Compress', tone: 'neutral'})
    if (!entry.directory && isArchiveName(entry.name)) {
      actions.push({kind: 'extract', label: 'Unzip here', tone: 'neutral'})
    }
    actions.push({kind: 'chmod', label: 'Permissions', tone: 'neutral'})
    actions.push({kind: 'delete', label: 'Delete', tone: 'danger'})
  }

  return actions
}

/**
 * The two actions a swipe reveals.
 *
 * A swipe is a shortcut and cannot hold a menu: past two buttons on a 375px screen the
 * row travels further than a thumb does comfortably, and every one of them is in the
 * overflow sheet anyway. Delete and the most useful read action.
 */
export function swipeActionsFor(
  entry: FileEntryView,
  context: FileActionContext,
): FileAction[] {
  const all = actionsFor(entry, context)
  const primary = all.find((action) => action.kind === 'edit' || action.kind === 'download')
  const remove = all.find((action) => action.kind === 'delete')
  return [primary, remove].filter((action): action is FileAction => action !== undefined)
}
