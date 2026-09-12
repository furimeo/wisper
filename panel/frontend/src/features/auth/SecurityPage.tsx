import {Head, usePage} from '@inertiajs/react'

import {t} from '@/i18n'
import {PageHeader} from '@/shell'

import type {AccountProfile, SessionView, TwoFactorEnrolment} from './authTypes'
import {RecoveryCodesPanel} from './RecoveryCodesPanel'
import {SessionList} from './SessionList'
import {SettingsTabs} from './SettingsTabs'
import {TotpEnrolment} from './TotpEnrolment'

/**
 * `GET /settings/security` - the second factor, the recovery codes and the browsers.
 *
 * `SecuritySettingsController` only reads; the writes belong to
 * `TwoFactorSettingsController` and `SessionSettingsController`, which all redirect back
 * here. Two of the props never come from the database at all: `enrolment` and
 * `recoveryCodes` are flash attributes, present on exactly the render that follows the
 * write that produced them and unreadable afterwards. Both are typed optional for that
 * reason, and both sections have a real thing to show when they are absent.
 */
type SecurityProps = {
  profile: AccountProfile
  sessions: SessionView[]
  recoveryCodesRemaining: number
  recoveryCodesIssued: number
  enrolmentPending: boolean
  enrolment?: TwoFactorEnrolment
  recoveryCodes?: string[]
}

export default function SecurityPage() {
  const {
    profile,
    sessions,
    recoveryCodesRemaining,
    recoveryCodesIssued,
    enrolmentPending,
    enrolment,
    recoveryCodes,
  } = usePage<SecurityProps>().props

  return (
    <div className="flex flex-col gap-4">
      <Head title={t('auth.security.title')} />
      <SettingsTabs />
      <PageHeader
        title={t('auth.security.title')}
        description={t('auth.security.description', {email: profile.email})}
      />

      <TotpEnrolment
        enabled={profile.twoFactorEnabled}
        pending={enrolmentPending}
        enrolment={enrolment}
      />

      <RecoveryCodesPanel
        codes={recoveryCodes}
        remaining={recoveryCodesRemaining}
        issued={recoveryCodesIssued}
        twoFactorEnabled={profile.twoFactorEnabled}
      />

      <SessionList sessions={sessions} />
    </div>
  )
}
