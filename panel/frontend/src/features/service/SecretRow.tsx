import {t} from '@/i18n'
import {Badge, Icon, RelativeTime} from '@/shell'

import type {SecretView} from './serviceTypes'

export function SecretRow({
  secret,
  onOpen,
}: {
  secret: SecretView
  /** Absent for a viewer. */
  onOpen?: () => void
}) {
  const body = (
    <>
      <span className="min-w-0 flex-1">
        <span className="flex items-center gap-2">
          <code className="truncate font-mono text-sm font-medium text-ink-900 dark:text-ink-100">
            {secret.name}
          </code>
          {secret.buildTime ? <Badge tone="accent">{t('service.variables.build_badge')}</Badge> : null}
        </span>
        <span className="mt-0.5 block truncate text-xs text-ink-500 dark:text-ink-400">
          {secret.lastChangedAt ? (
            <>
              {t('service.secrets.last_changed')} <RelativeTime at={secret.lastChangedAt} />
            </>
          ) : (
            <>
              {t('service.secrets.set_prefix')} <RelativeTime at={secret.createdAt} />{' '}
              {t('service.secrets.unchanged_suffix')}
            </>
          )}
        </span>
      </span>
      {onOpen ? <Icon name="chevronRight" className="size-4 shrink-0 text-ink-400" /> : null}
    </>
  )

  return (
    <li>
      {onOpen ? (
        <button
          type="button"
          onClick={onOpen}
          className="flex w-full touch-target items-center gap-3 px-4 py-2.5 text-left transition-colors hover:bg-ink-100 md:px-5 dark:hover:bg-ink-800"
        >
          {body}
        </button>
      ) : (
        <div className="flex w-full touch-target items-center gap-3 px-4 py-2.5 md:px-5">
          {body}
        </div>
      )}
    </li>
  )
}
