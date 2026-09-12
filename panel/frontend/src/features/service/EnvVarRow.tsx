import {t} from '@/i18n'
import {Badge, CopyButton, Icon} from '@/shell'

import type {EnvVar} from './serviceTypes'

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
          {variable.buildTime ? <Badge tone="accent">{t('service.variables.build_badge')}</Badge> : null}
        </span>
        <span className="mt-0.5 block truncate font-mono text-xs text-ink-500 dark:text-ink-400">
          {revealed ? variable.value || t('service.variables.empty_value') : '••••••••'}
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
          describedAs={t('service.variables.copy_value', {name: variable.name})}
          className="border-transparent"
        />
      </span>
    </li>
  )
}
