import {usePage} from '@inertiajs/react'

import {Button, Card, Field, Select, useFormFields} from '@/shell'
import {t} from '@/i18n'

/**
 * `POST /settings/language` - which language the panel is written in for this account.
 *
 * <p>On the account and not in the browser, because the choice has to reach two places a
 * browser preference cannot: the flash message a controller writes before the page is
 * rendered, and anything generated for this person while they are not looking at a screen.
 *
 * <p>Each language is listed under its own name and never a translated one. Somebody who
 * cannot read the current language is exactly the person who needs this control, and
 * "Vietnamese" written in English is no use to them - `Tiếng Việt` is.
 *
 * <p>Saving reloads the page rather than swapping strings in place. The catalogue is
 * fetched once before the first render and the server has already chosen a language for
 * the flash message confirming the change; re-rendering from the server is what keeps
 * those two from disagreeing on the same screen.
 */
export function LanguageForm() {
  const {locales, locale} = usePage<{
    locales: Array<{tag: string; name: string}>
    locale: string
  }>().props
  const form = useFormFields({locale})

  return (
    <Card
      title={t('auth.profile.language.title')}
      description={t('auth.profile.language.description')}
    >
      <form
        className="flex flex-col gap-3 sm:flex-row sm:items-end"
        onSubmit={(event) => {
          event.preventDefault()
          form.submit('/settings/language')
        }}
      >
        <Field
          label={t('shell.account.language')}
          error={form.error('locale')}
          className="sm:max-w-64 sm:flex-1"
        >
          <Select
            {...form.bind('locale')}
            options={locales.map((entry) => ({value: entry.tag, label: entry.name}))}
          />
        </Field>

        <Button type="submit" loading={form.processing} disabled={form.data.locale === locale}>
          {t('shell.action.save')}
        </Button>
      </form>
    </Card>
  )
}
