import {useState} from 'react'

import {t} from '@/i18n'
import {Button, Card, Checkbox, Input, useFormFields} from '@/shell'

/**
 * `POST /settings/password`.
 *
 * Three fields, because the confirmation is checked server-side by `PasswordController`
 * and reported as an error on `confirmPassword`; repeating that check here would be a
 * second copy of a rule that can disagree with the first.
 *
 * The browser making the change keeps its session and every other one is ended, which is
 * said above the fields rather than in the confirmation afterwards - somebody who did not
 * want that needs to know before they press the button, not after their other tab has
 * been signed out.
 *
 * `autoComplete` matters more than it looks: `current-password` and `new-password` are
 * what tell a password manager to fill the first box and to offer to generate and then
 * save the second. Getting them wrong produces a form that silently defeats the tool most
 * people rely on to have a strong password at all.
 */

/** `PasswordPolicy.MINIMUM_CHARACTERS` on the Java side. */
const MINIMUM = 12

export function ChangePasswordForm() {
  const [revealed, setRevealed] = useState(false)
  const form = useFormFields({currentPassword: '', newPassword: '', confirmPassword: ''})

  return (
    <Card
      title={t('auth.password.title')}
      description={t('auth.password.description')}
    >
      <form
        className="flex flex-col gap-5"
        onSubmit={(event) => {
          event.preventDefault()
          form.submit('/settings/password', {
            preserveState: 'errors',
            onSuccess: () => {
              form.reset()
              setRevealed(false)
            },
          })
        }}
      >
        <Input
          {...form.bind('currentPassword')}
          label={t('auth.password.current')}
          type="password"
          required
          autoComplete="current-password"
          enterKeyHint="next"
        />

        <Input
          {...form.bind('newPassword')}
          label={t('auth.password.new')}
          type={revealed ? 'text' : 'password'}
          required
          minLength={MINIMUM}
          autoComplete="new-password"
          enterKeyHint="next"
          hint={t('auth.password.hint', {min: MINIMUM})}
        />

        <Input
          {...form.bind('confirmPassword')}
          label={t('auth.password.confirm')}
          type={revealed ? 'text' : 'password'}
          required
          autoComplete="new-password"
          enterKeyHint="done"
        />

        {/*
          One toggle for both new-password fields. Retyping a generated passphrase blind
          on a phone keyboard is where "the two passwords do not match" comes from.
        */}
        <Checkbox
          label={t('auth.password.show')}
          checked={revealed}
          onChange={(event) => setRevealed(event.target.checked)}
        />

        <Button type="submit" className="w-full sm:w-auto" loading={form.processing}>
          {form.processing ? t('auth.password.changing') : t('auth.password.change')}
        </Button>
      </form>
    </Card>
  )
}
