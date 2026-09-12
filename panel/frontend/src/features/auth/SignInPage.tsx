import {Head, usePage} from '@inertiajs/react'
import {useRef, useState} from 'react'

import {t} from '@/i18n'
import {Button, Checkbox, Input, csrfToken, cx} from '@/shell'

import type {SignInNotice} from './authTypes'

/**
 * `GET /login` - the front door.
 *
 * This is the one form in the panel that is not an Inertia visit. The POST is answered by
 * Spring Security's `UsernamePasswordAuthenticationFilter`, which runs long before the
 * dispatcher servlet and replies with a plain 302 carrying no page for the client to
 * render. So it submits the way that filter expects: an ordinary browser form with the
 * CSRF token in a hidden field, and the outcome comes back as the `notice` prop that
 * `SignInController` builds from the query string.
 *
 * That also means password managers see a real navigation and offer to save the
 * credential, which they do not reliably do for an XHR sign-in.
 *
 * No chrome: `main.tsx` lists this component in `BARE_PAGES`, because there is nothing to
 * navigate to yet. That also means no `Toaster`, which is why the banner below is drawn
 * here rather than raised as a notice.
 */
type SignInProps = {
  notice: SignInNotice | null
}

const TONES: Record<SignInNotice['tone'], string> = {
  error: 'border-failed/40 bg-failed/10',
  info: 'border-accent-500/40 bg-accent-500/10',
  success: 'border-running/40 bg-running/10',
}

export default function SignInPage() {
  const {notice} = usePage<SignInProps>().props
  const [revealed, setRevealed] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const token = useRef<HTMLInputElement>(null)

  return (
    <main className="flex min-h-dvh flex-col justify-center px-5 py-10">
      <Head title={t('auth.signIn.title')} />

      <div className="mx-auto w-full max-w-sm">
        <p className="font-mono text-sm text-accent-600 dark:text-accent-400">wisper</p>
        <h1 className="mt-1 text-2xl font-semibold tracking-tight">{t('auth.signIn.title')}</h1>

        {notice ? (
          <p
            role="alert"
            className={cx(
              'mt-5 rounded-lg border px-4 py-3 text-sm leading-relaxed',
              'text-ink-800 dark:text-ink-100',
              TONES[notice.tone],
            )}
          >
            {notice.message}
          </p>
        ) : null}

        <form
          method="post"
          action="/login"
          className="mt-6 flex flex-col gap-5"
          onSubmit={() => {
            /*
             * Read the cookie at submit time, not at mount. A form left open across a
             * token rotation would otherwise post a value the server has stopped
             * expecting, and the customer would see a 403 instead of a sign-in.
             */
            if (token.current) {
              token.current.value = csrfToken() ?? ''
            }
            setSubmitting(true)
          }}
        >
          <input ref={token} type="hidden" name="_csrf" defaultValue={csrfToken() ?? ''} />

          <Input
            name="username"
            label={t('auth.signIn.email')}
            type="email"
            required
            autoFocus
            inputMode="email"
            autoComplete="username"
            autoCapitalize="none"
            autoCorrect="off"
            spellCheck={false}
            enterKeyHint="next"
            placeholder={t('auth.signIn.emailPlaceholder')}
          />

          <div className="flex flex-col gap-1">
            <Input
              name="password"
              label={t('auth.signIn.password')}
              type={revealed ? 'text' : 'password'}
              required
              autoComplete="current-password"
              enterKeyHint="go"
            />
            {/*
              A checkbox rather than an eye inside the field: at 375px the overlay sits
              where a thumb reaching for the end of the text already is, and a mis-tap
              there reveals the password to the room.
            */}
            <Checkbox
              label={t('auth.signIn.showPassword')}
              checked={revealed}
              onChange={(event) => setRevealed(event.target.checked)}
            />
          </div>

          <Button type="submit" block loading={submitting}>
            {submitting ? t('auth.signIn.submitting') : t('auth.signIn.submit')}
          </Button>
        </form>

        <p className="mt-6 text-xs leading-relaxed text-ink-500">
          {t('auth.signIn.footer')}
        </p>
      </div>
    </main>
  )
}
