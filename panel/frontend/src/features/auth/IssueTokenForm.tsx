import {useForm} from '@inertiajs/react'

import {Button, Card, Input, Select} from '@/shell'

import type {AccountOrganization, ApiScope} from './authTypes'
import {ScopePicker} from './ScopePicker'

/**
 * `POST /settings/tokens` - the create form.
 *
 * <h2>Why this one form does not use `useFormFields`</h2>
 *
 * `ApiTokenSettingsController` binds `@RequestParam List<String> scopes`, which needs the
 * body to repeat a parameter literally named `scopes`. Inertia serialises an array in an
 * ordinary payload as `scopes[]` (or `scopes[0]` under `queryStringArrayFormat:
 * 'indices'`), and Spring binds neither. The one payload it passes through untouched is a
 * `FormData` the caller built itself, and `transform` is the hook that can hand it one -
 * `useFormFields.submit` always posts its own object. Everything else on these screens
 * uses the shell hook; this is the exception, and it is here rather than solved by
 * comma-joining because a scope with a comma in it would then break silently.
 */
type IssueTokenFormProps = {
  organizations: AccountOrganization[]
  /** The scopes this account may ask for; `nodes:*` only for a platform operator. */
  scopes: ApiScope[]
  /** Only a platform operator may leave the organization blank. */
  mayIssueUnscoped: boolean
}

/** The longest life `ApiTokenSettingsController` accepts. */
const MAX_EXPIRY_DAYS = 3650

export function IssueTokenForm({organizations, scopes, mayIssueUnscoped}: IssueTokenFormProps) {
  const form = useForm({
    name: '',
    organizationId: organizations.length === 1 ? (organizations[0]?.id ?? '') : '',
    scopes: [] as string[],
    expiresInDays: '90',
  })

  // `IssueApiToken` reports one message against `expiresAt`, which is not a field on this
  // form, so the map is read by name rather than through the hook's key-typed view.
  const errors = form.errors as unknown as Record<string, string | undefined>
  const noOrganizations = organizations.length === 0 && !mayIssueUnscoped

  if (noOrganizations) {
    return (
      <Card title="New token">
        <p className="rounded-lg border border-degraded/40 bg-degraded/10 px-4 py-3 text-sm leading-relaxed">
          You are not a member of any organization yet, and a token has to act in one.
          Accept an invitation first, and this form will work.
        </p>
      </Card>
    )
  }

  return (
    <Card
      title="New token"
      description="A token acts as you, limited to the permissions and the organization you give
        it here. Every call it makes is recorded in the audit log against its name."
    >
      <form
        className="flex flex-col gap-5"
        onSubmit={(event) => {
          event.preventDefault()
          form.transform((data) => {
            const body = new FormData()
            body.append('name', data.name)
            body.append('organizationId', data.organizationId)
            body.append('expiresInDays', data.expiresInDays)
            // Repeated, under the plain name, which is what Spring binds a List from.
            for (const scope of data.scopes) {
              body.append('scopes', scope)
            }
            return body
          })
          form.post('/settings/tokens', {
            preserveScroll: true,
            preserveState: 'errors',
            onSuccess: () => form.reset(),
          })
        }}
      >
        <Input
          name="name"
          label="Name"
          required
          maxLength={80}
          autoComplete="off"
          placeholder="CI deploys"
          enterKeyHint="next"
          value={form.data.name}
          onChange={(event) => form.setData('name', event.target.value)}
          error={errors.name}
          hint="What you will recognise it by in this list a year from now."
        />

        <Select
          name="organizationId"
          label="Organization"
          required={!mayIssueUnscoped}
          value={form.data.organizationId}
          onChange={(event) => form.setData('organizationId', event.target.value)}
          error={errors.organizationId}
          hint={
            mayIssueUnscoped
              ? 'Leave it blank for a token that is not tied to one tenant. Only a platform operator may do that.'
              : 'The token can only act inside this organization.'
          }
        >
          <option value="">
            {mayIssueUnscoped ? 'Platform-wide (no organization)' : 'Choose one…'}
          </option>
          {organizations.map((organization) => (
            <option key={organization.id} value={organization.id}>
              {organization.name}
            </option>
          ))}
        </Select>

        <ScopePicker
          available={scopes}
          chosen={form.data.scopes}
          error={errors.scopes}
          onToggle={(scope, on) =>
            form.setData(
              'scopes',
              on
                ? [...form.data.scopes, scope]
                : form.data.scopes.filter((held) => held !== scope),
            )
          }
        />

        {/*
          `type="number"` rather than a text field with a pattern: the server binds this
          to an `Integer`, and letters would come back as a type-mismatch error page
          instead of a message under the field. The browser refuses them first.
        */}
        <Input
          name="expiresInDays"
          label="Expires after (days)"
          type="number"
          inputMode="numeric"
          min={1}
          max={MAX_EXPIRY_DAYS}
          step={1}
          enterKeyHint="done"
          value={form.data.expiresInDays}
          onChange={(event) => form.setData('expiresInDays', event.target.value)}
          error={errors.expiresInDays ?? errors.expiresAt}
          hint={`Up to ${MAX_EXPIRY_DAYS}. Leave it blank for a token that never expires - a
            short life is one fewer secret to have left lying around.`}
        />

        <Button type="submit" className="w-full sm:w-auto" loading={form.processing}>
          {form.processing ? 'Creating…' : 'Create token'}
        </Button>
      </form>
    </Card>
  )
}
