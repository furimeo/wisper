import {Button, cx} from '@/shell'

/**
 * What to do with the entries that are ticked.
 *
 * Sticky at the top of the listing rather than fixed at the bottom of the screen: the
 * bottom of a phone already has the application's navigation and the toast stack, and a
 * third bar competing for the same forty pixels is how a customer taps Delete meaning
 * Projects. Sticking to the top of the list keeps it beside the checkboxes it is about,
 * and it stays on screen while a long directory scrolls past.
 *
 * Only two actions, both of which the panel really has for a set of paths: compress them
 * into one archive, which is a single request, and delete them, which is one request each
 * and says how many went. Everything else - rename, permissions - is about one entry and
 * lives in that entry's own menu.
 */
export function SelectionBar({
  count,
  canWrite,
  busy,
  onCompress,
  onDelete,
  onClear,
}: {
  count: number
  canWrite: boolean
  busy: boolean
  onCompress: () => void
  onDelete: () => void
  onClear: () => void
}) {
  if (count === 0) {
    return null
  }

  return (
    <div
      className={cx(
        'sticky top-0 z-20 flex flex-wrap items-center justify-between gap-2',
        'border-b border-accent-500/40 bg-accent-500/10 px-3 py-2 backdrop-blur',
      )}
    >
      <span className="text-sm font-medium text-ink-900 tabular-nums dark:text-ink-100">
        {count} selected
      </span>
      <div className="flex items-center gap-2">
        {canWrite ? (
          <>
            <Button variant="secondary" size="sm" onClick={onCompress} disabled={busy}>
              Compress
            </Button>
            <Button variant="danger" size="sm" onClick={onDelete} loading={busy}>
              Delete
            </Button>
          </>
        ) : null}
        <Button variant="ghost" size="sm" onClick={onClear}>
          Clear
        </Button>
      </div>
    </div>
  )
}
