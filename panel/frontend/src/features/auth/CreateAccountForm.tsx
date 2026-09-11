import {useState} from 'react'

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

const ROLE_LABELS: Record<PlatformRole, string> = {
  CUSTOMER: 'Customer',
  ADMIN: 'Platform operator - every tenant, every node',
}

export function CreateAccountForm({roles}: CreateAccountFormProps) {
  const [revealed, setRevealed] = useState(false)
  const form = useFormFields({
    email: '',
    displayName: '',
    password: '',
    platformRole: (roles[0] ?? 'CUSTOMER') as string,
  })

  return (
    <Card
      title="Create an account"
      description="Tell them the password over something that is not this panel, and ask them to
        change it on their first sign-in."
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
          label="Email address"
          type="email"
          required
          inputMode="email"
          autoComplete="off"
          autoCapitalize="none"
          autoCorrect="off"
          spellCheck={false}
          enterKeyHint="next"
          placeholder="them@example.com"
          hint="Also their sign-in name."
        />

        <Input
          {...form.bind('displayName')}
          label="Name"
          required
          maxLength={120}
          autoComplete="off"
          enterKeyHint="next"
        />

        <div className="flex flex-col gap-2">
          <Input
            {...form.bind('password')}
            label="First password"
            type={revealed ? 'text' : 'password'}
            required
            minLength={MINIMUM}
            autoComplete="new-password"
            enterKeyHint="next"
            className="font-mono"
            hint={`At least ${MINIMUM} characters, and at most 72 bytes - past that, BCrypt stops reading.`}
          />
          <div className="flex flex-wrap items-center gap-2">
            <Button
              variant="secondary"
              onClick={() => {
                form.set('password', generatePassword())
                setRevealed(true)
              }}
            >
              Generate one
            </Button>
            {form.data.password ? (
              <CopyButton
                value={form.data.password}
                label="Copy password"
                describedAs="Copy the first password for this account"
              />
            ) : null}
          </div>
          <Checkbox
            label="Show password"
            checked={revealed}
            onChange={(event) => setRevealed(event.target.checked)}
          />
        </div>

        <Select
          {...form.bind('platformRole')}
          label="Role"
          options={roles.map((role) => ({value: role, label: ROLE_LABELS[role]}))}
          hint="An operator can reach every organization, every node and the audit log. Give it to
            as few people as the platform can be run with."
        />

        <Button type="submit" className="w-full sm:w-auto" loading={form.processing}>
          {form.processing ? 'Creating…' : 'Create account'}
        </Button>
      </form>
    </Card>
  )
}
