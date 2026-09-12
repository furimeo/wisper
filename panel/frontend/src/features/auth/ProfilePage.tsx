import {Head, Link, usePage} from '@inertiajs/react'

import {t} from '@/i18n'
import {
  Badge,
  Button,
  Card,
  CardFact,
  CardFacts,
  Input,
  PageHeader,
  RelativeTime,
  useFormFields,
} from '@/shell'

import type {AccountProfile} from './authTypes'
import {ChangePasswordForm} from './ChangePasswordForm'
import {LanguageForm} from './LanguageForm'
import {SettingsTabs} from './SettingsTabs'

/**
 * `GET /settings/profile` - who you are, as far as the panel is concerned.
 *
 * Two writes live on this screen and they belong to two controllers: the display name
 * goes to `ProfileController`, the password to `PasswordController`. They share a page
 * because they are both "my account", not because they are the same kind of change - one
 * saves a preference, the other rotates a credential and signs several browsers out.
 *
 * The address is shown and cannot be edited. Nothing in the panel changes it, so an input
 * that looked editable would be a lie; an operator does it, which is what the note says.
 */
type ProfileProps = {
  profile: AccountProfile
}

export default function ProfilePage() {
  const {profile} = usePage<ProfileProps>().props
  const form = useFormFields({displayName: profile.displayName})

  return (
    <div className="flex flex-col gap-4">
      <Head title={t('auth.profile.title')} />
      <SettingsTabs />
      <PageHeader
        title={t('auth.profile.title')}
        description={t('auth.profile.description')}
      />

      <Card
        title={t('auth.profile.nameSection')}
        description={t('auth.profile.nameDesc')}
      >
        <form
          className="flex flex-col gap-5"
          onSubmit={(event) => {
            event.preventDefault()
            form.submit('/settings/profile', {preserveState: 'errors'})
          }}
        >
          <Input
            {...form.bind('displayName')}
            label={t('auth.profile.displayName')}
            required
            maxLength={120}
            autoComplete="name"
            enterKeyHint="done"
          />

          <div>
            <span className="text-sm font-medium text-ink-700 dark:text-ink-300">
              {t('auth.profile.email')}
            </span>
            <p className="mt-1.5 font-mono text-sm break-all text-ink-700 dark:text-ink-300">
              {profile.email}
            </p>
            <p className="mt-1.5 text-xs leading-relaxed text-ink-500">
              {t('auth.profile.emailNotice')}
            </p>
          </div>

          <Button
            type="submit"
            className="w-full sm:w-auto"
            loading={form.processing}
            disabled={form.data.displayName.trim() === profile.displayName}
          >
            {form.processing ? t('auth.profile.saving') : t('auth.profile.saveName')}
          </Button>
        </form>
      </Card>

      <LanguageForm />

      <ChangePasswordForm />

      <Card
        title={t('auth.profile.accountSection')}
        action={
          profile.status === 'ACTIVE' ? (
            <Badge tone="running" dot>
              {t('auth.profile.statusActive')}
            </Badge>
          ) : (
            <Badge tone="failed" dot>
              {t('auth.profile.statusSuspended')}
            </Badge>
          )
        }
      >
        <CardFacts>
          <CardFact label={t('auth.profile.role')}>
            {profile.platformRole === 'ADMIN' ? t('auth.profile.roleAdmin') : t('auth.profile.roleCustomer')}
          </CardFact>

          <CardFact label={t('auth.profile.twoFactor')}>
            <span className="flex flex-wrap items-center gap-2">
              {profile.twoFactorEnabled ? (
                <Badge tone="running" dot>
                  {t('auth.profile.twoFactorOn')}
                </Badge>
              ) : (
                <Badge tone="degraded" dot>
                  {t('auth.profile.twoFactorOff')}
                </Badge>
              )}
              <Link
                href="/settings/security"
                className="text-accent-600 underline underline-offset-2 dark:text-accent-400"
              >
                {profile.twoFactorEnabled ? t('auth.profile.twoFactorManage') : t('auth.profile.twoFactorTurnOn')}
              </Link>
            </span>
          </CardFact>

          <CardFact label={t('auth.profile.lastSignIn')}>
            {profile.lastLoginAt ? (
              <>
                <RelativeTime at={profile.lastLoginAt} />
                {profile.lastLoginAddress ? (
                  <span className="text-ink-500">{t('auth.profile.fromIp', {ip: profile.lastLoginAddress})}</span>
                ) : null}
              </>
            ) : (
              t('auth.profile.firstSession')
            )}
          </CardFact>

          <CardFact label={t('auth.profile.passwordChanged')}>
            <RelativeTime
              at={profile.passwordChangedAt}
              fallback={t('auth.profile.passwordNeverChanged')}
            />
          </CardFact>

          {profile.lockedUntil ? (
            <CardFact label={t('auth.profile.lockedUntil')}>
              <span className="text-failed">
                <RelativeTime at={profile.lockedUntil} />{t('auth.profile.tooManyPasswords')}
              </span>
            </CardFact>
          ) : null}

          <CardFact label={t('auth.profile.accountCreated')}>
            <RelativeTime at={profile.createdAt} />
          </CardFact>
        </CardFacts>
      </Card>
    </div>
  )
}
