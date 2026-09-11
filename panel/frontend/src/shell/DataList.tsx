import {Link} from '@inertiajs/react'
import type {ReactNode} from 'react'
import {useState} from 'react'

import {Drawer} from './Drawer'
import {EmptyState} from './EmptyState'
import {Icon} from './Icon'
import {SkeletonList} from './Skeleton'
import type {SwipeAction} from './SwipeRow'
import {SwipeRow} from './SwipeRow'
import {cx} from './cx'
import {useIsWide} from './useMediaQuery'

/**
 * One collection, rendered as a table on a desktop and as a list of rows on a phone.
 *
 * This is the component the mobile-first rule is really about. A four-column table on a
 * 375px screen either scrolls sideways - hiding the columns that matter behind the ones
 * that do not - or squeezes every cell to two words. So below `md` the same data becomes
 * one row per item: a headline, a supporting line, a state on the right, and its actions
 * behind a swipe and an overflow button. Above `md` there is room for the table, and a
 * table is genuinely better for comparing forty rows.
 *
 * Only one of the two is rendered, chosen from the viewport rather than hidden with CSS:
 * a hidden copy still mounts every row, doubles the swipe handlers and doubles what a
 * screen reader has to walk past.
 */
export interface DataListColumn<T> {
  key: string
  header: ReactNode
  cell: (item: T) => ReactNode
  align?: 'left' | 'right'
  /** Extra classes on both the header cell and the body cell. */
  className?: string
}

export interface DataListProps<T> {
  items: T[]
  keyOf: (item: T) => string
  /** The desktop table. */
  columns: Array<DataListColumn<T>>
  /** The phone row's headline - the name somebody is scanning for. */
  primary: (item: T) => ReactNode
  /** The phone row's second line - the one or two facts that disambiguate it. */
  secondary?: (item: T) => ReactNode
  /** The phone row's right-hand side: a state pill, a size, a time. */
  trailing?: (item: T) => ReactNode
  /** Swipe actions on a phone, an overflow menu everywhere. */
  actions?: (item: T) => SwipeAction[]
  /** Makes the whole row a link. */
  href?: (item: T) => string | undefined
  /** What to show when there is nothing. A real sentence, not a blank frame. */
  empty?: ReactNode
  loading?: boolean
  /** Announced as the purpose of the table or list. */
  label: string
  className?: string
}

export function DataList<T>({
  items,
  keyOf,
  columns,
  primary,
  secondary,
  trailing,
  actions,
  href,
  empty,
  loading,
  label,
  className,
}: DataListProps<T>) {
  const wide = useIsWide()
  const [openRow, setOpenRow] = useState<string | null>(null)
  const [menuRow, setMenuRow] = useState<string | null>(null)

  if (loading) {
    return <SkeletonList label={`Loading ${label}`} className={className} />
  }

  if (items.length === 0) {
    return (
      <div className={className}>
        {empty ?? (
          <EmptyState
            title={`No ${label} yet`}
            description="Nothing here so far. Anything you create will be listed here."
          />
        )}
      </div>
    )
  }

  const menuItem = menuRow === null ? null : items.find((item) => keyOf(item) === menuRow)
  const menuActions = menuItem && actions ? actions(menuItem) : []

  return (
    <div className={className}>
      {wide ? (
        <DesktopTable
          items={items}
          keyOf={keyOf}
          columns={columns}
          actions={actions}
          href={href}
          label={label}
          onOpenMenu={setMenuRow}
        />
      ) : (
        <ul className="divide-y divide-ink-200 dark:divide-ink-800" aria-label={label}>
          {items.map((item) => {
            const key = keyOf(item)
            const rowActions = actions?.(item) ?? []
            const target = href?.(item)
            const body = (
              <div className="flex min-w-0 items-center gap-3 px-4 py-3">
                <div className="min-w-0 flex-1">
                  <div className="truncate text-sm font-medium text-ink-900 dark:text-ink-100">
                    {primary(item)}
                  </div>
                  {secondary ? (
                    <div className="mt-0.5 truncate text-sm text-ink-500 dark:text-ink-400">
                      {secondary(item)}
                    </div>
                  ) : null}
                </div>
                {trailing ? <div className="shrink-0">{trailing(item)}</div> : null}
                {target ? (
                  <Icon name="chevronRight" className="size-4 shrink-0 text-ink-400" />
                ) : null}
              </div>
            )

            return (
              <li key={key}>
                <SwipeRow
                  actions={rowActions}
                  open={openRow === key}
                  onOpenChange={(next) => setOpenRow(next ? key : null)}
                >
                  <div className="flex items-stretch">
                    {target ? (
                      <Link href={target} className="min-w-0 flex-1">
                        {body}
                      </Link>
                    ) : (
                      <div className="min-w-0 flex-1">{body}</div>
                    )}
                    {rowActions.length > 0 ? (
                      <button
                        type="button"
                        onClick={() => setMenuRow(key)}
                        aria-label="Actions"
                        className="flex touch-target shrink-0 items-center justify-center px-1 text-ink-500"
                      >
                        <Icon name="more" />
                      </button>
                    ) : null}
                  </div>
                </SwipeRow>
              </li>
            )
          })}
        </ul>
      )}

      <Drawer
        open={menuActions.length > 0}
        onClose={() => setMenuRow(null)}
        side="right"
        title="Actions"
      >
        <ul className="flex flex-col">
          {menuActions.map((action) => (
            <li key={action.label}>
              <button
                type="button"
                onClick={() => {
                  setMenuRow(null)
                  setOpenRow(null)
                  action.onSelect()
                }}
                className={cx(
                  'flex w-full touch-target items-center gap-3 rounded-lg px-3 text-left text-sm',
                  action.tone === 'danger'
                    ? 'text-failed hover:bg-failed/10'
                    : 'text-ink-800 hover:bg-ink-100 dark:text-ink-100 dark:hover:bg-ink-800',
                )}
              >
                {action.icon}
                {action.label}
              </button>
            </li>
          ))}
        </ul>
      </Drawer>
    </div>
  )
}

function DesktopTable<T>({
  items,
  keyOf,
  columns,
  actions,
  href,
  label,
  onOpenMenu,
}: {
  items: T[]
  keyOf: (item: T) => string
  columns: Array<DataListColumn<T>>
  actions?: (item: T) => SwipeAction[]
  href?: (item: T) => string | undefined
  label: string
  onOpenMenu: (key: string) => void
}) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full border-collapse text-left text-sm">
        <caption className="sr-only">{label}</caption>
        <thead>
          <tr className="border-b border-ink-200 dark:border-ink-800">
            {columns.map((column) => (
              <th
                key={column.key}
                scope="col"
                className={cx(
                  'px-4 py-2.5 text-xs font-semibold uppercase tracking-wide',
                  'text-ink-500 dark:text-ink-400',
                  column.align === 'right' ? 'text-right' : '',
                  column.className,
                )}
              >
                {column.header}
              </th>
            ))}
            {actions ? <th className="w-12 px-2" /> : null}
          </tr>
        </thead>
        <tbody className="divide-y divide-ink-200 dark:divide-ink-800">
          {items.map((item) => {
            const key = keyOf(item)
            const target = href?.(item)
            const rowActions = actions?.(item) ?? []
            return (
              <tr
                key={key}
                className="align-middle hover:bg-ink-50 dark:hover:bg-ink-800/50"
              >
                {columns.map((column, index) => (
                  <td
                    key={column.key}
                    className={cx(
                      'px-4 py-3 text-ink-800 dark:text-ink-200',
                      column.align === 'right' ? 'text-right' : '',
                      column.className,
                    )}
                  >
                    {index === 0 && target ? (
                      <Link
                        href={target}
                        className="font-medium text-ink-900 hover:text-accent-600 dark:text-ink-100 dark:hover:text-accent-400"
                      >
                        {column.cell(item)}
                      </Link>
                    ) : (
                      column.cell(item)
                    )}
                  </td>
                ))}
                {actions ? (
                  <td className="px-2 py-3 text-right">
                    {rowActions.length > 0 ? (
                      <button
                        type="button"
                        onClick={() => onOpenMenu(key)}
                        aria-label="Actions"
                        className="inline-flex size-9 items-center justify-center rounded-lg text-ink-500 hover:bg-ink-200/60 dark:hover:bg-ink-700"
                      >
                        <Icon name="more" />
                      </button>
                    ) : null}
                  </td>
                ) : null}
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}
