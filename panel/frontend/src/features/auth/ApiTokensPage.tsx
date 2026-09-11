import {Head, usePage} from '@inertiajs/react'

import {PageHeader} from '@/shell'

import type {AccountOrganization, ApiScope, ApiTokenView} from './authTypes'
import {ApiTokenList} from './ApiTokenList'
import {IssueTokenForm} from './IssueTokenForm'
import {IssuedTokenBanner} from './IssuedTokenBanner'
import {SettingsTabs} from './SettingsTabs'

/**
 * `GET /settings/tokens` - the tokens that let something other than a browser in.
 *
 * `issuedToken` and `issuedTokenName` are flash attributes: present on the render that
 * follows a successful create, absent on every other. When they are here the new token
 * goes above everything else, because copying it is the only thing that matters on that
 * render and it cannot be done later.
 */
type ApiTokensProps = {
  tokens: ApiTokenView[]
  organizations: AccountOrganization[]
  /** The scopes this account may ask for. Node permissions are omitted for a customer. */
  scopes: ApiScope[]
  mayIssueUnscoped: boolean
  issuedToken?: string
  issuedTokenName?: string
}

export default function ApiTokensPage() {
  const {tokens, organizations, scopes, mayIssueUnscoped, issuedToken, issuedTokenName} =
    usePage<ApiTokensProps>().props

  return (
    <div className="flex flex-col gap-4">
      <Head title="API tokens" />
      <SettingsTabs />
      <PageHeader
        title="API tokens"
        description={
          <>
            A bearer token for <code className="font-mono">/api/v1</code>. It acts as you,
            inside the permissions you give it.
          </>
        }
      />

      {issuedToken ? (
        <IssuedTokenBanner token={issuedToken} name={issuedTokenName ?? 'Your new token'} />
      ) : null}

      <IssueTokenForm
        organizations={organizations}
        scopes={scopes}
        mayIssueUnscoped={mayIssueUnscoped}
      />

      <ApiTokenList tokens={tokens} organizations={organizations} />
    </div>
  )
}
