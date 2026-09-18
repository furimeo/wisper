import {cx} from '@/shell'

import {categoryOf} from './fileKinds'
import type {FileCategory} from './fileKinds'
import type {FileEntryView} from './fileTypes'

/**
 * The icon beside a file row.
 *
 * Six glyphs rather than a file-type icon set, drawn on the same 24x24 stroke grid as
 * `shell/Icon` so a row in the file manager and a row anywhere else in the panel look
 * like the same product. The shell's own set has no file glyphs and should not grow them:
 * nothing outside this feature has a use for "archive" or "image".
 *
 * A folder is tinted with the accent and everything else is neutral. On a phone the icon
 * is doing one job - letting a thumb find the folders while scrolling past forty files -
 * and colouring six categories differently would make that harder rather than prettier.
 */
const PATHS: Record<FileCategory, string> = {
  folder:
    'M3.5 6.8A1.3 1.3 0 0 1 4.8 5.5h3.4l1.9 2.2h9.1a1.3 1.3 0 0 1 1.3 1.3v8.2a1.3 1.3 0 0 1-1.3 1.3H4.8a1.3 1.3 0 0 1-1.3-1.3V6.8Z',
  archive:
    'M6 4.5h12a1 1 0 0 1 1 1v13a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1v-13a1 1 0 0 1 1-1ZM11 4.5v3M13 7.5v3M11 10.5v3M12 15.5h.5a1 1 0 0 1 1 1V18h-3v-1.5a1 1 0 0 1 1-1H12Z',
  image:
    'M4.5 5.5h15a1 1 0 0 1 1 1v11a1 1 0 0 1-1 1h-15a1 1 0 0 1-1-1v-11a1 1 0 0 1 1-1ZM3.5 15.5l4.2-4a1 1 0 0 1 1.4 0l4.4 4.3M13 14l2.2-2a1 1 0 0 1 1.4 0l3.9 3.6M15.8 9h.01',
  code: 'M9 4.5h6.5L19 8v11.5a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1v-15a1 1 0 0 1 1-1h2ZM15 4.5V8h3.5M9.8 12.5 8 14.5l1.8 2M14.2 12.5l1.8 2-1.8 2',
  text: 'M7 3.5h7.5L19 8v11.5a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1v-15a1 1 0 0 1 1-1ZM14 3.5V8h4.5M9 12h6M9 15.5h4',
  binary:
    'M7 3.5h7.5L19 8v11.5a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1v-15a1 1 0 0 1 1-1ZM14 3.5V8h4.5M9.5 12.5h1.5v4H9.5v-4ZM13.5 12.5H15v4h-1.5v-4Z',
}

const COLOR_BY_CATEGORY: Record<FileCategory, string> = {
  folder: 'text-amber-500 dark:text-amber-400',
  archive: 'text-orange-500 dark:text-orange-400',
  image: 'text-emerald-500 dark:text-emerald-400',
  code: 'text-sky-500 dark:text-sky-400',
  text: 'text-ink-500 dark:text-ink-400',
  binary: 'text-ink-400 dark:text-ink-500',
}

export function FileGlyph({entry, className}: {entry: FileEntryView; className?: string}) {
  const category = categoryOf(entry)
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      aria-hidden="true"
      className={cx(
        'size-5 shrink-0',
        COLOR_BY_CATEGORY[category],
        className,
      )}
    >
      <path
        d={PATHS[category]}
        stroke="currentColor"
        strokeWidth="1.6"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  )
}
