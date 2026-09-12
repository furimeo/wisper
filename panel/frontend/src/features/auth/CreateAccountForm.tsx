import {useState} from 'react'

import {t} from '@/i18n'
import {Button, Card, Checkbox, CopyButton, Input, Select, useFormFields} from '@/shell'

import type {PlatformRole} from './authTypes'
import {generatePassword} from './generatePassword'

/**
 * `POST /admin/accounts` - the only door new accounts come through.
 *
 * There is no public sign-up, so an operator types the address and sets the first
 * password. The panel deliberately does not mail it: there is no mail configuration in
 * wisper, and a feature that half-works here would be a password sitting in a queue
 * nobody drains. The copy button and the wording carry that instead.
 */
type CreateAccountFormProps = {
  /** `PlatformRole` names, in the order `AdminAccountController` sends them. */
  roles: PlatformRole[]
}

/** `PasswordPolicy.MINIMUM_CHARACTERS` on the Java side. */
const MINIMUM = 12

export function CreateAccountForm({roles}: CreateAccountFormProps) {
  const [revealed, setRevealed] = useState(false)
  const form = useFormFields({
    email: '',
    displayName: '',
    password: '',
    platformRole: (roles[0] ?? 'CUSTOMER') as string,
  })

  const roleLabels: Record<PlatformRole, string> = {
    CUSTOMER: t('auth.admin.roleCustomer'),
    ADMIN: t('auth.admin.roleAdmin'),
  }

  return (
    <Card
      title={t('auth.admin.createTitle')}
      description={t('auth.admin.createDesc')}
    >
      <form
        className="flex flex-col gap-5"
        onSubmit={(event) => {
          event.preventDefault()
          form.submit('/admin/accounts', {
            preserveState: 'errors',
            onSuccess: () => {
              form.reset()
              setRevealed(false)
            },
          })
        }}
      >
        <Input
          {...form.bind('email')}
          label={t('auth.admin.createEmail')}
          type="email"
          required
          inputMode="email"
          autoComplete="off"
          autoCapitalize="none"
          autoCorrect="off"
          spellCheck={false}
          enterKeyHint="next"
          placeholder="them@example.com"
          hint={t('auth.admin.createEmailHint')}
        />

        <Input
          {...form.bind('displayName')}
          label={t('auth.admin.createName')}
          required
          maxLength={120}
          autoComplete="off"
          enterKeyHint="next"
        />

        <div className="flex flex-col gap-2">
          <Input
            {...form.bind('password')}
            label={t('auth.admin.firstPassword')}
            type={revealed ? 'text' : 'password'}
            required
            minLength={MINIMUM}
            autoComplete="new-password"
            enterKeyHint="next"
            className="font-mono"
            hint={t('auth.admin.firstPasswordHint', {min: MINIMUM})}
          />
          <div className="flex flex-wrap items-center gap-2">
            <Button
              variant="secondary"
              onClick={() => {
                form.set('password', generatePassword())
                setRevealed(true)
              }}
            >
              {t('auth.admin.generatePassword')}
            </Button>
            {form.data.password ? (
              <CopyButton
                value={form.data.password}
                label={t('auth.admin.copyPassword')}
                describedAs={t('auth.admin.copyPasswordDesc')}
              />
            ) : null}
          </div>
          <Checkbox
            label={t('auth.admin.showPassword')}
            checked={revealed}
            onChange={(event) => setRevealed(event.target.checked)}
          />
        </div>

        <Select
          {...form.bind('platformRole')}
          label={t('auth.admin.role')}
          options={roles.map((role) => ({value: role, label: roleLabels[role]}))}
          hint={t('auth.admin.roleHint')}
        />

        <Button type="submit" className="w-full sm:w-auto" loading={form.processing}>
          {form.processing ? t('auth.admin.creating') : t('auth.admin.createBtn')}
        </Button>
      </form>
    </Card>
  )
}
