import {Badge, Button, Card, CopyButton, Input, useFormFields} from '@/shell'

import type {TwoFactorEnrolment} from './authTypes'
import {QrCode} from './QrCode'

/**
 * The second-factor section of `/settings/security`, in all of its states.
 *
 * Enrolment is two steps because the secret has to reach the phone before the panel can
 * know the phone has it, and the half-finished state in between is real: a secret exists,
 * `totp_confirmed_at` is still null, and `Account.hasSecondFactor()` answers false. The
 * page has to be able to render that, including after a reload that lost the QR code -
 * which is what `pending` without an `enrolment` prop means, and why "show a new QR code"
 * is offered rather than a confirm box for a secret nobody can see any more.
 *
 * Three ways to get the secret into an app, because no single one works for everybody:
 *
 * - the `otpauth://` link, which opens the authenticator directly and is the only one
 *   that works when the panel is open on the same phone as the app;
 * - the QR code, for a second device with a camera;
 * - the secret in text, for an app that will not scan and for a password manager.
 */
type TotpEnrolmentProps = {
  enabled: boolean
  /** A secret exists but was never confirmed. */
  pending: boolean
  /** Present only on the render that follows "start setup". */
  enrolment?: TwoFactorEnrolment | undefined
}

export function TotpEnrolment({enabled, pending, enrolment}: TotpEnrolmentProps) {
  const begin = useFormFields({})
  const confirm = useFormFields({code: ''})
  const disable = useFormFields({currentPassword: ''})

  if (enabled) {
    return (
      <Card
        title="Two-factor authentication"
        description="Signing in asks for a code from your authenticator after your password."
        action={
          <Badge tone="running" dot>
            On
          </Badge>
        }
      >
        <form
          className="flex flex-col gap-5"
          onSubmit={(event) => {
            event.preventDefault()
            disable.submit('/settings/security/two-factor/disable', {
              preserveState: 'errors',
            })
          }}
        >
          <p className="text-sm leading-relaxed text-ink-600 dark:text-ink-400">
            Turning it off discards your recovery codes as well. Your password is required,
            because this is the change worth taking an account for.
          </p>
          <Input
            {...disable.bind('currentPassword')}
            label="Your password"
            type="password"
            required
            autoComplete="current-password"
            enterKeyHint="done"
          />
          <Button
            type="submit"
            variant="danger"
            className="w-full sm:w-auto"
            loading={disable.processing}
          >
            {disable.processing ? 'Turning off…' : 'Turn off two-factor authentication'}
          </Button>
        </form>
      </Card>
    )
  }

  const startSetup = (label: string, variant: 'primary' | 'secondary') => (
    <Button
      variant={variant}
      className="w-full sm:w-auto"
      loading={begin.processing}
      onClick={() => begin.submit('/settings/security/two-factor/begin')}
    >
      {begin.processing ? 'Preparing…' : label}
    </Button>
  )

  if (!pending && !enrolment) {
    return (
      <Card
        title="Two-factor authentication"
        description="A code from your phone on top of your password. It is the single change that
          most reduces the chance of somebody else getting into this account."
        action={
          <Badge tone="degraded" dot>
            Off
          </Badge>
        }
      >
        {startSetup('Turn it on', 'primary')}
      </Card>
    )
  }

  return (
    <Card
      title="Two-factor authentication"
      description="Add the secret to your authenticator, then type the code it shows. Nothing is
        switched on until that code verifies."
      action={
        <Badge tone="degraded" dot>
          Not finished
        </Badge>
      }
    >
      <div className="flex flex-col gap-5">
        {enrolment ? (
          <div className="flex flex-col gap-4">
            {/*
              The panel is usually open on the same phone as the authenticator, and this
              hands the secret straight to it. rel="nofollow" because the href holds the
              secret and there is nothing here worth a crawler following.
            */}
            <a
              href={enrolment.provisioningUri}
              rel="nofollow"
              className="inline-flex w-full touch-target items-center justify-center rounded-lg bg-accent-600 px-4 text-sm font-medium text-white hover:bg-accent-500"
            >
              Open my authenticator app
            </a>

            <div className="flex flex-col items-center gap-3">
              <QrCode
                value={enrolment.provisioningUri}
                label="QR code containing the setup secret for this account"
                className="size-56 rounded-lg border border-ink-200 dark:border-ink-700"
              />
              <p className="text-center text-xs leading-relaxed text-ink-500">
                Scan this from a second device. On the phone you are reading this on, use
                the button above instead.
              </p>
            </div>

            <div>
              <p className="text-sm font-medium text-ink-700 dark:text-ink-300">
                Or type the secret in
              </p>
              <p className="mt-1.5 rounded-lg bg-ink-100 px-3 py-2.5 font-mono text-sm break-all select-all dark:bg-ink-950">
                {enrolment.secret}
              </p>
              <div className="mt-2">
                <CopyButton
                  value={enrolment.secret.replace(/\s+/g, '')}
                  label="Copy secret"
                  describedAs="Copy the two-factor setup secret"
                />
              </div>
              <p className="mt-2 text-xs leading-relaxed text-ink-500">
                Time-based, SHA-1, six digits, thirty seconds - the defaults every
                authenticator uses. Spaces in the secret are for reading; type it without
                them if your app objects.
              </p>
            </div>
          </div>
        ) : (
          <p className="rounded-lg border border-degraded/40 bg-degraded/10 px-4 py-3 text-sm leading-relaxed text-ink-800 dark:text-ink-100">
            Setup was started but never finished, and the QR code is only ever shown once.
            If it is already in your authenticator, enter a code below. Otherwise start
            again to get a new one - the old secret stops working the moment you do.
          </p>
        )}

        <form
          className="flex flex-col gap-4 border-t border-ink-200 pt-5 dark:border-ink-800"
          onSubmit={(event) => {
            event.preventDefault()
            confirm.submit('/settings/security/two-factor/confirm', {
              preserveState: 'errors',
            })
          }}
        >
          <Input
            {...confirm.bind('code')}
            label="Code from your authenticator"
            required
            inputMode="numeric"
            autoComplete="one-time-code"
            maxLength={6}
            placeholder="000000"
            enterKeyHint="done"
            className="font-mono tracking-[0.3em]"
          />
          <Button
            type="submit"
            className="w-full sm:w-auto"
            loading={confirm.processing}
          >
            {confirm.processing ? 'Checking…' : 'Turn on two-factor authentication'}
          </Button>
        </form>

        <div className="border-t border-ink-200 pt-4 dark:border-ink-800">
          {startSetup(
            enrolment ? 'Start again with a new secret' : 'Show a new QR code',
            'secondary',
          )}
        </div>
      </div>
    </Card>
  )
}
