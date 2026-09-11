import {useState} from 'react'

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
      title="Password"
      description="Changing it signs out every other browser you are signed in to. This one stays."
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
          label="Current password"
          type="password"
          required
          autoComplete="current-password"
          enterKeyHint="next"
        />

        <Input
          {...form.bind('newPassword')}
          label="New password"
          type={revealed ? 'text' : 'password'}
          required
          minLength={MINIMUM}
          autoComplete="new-password"
          enterKeyHint="next"
          hint={`At least ${MINIMUM} characters. A few unrelated words is easier to remember and harder to guess than a short one with symbols in it.`}
        />

        <Input
          {...form.bind('confirmPassword')}
          label="New password again"
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
          label="Show the new password"
          checked={revealed}
          onChange={(event) => setRevealed(event.target.checked)}
        />

        <Button type="submit" className="w-full sm:w-auto" loading={form.processing}>
          {form.processing ? 'Changing…' : 'Change password'}
        </Button>
      </form>
    </Card>
  )
}
