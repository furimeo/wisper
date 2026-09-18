import {t} from '@/i18n'
import {Checkbox} from '@/shell'
import {FilePathBar} from './FilePathBar'
import {FileToolbar} from './FileToolbar'
import type {FileActionKind, FileActionState} from './fileActions'
import type {FileRootRef} from './fileTypes'

export function FileChromeBar({
  serviceId,
  roots,
  root,
  path,
  parentPath,
  showHidden,
  onShowHiddenChange,
  states,
  onAction,
  refreshing,
  editing,
  onEditingChange,
}: {
  serviceId: string
  roots: FileRootRef[]
  root: FileRootRef
  path: string
  parentPath: string
  showHidden: boolean
  onShowHiddenChange: (value: boolean) => void
  states: FileActionState[]
  onAction: (kind: FileActionKind) => void
  refreshing: boolean
  editing: boolean
  onEditingChange: (editing: boolean) => void
}) {
  return (
    <div className="flex flex-col gap-3 border-b border-ink-200 bg-ink-50/50 p-3 dark:border-ink-800 dark:bg-ink-900/30 sm:p-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <FileToolbar states={states} onAction={onAction} refreshing={refreshing} />
        <Checkbox
          checked={showHidden}
          onChange={(event) => onShowHiddenChange(event.target.checked)}
          label={t('files.browser.show_hidden')}
        />
      </div>
      <FilePathBar
        serviceId={serviceId}
        roots={roots}
        root={root}
        path={path}
        parentPath={parentPath}
        showHidden={showHidden}
        editing={editing}
        onEditingChange={onEditingChange}
      />
    </div>
  )
}
