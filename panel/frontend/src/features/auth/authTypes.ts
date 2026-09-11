/**
 * The shapes the `auth` controllers put in a model.
 *
 * Every interface here is one Java record in `lhqm.furimeo.wisper.auth`, transcribed from
 * docs/contracts/pages.md §5 - component order, nullability and all. They live in this
 * feature folder because that is where the records live on the other side; another
 * feature that needs one of them imports it from here rather than declaring a second,
 * slightly different copy that nobody notices has drifted.
 *
 * The enums are string-literal unions rather than TypeScript `enum`s: the wire value is a
 * Java `enum` serialised by `name()`, so a union is exactly the set of strings that can
 * arrive, and a typo becomes a compile error instead of a branch that never runs.
 */

/*
 * `PlatformRole` and `SignedInAccount` are declared by the shell, because the chrome needs
 * them on every page and `src/inertia/sharedProps.ts` - which the contract says should
 * carry them and which no feature owns - does not yet. Re-exported rather than declared
 * again here: two declarations of one Java record is precisely the drift this file exists
 * to prevent, and the account menu and this feature must agree.
 */
export type {PlatformRole, SignedInAccount} from '@/shell'

import type {PlatformRole} from '@/shell'

/** `AccountStatus.name()`. A suspended account exists but cannot sign in. */
export type AccountStatus = 'ACTIVE' | 'SUSPENDED'

/**
 * `ApiScope.wireName()` - `resource:verb`, not the Java constant name.
 *
 * This is what `api_token.scopes` stores and what the create form submits. `nodes:read`
 * and `nodes:write` are the platform's own; the server refuses them for a customer, and
 * the page is simply not sent them.
 */
export type ApiScope =
  | 'projects:read'
  | 'projects:write'
  | 'services:read'
  | 'services:write'
  | 'deployments:read'
  | 'deployments:write'
  | 'domains:read'
  | 'domains:write'
  | 'databases:read'
  | 'databases:write'
  | 'files:read'
  | 'files:write'
  | 'backups:read'
  | 'backups:write'
  | 'metrics:read'
  | 'nodes:read'
  | 'nodes:write'

/** `auth.AccountProfile` - an account as a page is allowed to see it. */
export interface AccountProfile {
  id: string
  email: string
  displayName: string
  platformRole: PlatformRole
  status: AccountStatus
  twoFactorEnabled: boolean
  lastLoginAt: string | null
  lastLoginAddress: string | null
  passwordChangedAt: string | null
  lockedUntil: string | null
  createdAt: string
}

/** `auth.SessionView` - one row of "where you are signed in". */
export interface SessionView {
  id: string
  remoteAddress: string | null
  userAgent: string | null
  lastSeenAt: string | null
  createdAt: string
  expiresAt: string
  secondFactorSatisfied: boolean
  current: boolean
}

/** `auth.ApiTokenView`. `maskedValue` is all of the token the panel keeps. */
export interface ApiTokenView {
  id: string
  name: string
  maskedValue: string
  organizationId: string | null
  scopes: ApiScope[]
  expiresAt: string | null
  lastUsedAt: string | null
  lastUsedAddress: string | null
  revokedAt: string | null
  revokedReason: string | null
  createdAt: string
  live: boolean
}

/** `auth.AccountOrganizations.AccountOrganization` - what the token form offers. */
export interface AccountOrganization {
  id: string
  name: string
  slug: string
}

/**
 * `auth.BeginTwoFactorEnrolment.Enrolment` - a one-shot flash prop.
 *
 * `secret` is base32 in groups of four for somebody typing it in; `provisioningUri` is
 * the `otpauth://` URI, which is both the QR code's payload and a link that opens the
 * authenticator app directly when the panel is already on the phone.
 */
export interface TwoFactorEnrolment {
  secret: string
  provisioningUri: string
}

/** `auth.SignInController.Notice` - the banner above the sign-in form. */
export interface SignInNotice {
  tone: 'error' | 'info' | 'success'
  message: string
}
