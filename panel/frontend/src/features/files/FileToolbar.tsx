import {Button, cx} from '@/shell'

import {FileActionIcon} from './FileActionIcon'
import type {FileActionKind, FileActionState} from './fileActions'

/**
 * The two things you can do to a folder, and nothing else.
 *
 * The first pass at this screen put all thirteen operations on a permanent bar, greying
 * out the ones the selection did not suit. It read as a control panel with a list
 * underneath rather than as a file manager, which is what every real one avoids: cPanel
 * and Pterodactyl both keep the chrome to "new folder" and "upload", because those are
 * the only two actions that make sense when nothing is selected.
 *
 * Everything else acts on something, so it lives where that something is: `FileActionMenu`
 * on each row, and `FileSelectionBar` when several rows are picked. A control that is
 * disabled nine times out of ten is a control that has taught you to look past it.
 */
const CREATE: FileActionKind[] = ['newFolder', 'upload']

export function FileToolbar({
  states,
  onAction,
  refreshing,
  className,
}: {
  states: FileActionState[]
  onAction: (kind: FileActionKind) => void
  /** A listing is being re-fetched: the control says so rather than looking inert. */
  refreshing: boolean
  className?: string
}) {
  const by = new Map(states.map((state) => [state.kind, state]))
  const refresh = by.get('refresh')

  return (
    <div
      role="toolbar"
      aria-label="Folder actions"
      className={cx('flex items-center gap-2', className)}
    >
      {refresh ? (
        <Button
          variant="ghost"
          size="sm"
          onClick={() => onAction('refresh')}
          loading={refreshing}
          title="Refresh this folder"
          aria-label="Refresh this folder"
        >
          {refreshing ? null : <FileActionIcon kind="refresh" />}
        </Button>
      ) : null}

      {CREATE.map((kind) => {
        const state = by.get(kind)
        if (!state) {
          return null
        }
        return (
          <Button
            key={kind}
            variant={kind === 'upload' ? 'primary' : 'secondary'}
            size="sm"
            onClick={() => onAction(kind)}
            disabled={state.disabledReason !== null}
            title={state.disabledReason ?? state.label}
          >
            <FileActionIcon kind={kind} />
            {state.label}
          </Button>
        )
      })}
    </div>
  )
}
