import {Head, Link, usePage} from '@inertiajs/react'

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
      <Head title="Profile" />
      <SettingsTabs />
      <PageHeader
        title="Profile"
        description="Your name, your password, and the facts the panel records about this account."
      />

      <Card
        title="Name and address"
        description="The name appears next to anything you do in the panel and in the audit log."
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
            label="Display name"
            required
            maxLength={120}
            autoComplete="name"
            enterKeyHint="done"
          />

          <div>
            <span className="text-sm font-medium text-ink-700 dark:text-ink-300">
              Email address
            </span>
            <p className="mt-1.5 font-mono text-sm break-all text-ink-700 dark:text-ink-300">
              {profile.email}
            </p>
            <p className="mt-1.5 text-xs leading-relaxed text-ink-500">
              This is also your sign-in name. Ask the platform operator if it needs to
              change.
            </p>
          </div>

          <Button
            type="submit"
            className="w-full sm:w-auto"
            loading={form.processing}
            disabled={form.data.displayName.trim() === profile.displayName}
          >
            {form.processing ? 'Saving…' : 'Save name'}
          </Button>
        </form>
      </Card>

      <LanguageForm />

      <ChangePasswordForm />

      <Card
        title="Account"
        action={
          profile.status === 'ACTIVE' ? (
            <Badge tone="running" dot>
              Active
            </Badge>
          ) : (
            <Badge tone="failed" dot>
              Suspended
            </Badge>
          )
        }
      >
        <CardFacts>
          <CardFact label="Role">
            {profile.platformRole === 'ADMIN' ? 'Platform operator' : 'Customer'}
          </CardFact>

          <CardFact label="Two-factor authentication">
            <span className="flex flex-wrap items-center gap-2">
              {profile.twoFactorEnabled ? (
                <Badge tone="running" dot>
                  On
                </Badge>
              ) : (
                <Badge tone="degraded" dot>
                  Off
                </Badge>
              )}
              <Link
                href="/settings/security"
                className="text-accent-600 underline underline-offset-2 dark:text-accent-400"
              >
                {profile.twoFactorEnabled ? 'Manage' : 'Turn it on'}
              </Link>
            </span>
          </CardFact>

          <CardFact label="Last signed in">
            {profile.lastLoginAt ? (
              <>
                <RelativeTime at={profile.lastLoginAt} />
                {profile.lastLoginAddress ? (
                  <span className="text-ink-500"> from {profile.lastLoginAddress}</span>
                ) : null}
              </>
            ) : (
              'This is your first session'
            )}
          </CardFact>

          <CardFact label="Password last changed">
            <RelativeTime
              at={profile.passwordChangedAt}
              fallback="Never - still the one you were given"
            />
          </CardFact>

          {profile.lockedUntil ? (
            <CardFact label="Locked until">
              <span className="text-failed">
                <RelativeTime at={profile.lockedUntil} /> - too many wrong passwords
              </span>
            </CardFact>
          ) : null}

          <CardFact label="Account created">
            <RelativeTime at={profile.createdAt} />
          </CardFact>
        </CardFacts>
      </Card>
    </div>
  )
}
