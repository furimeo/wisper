import {cx} from '@/shell'

import type {FileActionKind} from './fileActions'

/**
 * The glyph on a toolbar button and beside a menu item.
 *
 * Drawn on the same 24x24 stroke grid as `shell/Icon`, so a control here and a control
 * anywhere else in the panel look like the same product, but kept in this feature: the
 * shell's set is the twenty glyphs the chrome needs, and "extract", "chmod" and "compress"
 * are nouns only a file manager has. Growing the shared set with them would make every
 * page in the panel carry the paths for a screen it does not have.
 *
 * A toolbar of eleven text buttons wraps to three lines on a laptop and does not fit a
 * phone at all, which is the whole reason these exist rather than labels alone. The label
 * is still there on a wide screen, and it is still the accessible name everywhere.
 */
const PATHS: Record<FileActionKind, string> = {
  newFolder:
    'M3.5 6.8A1.3 1.3 0 0 1 4.8 5.5h3.4l1.9 2.2h9.1a1.3 1.3 0 0 1 1.3 1.3v8.2a1.3 1.3 0 0 1-1.3 1.3H4.8a1.3 1.3 0 0 1-1.3-1.3V6.8ZM12 11v5M9.5 13.5h5',
  newFile:
    'M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8l-6-6Zm-2 9v6m-3-3h6',
  upload: 'M12 16.5v-12M7.5 9 12 4.5 16.5 9M4.5 15.5v3a1.5 1.5 0 0 0 1.5 1.5h12a1.5 1.5 0 0 0 1.5-1.5v-3',
  download:
    'M12 3.5v12M7.5 11l4.5 4.5L16.5 11M4.5 15.5v3a1.5 1.5 0 0 0 1.5 1.5h12a1.5 1.5 0 0 0 1.5-1.5v-3',
  open: 'M4.5 7.5A1.5 1.5 0 0 1 6 6h3.5l1.8 2h7.2A1.5 1.5 0 0 1 20 9.5v7A1.5 1.5 0 0 1 18.5 18h-13A1.5 1.5 0 0 1 4 16.5l.5-9Z',
  edit: 'M4.5 19.5h4L19 9a2.1 2.1 0 0 0-3-3L5.5 16.5l-1 3ZM14.5 7.5l2 2',
  rename: 'M4.5 8V6.5h15V8M12 6.5v11M9.5 17.5h5',
  move: 'M4.5 12h11M12 8.5 15.5 12 12 15.5M18.5 5v14',
  compress:
    'M6 4.5h12a1 1 0 0 1 1 1v13a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1v-13a1 1 0 0 1 1-1ZM11 4.5v3M13 7.5v3M11 10.5v3M12 15.5h.5a1 1 0 0 1 1 1V18h-3v-1.5a1 1 0 0 1 1-1H12Z',
  extract: 'M12 14.5v-10M8.5 8 12 4.5 15.5 8M4.5 14v4.5a1 1 0 0 0 1 1h13a1 1 0 0 0 1-1V14M8 14h8',
  chmod: 'M8 12a4 4 0 1 0 0 8 4 4 0 1 0 0-8M10.8 13.2 20.5 3.5M18 6l2 2M15.5 8.5l2 2',
  measure: 'M3.5 9.5h17a1 1 0 0 1 1 1v3a1 1 0 0 1-1 1h-17a1 1 0 0 1-1-1v-3a1 1 0 0 1 1-1ZM7 9.5v3M11 9.5v4M15 9.5v3M19 9.5v4',
  delete: 'M4.5 7h15M9.5 7V5.5a1 1 0 0 1 1-1h3a1 1 0 0 1 1 1V7M6.5 7l.8 12a1.5 1.5 0 0 0 1.5 1.4h6.4a1.5 1.5 0 0 0 1.5-1.4L17.5 7M10 11v6M14 11v6',
  refresh:
    'M19.5 12a7.5 7.5 0 1 1-2.2-5.3M19.5 4v4.5H15',
}

export function FileActionIcon({
  kind,
  className,
}: {
  kind: FileActionKind
  className?: string
}) {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      aria-hidden="true"
      className={cx('size-5 shrink-0', className)}
    >
      <path
        d={PATHS[kind]}
        stroke="currentColor"
        strokeWidth="1.6"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  )
}
