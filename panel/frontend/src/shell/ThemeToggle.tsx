import {Icon} from './Icon'
import type {IconName} from './Icon'
import {cx} from './cx'
import type {ThemePreference} from './useTheme'
import {useTheme} from './useTheme'

/**
 * Light, dark, or follow the device.
 *
 * Three states rather than a two-way switch, because "follow the device" is the default
 * and a two-way switch has no way to get back to it once it has been touched. A segmented
 * control rather than a menu: three options fit, and one tap beats two.
 */
const OPTIONS: Array<{value: ThemePreference; label: string; icon: IconName}> = [
  {value: 'light', label: 'Light', icon: 'sun'},
  {value: 'dark', label: 'Dark', icon: 'moon'},
  {value: 'system', label: 'System', icon: 'monitor'},
]

export function ThemeToggle({className}: {className?: string}) {
  const {preference, set} = useTheme()

  return (
    <div
      role="group"
      aria-label="Colour theme"
      className={cx(
        'inline-flex rounded-lg border border-ink-200 bg-ink-100 p-0.5 dark:border-ink-700 dark:bg-ink-800',
        className,
      )}
    >
      {OPTIONS.map((option) => {
        const active = preference === option.value
        return (
          <button
            key={option.value}
            type="button"
            aria-pressed={active}
            onClick={() => set(option.value)}
            title={option.label}
            className={cx(
              'flex size-9 items-center justify-center rounded-md transition-colors',
              active
                ? 'bg-white text-ink-900 shadow-sm dark:bg-ink-950 dark:text-ink-50'
                : 'text-ink-500 hover:text-ink-800 dark:hover:text-ink-200',
            )}
          >
            <Icon name={option.icon} className="size-4" label={option.label} />
          </button>
        )
      })}
    </div>
  )
}
