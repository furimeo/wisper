import {Icon} from './Icon'
import {cx} from './cx'
import {useClipboard} from './useClipboard'

/**
 * Copies a string, and says whether it worked.
 *
 * The panel hands out a lot of text nobody is going to retype: connection strings, the
 * node install command and its checksum, an API token shown exactly once, a container id,
 * a certificate fingerprint. Selecting any of that inside a scrolling row on a phone is
 * close to impossible, so this is not a convenience control.
 *
 * It reports failure rather than pretending. A copy can be refused - no permission, the
 * document not focused, an insecure context with the fallback also blocked - and a button
 * that flashes "Copied" when nothing was copied costs the customer the token.
 */
export interface CopyButtonProps {
  value: string
  /** Shown next to the icon. Omit for an icon-only button in a tight row. */
  label?: string
  /** What a screen reader hears. Say what is being copied. */
  describedAs?: string
  size?: 'md' | 'sm'
  className?: string
}

export function CopyButton({
  value,
  label,
  describedAs,
  size = 'md',
  className,
}: CopyButtonProps) {
  const {copy, state} = useClipboard()

  const text = state === 'copied' ? 'Copied' : state === 'failed' ? 'Press ⌘/Ctrl+C' : label

  return (
    <button
      type="button"
      onClick={() => void copy(value)}
      aria-label={describedAs ?? (label ? undefined : `Copy ${value}`)}
      aria-live="polite"
      className={cx(
        'inline-flex touch-target items-center justify-center gap-1.5 rounded-lg border',
        'text-sm font-medium transition-colors',
        size === 'sm' ? 'min-h-9 px-2.5' : 'min-h-11 px-3',
        label || state !== 'idle' ? '' : 'aspect-square px-0',
        state === 'copied'
          ? 'border-running/40 text-running'
          : state === 'failed'
            ? 'border-failed/50 text-failed'
            : 'border-ink-300 text-ink-700 hover:bg-ink-100 dark:border-ink-700 dark:text-ink-300 dark:hover:bg-ink-800',
        className,
      )}
    >
      <Icon name={state === 'copied' ? 'check' : 'copy'} className="size-4" />
      {text ? <span className="whitespace-nowrap">{text}</span> : null}
    </button>
  )
}
