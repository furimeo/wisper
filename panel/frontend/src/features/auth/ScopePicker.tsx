import {t} from '@/i18n'
import {cx} from '@/shell'

import type {ApiScope} from './authTypes'

/**
 * The permission grid on the token form.
 *
 * Grouped by resource with one toggle per verb, which is the only shape seventeen scopes
 * take at 375px without becoming a scrolling wall of checkboxes. `:write` does not imply
 * `:read`, deliberately, so both are offered: a token that may deploy but not list is a
 * legitimate thing to want.
 *
 * A `fieldset` with a `legend` rather than the shell's `Field`, because this is a set of
 * controls rather than one: a `label` can only point at a single element, and pointing it
 * at nothing is how a group of checkboxes ends up announced without saying what it is for.
 *
 * The toggles are real checkboxes, visually hidden inside their labels rather than
 * replaced by divs. That keeps the keyboard, the screen reader and the label association
 * the browser gives for free, and `has-[:focus-visible]` puts the focus ring back on the
 * chip the customer is actually looking at.
 */
type ScopePickerProps = {
  /** Every scope this account may ask for, in `ApiScope` declaration order. */
  available: ApiScope[]
  chosen: string[]
  onToggle: (scope: ApiScope, on: boolean) => void
  /** The server's message for the `scopes` field, if it sent one. */
  error?: string | undefined
}

export function ScopePicker({available, chosen, onToggle, error}: ScopePickerProps) {
  return (
    <fieldset className="flex flex-col gap-1.5">
      <legend className="text-sm font-medium text-ink-700 dark:text-ink-300">
        {t('auth.tokens.permissionsLegend', {count: chosen.length})}
      </legend>

      <ul className="flex flex-col gap-2">
        {groupByResource(available).map(([resource, verbs]) => (
          <li
            key={resource}
            className="flex flex-wrap items-center gap-2 rounded-lg border border-ink-200 px-3 py-2 dark:border-ink-800"
          >
            <span className="mr-auto text-sm font-medium capitalize">{resource}</span>
            {verbs.map((scope) => {
              const checked = chosen.includes(scope)
              return (
                <label
                  key={scope}
                  className={cx(
                    'inline-flex touch-target cursor-pointer items-center gap-2 rounded-lg px-3 text-sm',
                    'has-[:focus-visible]:outline-2 has-[:focus-visible]:outline-offset-2',
                    'has-[:focus-visible]:outline-accent-500',
                    checked
                      ? 'bg-accent-600 text-white'
                      : 'bg-ink-100 text-ink-700 dark:bg-ink-800 dark:text-ink-300',
                  )}
                >
                  <input
                    type="checkbox"
                    name="scopes"
                    value={scope}
                    className="sr-only"
                    checked={checked}
                    onChange={(event) => onToggle(scope, event.target.checked)}
                  />
                  {scope.slice(resource.length + 1)}
                  <span className="sr-only"> {resource}</span>
                </label>
              )
            })}
          </li>
        ))}
      </ul>

      {error ? (
        <p role="alert" className="text-sm text-failed">
          {error}
        </p>
      ) : (
        <p className="text-sm text-ink-500 dark:text-ink-400">
          {t('auth.tokens.permissionsHint')}
        </p>
      )}
    </fieldset>
  )
}

/**
 * `['projects:read', 'projects:write', ...]` into `[['projects', [...]], ...]`.
 *
 * Insertion order, which is `ApiScope`'s declaration order, so the list on screen matches
 * the enum and does not reshuffle when a scope is added.
 */
function groupByResource(scopes: ApiScope[]): [string, ApiScope[]][] {
  const grouped = new Map<string, ApiScope[]>()
  for (const scope of scopes) {
    const resource = scope.slice(0, scope.indexOf(':'))
    const held = grouped.get(resource)
    if (held) {
      held.push(scope)
    } else {
      grouped.set(resource, [scope])
    }
  }
  return [...grouped.entries()]
}
