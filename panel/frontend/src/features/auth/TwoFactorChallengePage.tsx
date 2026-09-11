import {Head, router, usePage} from '@inertiajs/react'
import {useState} from 'react'

import {Button, Input, useFormFields} from '@/shell'

/**
 * `GET /login/two-factor` - the second step, once the password has been accepted.
 *
 * One input takes both kinds of secret, which is what `TwoFactorChallengeController`
 * expects: six digits from the authenticator, or one of the ten-character recovery codes.
 * Switching between them changes only the keyboard, the autocomplete hint and the help
 * text - a phone that offers a numeric keypad for a code containing letters is a small
 * cruelty, and two separate fields would make somebody decide which kind of thing they
 * are holding before they can type it.
 *
 * Five wrong codes throw the half-finished sign-in away and send the browser back to the
 * password step. The server counts, and it counts per sign-in rather than per account, so
 * nobody can lock somebody else out by failing this step repeatedly.
 *
 * No chrome, for the same reason as the sign-in page: this session may not reach anything
 * else yet. The error therefore has to be visible on the field itself - there is no
 * `Toaster` mounted here to carry it.
 */
type ChallengeProps = {
  email: string
  recoveryCodesRemaining: number
}

type CodeKind = 'authenticator' | 'recovery'

export default function TwoFactorChallengePage() {
  const {email, recoveryCodesRemaining} = usePage<ChallengeProps>().props
  const [kind, setKind] = useState<CodeKind>('authenticator')
  const form = useFormFields({code: ''})

  const authenticator = kind === 'authenticator'

  function switchTo(next: CodeKind) {
    setKind(next)
    form.set('code', '')
  }

  return (
    <main className="flex min-h-dvh flex-col justify-center px-5 py-10">
      <Head title="Confirm it is you" />

      <div className="mx-auto w-full max-w-sm">
        <p className="font-mono text-sm text-accent-600 dark:text-accent-400">wisper</p>
        <h1 className="mt-1 text-2xl font-semibold tracking-tight">Confirm it is you</h1>
        <p className="mt-2 text-sm leading-relaxed text-ink-600 dark:text-ink-400">
          Signed in as{' '}
          <span className="font-medium break-all text-ink-900 dark:text-ink-100">{email}</span>.{' '}
          {authenticator
            ? 'Open your authenticator app and enter the current six-digit code.'
            : 'Enter one of the recovery codes you saved when you turned this on.'}
        </p>

        <form
          className="mt-6 flex flex-col gap-5"
          onSubmit={(event) => {
            event.preventDefault()
            /*
             * `preserveState: 'errors'` keeps the component mounted only when the code
             * came back rejected. Without it Inertia remounts the page, `kind` resets,
             * and somebody who mistyped a recovery code is thrown back to the
             * authenticator field they had already said they could not reach.
             */
            form.submit('/login/two-factor', {preserveState: 'errors'})
          }}
        >
          <Input
            {...form.bind('code')}
            label={authenticator ? 'Six-digit code' : 'Recovery code'}
            required
            autoFocus
            enterKeyHint="go"
            autoCapitalize="characters"
            autoCorrect="off"
            spellCheck={false}
            /*
             * `one-time-code` is what makes iOS and Android offer the code straight from
             * the notification. It is wrong for a recovery code, which is read off paper,
             * so it is switched off in that mode rather than left lying.
             */
            autoComplete={authenticator ? 'one-time-code' : 'off'}
            inputMode={authenticator ? 'numeric' : 'text'}
            maxLength={authenticator ? 6 : 13}
            placeholder={authenticator ? '000000' : 'AB3CD-EF7GH'}
            className="text-center font-mono text-lg tracking-[0.3em]"
            hint={
              authenticator
                ? 'Codes change every 30 seconds. If yours keeps being rejected, check that your phone’s clock is set automatically.'
                : 'Ten characters, written with a hyphen in the middle. Each one works once.'
            }
          />

          <Button type="submit" block loading={form.processing}>
            {form.processing ? 'Checking…' : 'Continue'}
          </Button>
        </form>

        <div className="mt-6 flex flex-col gap-3 border-t border-ink-200 pt-5 dark:border-ink-800">
          <Button
            variant="secondary"
            block
            onClick={() => switchTo(authenticator ? 'recovery' : 'authenticator')}
          >
            {authenticator ? 'Use a recovery code instead' : 'Back to the authenticator code'}
          </Button>

          <p className="text-xs leading-relaxed text-ink-500">
            {recoveryCodesRemaining === 0
              ? 'You have no recovery codes left. If you cannot reach your authenticator, the platform operator has to turn the second factor off for you.'
              : `${recoveryCodesRemaining} recovery code${recoveryCodesRemaining === 1 ? '' : 's'} left.`}
          </p>

          {/*
            The way back to a different account. `SecurityConfig` maps /logout as a POST,
            and the Inertia client carries the CSRF header on it, so this is an ordinary
            visit rather than the hand-built form the sign-in page needs.
          */}
          <Button variant="ghost" block onClick={() => router.post('/logout')}>
            Sign out and start again
          </Button>
        </div>
      </div>
    </main>
  )
}
