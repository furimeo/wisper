import {Button, cx, useIsWide} from '@/shell'

import {FileActionIcon} from './FileActionIcon'
import type {FileActionKind, FileActionState} from './fileActions'

/**
 * The operations, in one place, all of the time.
 *
 * A toolbar is the difference between a file list and a file manager: it is the one part
 * of the screen that says what this tool can do before anything has been selected. Every
 * button is present at every moment and switches off when the selection does not suit it,
 * carrying the reason in its tooltip - so a control is always in the same place, and "why
 * can I not extract this?" is answered by hovering the button rather than by reading the
 * manual there is not.
 *
 * On a phone it collapses to three targets: upload, new folder, and the sheet that holds
 * everything else. Eleven 44px buttons across 375px is four rows of chrome above a list
 * that would then start below the fold.
 */
const GROUPS: FileActionKind[][] = [
  ['newFolder', 'upload'],
  ['open', 'edit', 'download'],
  ['rename', 'move', 'compress', 'extract'],
  ['chmod', 'measure'],
  ['delete'],
]

/** What the phone keeps on the bar itself; the rest is one tap away in the sheet. */
const PHONE_BAR: FileActionKind[] = ['upload', 'newFolder']

export function FileToolbar({
  states,
  onAction,
  onOpenSheet,
  selectedCount,
  onClearSelection,
  refreshing,
}: {
  states: FileActionState[]
  onAction: (kind: FileActionKind) => void
  /** Opens the same menu a right-click opens, with no anchor - the phone's way in. */
  onOpenSheet: () => void
  selectedCount: number
  onClearSelection: () => void
  /** A listing is being re-fetched: the refresh control says so rather than looking inert. */
  refreshing: boolean
}) {
  const wide = useIsWide()
  const by = new Map(states.map((state) => [state.kind, state]))
  const refresh = by.get('refresh')

  return (
    <div
      role="toolbar"
      aria-label="File operations"
      aria-orientation="horizontal"
      className={cx(
        'flex flex-wrap items-center gap-x-1 gap-y-2 rounded-xl border px-2 py-2',
        'border-ink-200 bg-white dark:border-ink-800 dark:bg-ink-900',
      )}
    >
      {wide ? (
        GROUPS.map((group, index) => (
          <div key={group.join()} className="flex items-center gap-1">
            {index > 0 ? (
              <span aria-hidden="true" className="mx-1 h-6 w-px bg-ink-200 dark:bg-ink-800" />
            ) : null}
            {group.map((kind) => (
              <ToolbarButton key={kind} state={by.get(kind)} onSelect={() => onAction(kind)} />
            ))}
          </div>
        ))
      ) : (
        <>
          {PHONE_BAR.map((kind) => (
            <ToolbarButton
              key={kind}
              state={by.get(kind)}
              onSelect={() => onAction(kind)}
              compact
            />
          ))}
          <Button variant="secondary" size="sm" onClick={onOpenSheet}>
            Actions
          </Button>
        </>
      )}

      <div className="ml-auto flex items-center gap-2">
        {selectedCount > 0 ? (
          <>
            <span className="text-xs font-medium tabular-nums text-ink-600 dark:text-ink-300">
              {selectedCount.toLocaleString()} selected
            </span>
            <Button variant="ghost" size="sm" onClick={onClearSelection}>
              Clear
            </Button>
          </>
        ) : null}
        {refresh ? (
          <Button
            variant="ghost"
            size="sm"
            onClick={() => onAction('refresh')}
            loading={refreshing}
            title="Refresh this folder"
          >
            {refreshing ? null : <FileActionIcon kind="refresh" />}
            <span className="sr-only lg:not-sr-only">Refresh</span>
          </Button>
        ) : null}
      </div>
    </div>
  )
}

function ToolbarButton({
  state,
  onSelect,
  compact,
}: {
  state: FileActionState | undefined
  onSelect: () => void
  /** Icon only. The label is still the accessible name. */
  compact?: boolean
}) {
  if (!state) {
    return null
  }
  const disabled = state.disabledReason !== null
  return (
    <Button
      variant="ghost"
      size="sm"
      onClick={onSelect}
      disabled={disabled}
      // The reason is the tooltip, so a greyed control explains itself rather than
      // leaving somebody clicking it harder.
      title={state.disabledReason ?? state.label}
      aria-label={state.label}
      className={
        state.tone === 'danger' && !disabled ? 'text-failed hover:bg-failed/10' : undefined
      }
    >
      <FileActionIcon kind={state.kind} />
      <span className={compact ? 'sr-only' : 'hidden lg:inline'}>{state.label}</span>
    </Button>
  )
}
