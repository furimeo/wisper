import {t} from '@/i18n'

import {isArchiveName, isEditable} from './fileKinds'
import type {FileEntryView} from './fileTypes'

/**
 * Every operation the file manager has, and whether the current selection suits it.
 *
 * One catalogue, read by the toolbar, by the right-click menu and by the phone's action
 * sheet. Deriving it once is what stops the three disagreeing about whether a read-only
 * tree offers Delete - the kind of difference nobody notices until somebody taps it and
 * gets a flash message they cannot explain.
 *
 * Nothing here is hidden when it does not apply; it is disabled and carries the sentence
 * saying why. A toolbar whose buttons appear and vanish as rows are ticked cannot be
 * learned, because the control somebody is reaching for is never in the same place twice -
 * and "Extract is greyed out because that is not an archive" teaches the tool, while an
 * absent button teaches nothing.
 *
 * Every kind maps to something the panel really does: the six form posts in
 * `FileMutationController`, the download and the editor's read in `FileTransferController`,
 * the size walk in `FileBrowseController`, the upload session endpoints. There is
 * deliberately no Copy: `files.proto` has `MovePath` and no copy operation, so a Copy
 * button could only be a control that does nothing.
 */
export type FileActionKind =
  | 'open'
  | 'edit'
  | 'download'
  | 'newFolder'
  | 'upload'
  | 'rename'
  | 'move'
  | 'compress'
  | 'extract'
  | 'chmod'
  | 'measure'
  | 'delete'
  | 'refresh'

export interface FileActionState {
  kind: FileActionKind
  label: string
  /** Why it is off, as a sentence. Null when it is on. */
  disabledReason: string | null
  tone: 'neutral' | 'danger'
}

export interface FileActionContext {
  /** False for a viewer, and for a static site's releases tree. */
  canWrite: boolean
  /** `wisper.files.max-editable-bytes`, from the page's props. */
  maxEditableBytes: number
  /** The node could not be reached. Only Refresh is worth offering. */
  unavailable: boolean
}

/** Why a whole group of actions is off, or null when they are available. */
function blocked(context: FileActionContext, needsWrite: boolean): string | null {
  if (context.unavailable) {
    return t('files.reasons.unavailable')
  }
  if (needsWrite && !context.canWrite) {
    return t('files.reasons.read_only')
  }
  return null
}

/** Exactly one entry has to be picked, and here is which sentence says so. */
function one(selected: FileEntryView[]): string | null {
  if (selected.length === 0) {
    return t('files.reasons.pick_one')
  }
  if (selected.length > 1) {
    return t('files.reasons.one_at_a_time')
  }
  return null
}

function some(selected: FileEntryView[]): string | null {
  return selected.length === 0 ? t('files.reasons.pick_something') : null
}

/**
 * The state of every action, in the order a toolbar and a menu both want them.
 *
 * The order is: get something new in, act on what is picked, then the two that cost the
 * node real work, then delete on its own at the end. Delete last and alone is not
 * decoration - it is the only irreversible operation on this screen.
 */
export function actionStates(
  selected: FileEntryView[],
  context: FileActionContext,
): FileActionState[] {
  const only = selected.length === 1 ? selected[0] : undefined
  const read = blocked(context, false)
  const write = blocked(context, true)

  return [
    {
      kind: 'newFolder',
      label: t('files.actions.new_folder'),
      disabledReason: write,
      tone: 'neutral',
    },
    {
      kind: 'upload',
      label: t('files.actions.upload'),
      disabledReason: write,
      tone: 'neutral',
    },
    {
      kind: 'open',
      label: t('files.actions.open'),
      disabledReason: read ?? one(selected),
      tone: 'neutral',
    },
    {
      kind: 'edit',
      label: t('files.actions.edit'),
      disabledReason: read ?? one(selected) ?? editReason(only, context),
      tone: 'neutral',
    },
    {
      kind: 'download',
      label: t('files.actions.download'),
      disabledReason: read ?? one(selected) ?? downloadReason(only),
      tone: 'neutral',
    },
    {
      kind: 'rename',
      label: t('files.actions.rename'),
      disabledReason: write ?? one(selected),
      tone: 'neutral',
    },
    {
      kind: 'move',
      label: t('files.actions.move'),
      disabledReason: write ?? some(selected),
      tone: 'neutral',
    },
    {
      kind: 'compress',
      label: t('files.actions.compress'),
      disabledReason: write ?? some(selected),
      tone: 'neutral',
    },
    {
      kind: 'extract',
      label: t('files.actions.extract'),
      disabledReason: write ?? one(selected) ?? extractReason(only),
      tone: 'neutral',
    },
    {
      kind: 'chmod',
      label: t('files.actions.chmod'),
      disabledReason: write ?? one(selected),
      tone: 'neutral',
    },
    {
      kind: 'measure',
      label: t('files.actions.measure'),
      disabledReason: read ?? measureReason(selected),
      tone: 'neutral',
    },
    {
      kind: 'delete',
      label: t('files.actions.delete'),
      disabledReason: write ?? some(selected),
      tone: 'danger',
    },
    {
      kind: 'refresh',
      label: t('files.actions.refresh'),
      disabledReason: null,
      tone: 'neutral',
    },
  ]
}

/** The states as a lookup, for a caller that wants one button rather than the row. */
export function actionMap(
  selected: FileEntryView[],
  context: FileActionContext,
): Map<FileActionKind, FileActionState> {
  return new Map(actionStates(selected, context).map((state) => [state.kind, state]))
}

function editReason(
  entry: FileEntryView | undefined,
  context: FileActionContext,
): string | null {
  if (!entry) {
    return null
  }
  if (entry.directory) {
    return t('files.reasons.folder_nothing_to_edit')
  }
  if (entry.symlink) {
    return t('files.reasons.symlink_edit')
  }
  if (!isEditable(entry, context.maxEditableBytes)) {
    return t('files.reasons.file_too_large_to_open')
  }
  return null
}

function downloadReason(entry: FileEntryView | undefined): string | null {
  if (!entry) {
    return null
  }
  if (entry.directory) {
    return t('files.reasons.folder_cannot_download')
  }
  if (entry.symlink) {
    return t('files.reasons.symlink_download')
  }
  return null
}

function extractReason(entry: FileEntryView | undefined): string | null {
  if (!entry) {
    return null
  }
  if (entry.directory || !isArchiveName(entry.name)) {
    return t('files.reasons.not_an_archive')
  }
  return null
}

/**
 * Measuring works on the folder being browsed when nothing is picked.
 *
 * That is the question somebody actually has - "how much is in here?" - and making them
 * tick a row to ask it would mean ticking the folder they are already standing in, which
 * is not on the list.
 */
function measureReason(selected: FileEntryView[]): string | null {
  if (selected.length === 0) {
    return null
  }
  if (selected.length > 1) {
    return t('files.reasons.measure_one')
  }
  return selected[0]?.directory ? null : t('files.reasons.file_shows_size')
}
