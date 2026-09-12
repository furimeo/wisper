import {Icon} from './Icon'
import type {IconName} from './Icon'
import {cx} from './cx'
import type {ThemePreference} from './useTheme'
import {useTheme} from './useTheme'
import {t} from '@/i18n'

/**
 * Light, dark, or follow the device.
 *
 * Three states rather than a two-way switch, because "follow the device" is the default
 * and a two-way switch has no way to get back to it once it has been touched. A segmented
 * control rather than a menu: three options fit, and one tap beats two.
 */
const OPTIONS: Array<{value: ThemePreference; labelKey: string; icon: IconName}> = [
  {value: 'light', labelKey: 'shell.theme.light', icon: 'sun'},
  {value: 'dark', labelKey: 'shell.theme.dark', icon: 'moon'},
  {value: 'system', labelKey: 'shell.theme.system', icon: 'monitor'},
]

export function ThemeToggle({className}: {className?: string}) {
  const {preference, set} = useTheme()

  return (
    <div
      role="group"
      aria-label={t('shell.theme.title')}
      className={cx(
        'inline-flex rounded-lg border border-ink-200 bg-ink-100 p-0.5 dark:border-ink-700 dark:bg-ink-800',
        className,
      )}
    >
      {OPTIONS.map((option) => {
        const active = preference === option.value
        const label = t(option.labelKey)
        return (
          <button
            key={option.value}
            type="button"
            aria-pressed={active}
            onClick={() => set(option.value)}
            title={label}
            className={cx(
              'flex size-9 items-center justify-center rounded-md transition-colors',
              active
                ? 'bg-white text-ink-900 shadow-sm dark:bg-ink-950 dark:text-ink-50'
                : 'text-ink-500 hover:text-ink-800 dark:hover:text-ink-200',
            )}
          >
            <Icon name={option.icon} className="size-4" label={label} />
          </button>
        )
      })}
    </div>
  )
}
