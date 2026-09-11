import {Badge, CopyButton, Icon} from '@/shell'

import type {EnvVar} from './serviceTypes'

/**
 * One plain environment variable.
 *
 * The value is masked by default and the mask is a fixed eight dots rather than one per
 * character: a customer standing on a train has somebody behind them, and a mask whose
 * width leaks the length of an API key is a mask that tells you which key it is.
 *
 * Copy sits outside the row's own button. Nesting an interactive control inside another
 * one is invalid HTML and, more to the point, makes the outer target eat the tap on a
 * phone about a third of the time. The row opens the editor; the button next to it
 * copies; neither is inside the other.
 */
export function EnvVarRow({
  variable,
  revealed,
  onOpen,
}: {
  variable: EnvVar
  /** Whether the list's "Show values" switch is on. */
  revealed: boolean
  /** Absent for a viewer: there is nothing behind the row for somebody who cannot write. */
  onOpen?: () => void
}) {
  const body = (
    <>
      <span className="min-w-0 flex-1">
        <span className="flex items-center gap-2">
          <code className="truncate font-mono text-sm font-medium text-ink-900 dark:text-ink-100">
            {variable.name}
          </code>
          {variable.buildTime ? <Badge tone="accent">Build</Badge> : null}
        </span>
        <span className="mt-0.5 block truncate font-mono text-xs text-ink-500 dark:text-ink-400">
          {revealed ? variable.value || '(empty)' : '••••••••'}
        </span>
      </span>
      {onOpen ? <Icon name="chevronRight" className="size-4 shrink-0 text-ink-400" /> : null}
    </>
  )

  return (
    <li className="flex items-stretch">
      {onOpen ? (
        <button
          type="button"
          onClick={onOpen}
          className="flex min-w-0 flex-1 touch-target items-center gap-3 px-4 py-2.5 text-left transition-colors hover:bg-ink-100 md:px-5 dark:hover:bg-ink-800"
        >
          {body}
        </button>
      ) : (
        <div className="flex min-w-0 flex-1 touch-target items-center gap-3 px-4 py-2.5 md:px-5">
          {body}
        </div>
      )}

      <span className="flex shrink-0 items-center pr-2">
        <CopyButton
          value={variable.value}
          size="sm"
          describedAs={`Copy the value of ${variable.name}`}
          className="border-transparent"
        />
      </span>
    </li>
  )
}
