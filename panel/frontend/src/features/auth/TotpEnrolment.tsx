import {t} from '@/i18n'
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
        title={t('auth.totp.title')}
        description={t('auth.totp.descOn')}
        action={
          <Badge tone="running" dot>
            {t('auth.totp.badgeOn')}
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
            {t('auth.totp.offNotice')}
          </p>
          <Input
            {...disable.bind('currentPassword')}
            label={t('auth.totp.yourPassword')}
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
            {disable.processing ? t('auth.totp.turningOff') : t('auth.totp.turnOff')}
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
      {begin.processing ? t('auth.totp.preparing') : label}
    </Button>
  )

  if (!pending && !enrolment) {
    return (
      <Card
        title={t('auth.totp.title')}
        description={t('auth.totp.descOff')}
        action={
          <Badge tone="degraded" dot>
            {t('auth.totp.badgeOff')}
          </Badge>
        }
      >
        {startSetup(t('auth.totp.turnOn'), 'primary')}
      </Card>
    )
  }

  return (
    <Card
      title={t('auth.totp.title')}
      description={t('auth.totp.descPending')}
      action={
        <Badge tone="degraded" dot>
          {t('auth.totp.badgeNotFinished')}
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
              {t('auth.totp.openApp')}
            </a>

            <div className="flex flex-col items-center gap-3">
              <QrCode
                value={enrolment.provisioningUri}
                label={t('auth.totp.qrAria')}
                className="size-56 rounded-lg border border-ink-200 dark:border-ink-700"
              />
              <p className="text-center text-xs leading-relaxed text-ink-500">
                {t('auth.totp.qrScanNotice')}
              </p>
            </div>

            <div>
              <p className="text-sm font-medium text-ink-700 dark:text-ink-300">
                {t('auth.totp.typeSecret')}
              </p>
              <p className="mt-1.5 rounded-lg bg-ink-100 px-3 py-2.5 font-mono text-sm break-all select-all dark:bg-ink-950">
                {enrolment.secret}
              </p>
              <div className="mt-2">
                <CopyButton
                  value={enrolment.secret.replace(/\s+/g, '')}
                  label={t('auth.totp.copySecret')}
                  describedAs={t('auth.totp.copySecretDesc')}
                />
              </div>
              <p className="mt-2 text-xs leading-relaxed text-ink-500">
                {t('auth.totp.secretExplanation')}
              </p>
            </div>
          </div>
        ) : (
          <p className="rounded-lg border border-degraded/40 bg-degraded/10 px-4 py-3 text-sm leading-relaxed text-ink-800 dark:text-ink-100">
            {t('auth.totp.pendingWarning')}
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
            label={t('auth.totp.codeLabel')}
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
            {confirm.processing ? t('auth.totp.checking') : t('auth.totp.turnOn')}
          </Button>
        </form>

        <div className="border-t border-ink-200 pt-4 dark:border-ink-800">
          {startSetup(
            enrolment ? t('auth.totp.startAgain') : t('auth.totp.showNewQr'),
            'secondary',
          )}
        </div>
      </div>
    </Card>
  )
}
