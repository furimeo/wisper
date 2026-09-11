import type {ReactNode} from 'react'

import {cx} from '@/shell'

/**
 * The extra keys a phone keyboard does not have.
 *
 * The whole product assumes people administer their hosting from a phone, and the two
 * screens where that assumption is tested hardest are the terminal and the file editor.
 * A software keyboard has no Escape, no Tab, no arrows and no Control, and it buries the
 * braces, the pipe and the slash two layers deep - which between them is most of what a
 * shell command or a config file is made of.
 *
 * Two details make it work rather than merely exist. The bar never takes focus:
 * `onPointerDown` is prevented, so the on-screen keyboard stays open and the caret stays
 * where it was - a bar that dismissed the keyboard on every tap would be worse than no
 * bar. And a sticky key stays visibly pressed until it is used, because Control has no
 * meaning on its own and a modifier with no state on screen is a guess.
 *
 * The bar itself is deliberately a strip that scrolls sideways: sixteen 44px targets do
 * not fit across 375px, and shrinking them below 44px is how a key bar becomes a source
 * of typing mistakes.
 */
export interface KeyBarKey {
  /** Passed back to `onPress`. The caller decides what it means. */
  id: string
  label: ReactNode
  /** Announced instead of the label, for a glyph like an arrow. */
  title?: string
  /** A modifier that stays down until the next key. */
  sticky?: boolean
  /** Whether that modifier is currently down. */
  pressed?: boolean
}

export function MobileKeyBar({
  keys,
  onPress,
  trailing,
  className,
}: {
  keys: KeyBarKey[]
  onPress: (id: string) => void
  /** Pinned to the right, outside the scrolling strip: the paste button, mostly. */
  trailing?: ReactNode
  className?: string
}) {
  return (
    <div
      className={cx(
        'flex items-stretch gap-1 border-t border-ink-200 bg-ink-100 px-1 py-1',
        'dark:border-ink-800 dark:bg-ink-900',
        className,
      )}
    >
      <div
        className="hide-scrollbar flex min-w-0 flex-1 items-stretch gap-1 overflow-x-auto"
        role="group"
        aria-label="Extra keys"
      >
        {keys.map((key) => (
          <button
            key={key.id}
            type="button"
            title={key.title}
            aria-label={key.title}
            aria-pressed={key.sticky ? Boolean(key.pressed) : undefined}
            // Keeps the software keyboard open and the caret where it was.
            onPointerDown={(event) => event.preventDefault()}
            onClick={() => onPress(key.id)}
            className={cx(
              'flex h-11 min-w-11 shrink-0 items-center justify-center rounded-lg px-2.5',
              'font-mono text-sm select-none',
              key.pressed
                ? 'bg-accent-600 text-white'
                : 'bg-white text-ink-800 active:bg-ink-200 dark:bg-ink-800 dark:text-ink-100 dark:active:bg-ink-700',
            )}
          >
            {key.label}
          </button>
        ))}
      </div>
      {trailing ? <div className="flex shrink-0 items-stretch gap-1">{trailing}</div> : null}
    </div>
  )
}
