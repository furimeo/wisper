import {t} from '@/i18n'
import {ByteSize} from '@/shell'
import type {FileEntryView} from './fileTypes'

export function FileStatusBar({
  entries,
  selected,
}: {
  entries: FileEntryView[]
  selected: FileEntryView[]
}) {
  const totalCount = entries.length
  const selectedCount = selected.length
  const selectedBytes = selected.reduce((sum, e) => sum + (e.sizeBytes ?? 0), 0)

  return (
    <div className="flex flex-wrap items-center justify-between gap-3 border-t border-ink-200 bg-ink-50/50 px-4 py-2.5 text-xs text-ink-600 dark:border-ink-800 dark:bg-ink-900/30 dark:text-ink-400">
      <div className="flex items-center gap-2">
        <span>{t('files.status.total_items', {count: totalCount})}</span>
      </div>
      {selectedCount > 0 && (
        <div className="flex items-center gap-2 font-medium text-accent-600 dark:text-accent-400">
          <span>{t('files.status.selected_items', {count: selectedCount})}</span>
          <span>&middot;</span>
          <ByteSize bytes={selectedBytes} />
        </div>
      )}
    </div>
  )
}
