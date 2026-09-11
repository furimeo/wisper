import {usePage} from '@inertiajs/react'

import type {FieldErrors, FlashMessages} from '@/inertia/sharedProps'

/**
 * The props the chrome reads on every page, typed.
 *
 * `docs/contracts/pages.md` §3 fixes five shared props: `errors` and `flash` from
 * `web.SharedProps`, `account` from `auth.SignedInAccountProps`, and `organizations` plus
 * `organization` from `org.CurrentOrganizationProps`. `src/inertia/sharedProps.ts` - a
 * file the shell does not own - currently declares only the first two, so the three the
 * switcher and the account menu need are declared here instead. None of them is ever
 * absent: the contributors write the key even when the value is null or empty, which is
 * what lets this file return them without an optional in the type.
 */

/** `auth.PlatformRole`. */
export type PlatformRole = 'ADMIN' | 'CUSTOMER'

/** `org.MemberRole`. What a person may do inside one organization. */
export type MemberRole = 'OWNER' | 'ADMIN' | 'DEVELOPER' | 'VIEWER'

/** `org.OrganizationStatus`. */
export type OrganizationStatus = 'ACTIVE' | 'SUSPENDED'

/** `auth.SignedInAccount`. Carries no credential, which is why it is safe on every page. */
export interface SignedInAccount {
  id: string
  email: string
  displayName: string
  role: PlatformRole
  /** Derived from `isPlatformAdmin()`: whether `/admin/**` is reachable. */
  platformAdmin: boolean
}

/** `org.OrganizationSummary`. One line of the organization switcher. */
export interface OrganizationSummary {
  id: string
  name: string
  slug: string
  role: MemberRole
  status: OrganizationStatus
  suspensionReason: string | null
  /** False while this is an invitation the account has not answered. */
  accepted: boolean
  invitedAt: string | null
  /** Derived from `isSuspended()`. */
  suspended: boolean
}

export interface ShellProps {
  errors: FieldErrors
  flash: FlashMessages
  account: SignedInAccount | null
  organizations: OrganizationSummary[]
  organization: OrganizationSummary | null
}

/** The shared props, typed. Page-specific props still come from `usePage<YourProps>()`. */
export function useShellProps(): ShellProps {
  return usePage().props as unknown as ShellProps
}

/** Who is signed in, or null on the sign-in page and the error page. */
export function useAccount(): SignedInAccount | null {
  return useShellProps().account
}

/** Every organization the account belongs to, memberships first, then invitations. */
export function useOrganizations(): OrganizationSummary[] {
  return useShellProps().organizations
}

/** The organization the current URL, the session or the first membership selected. */
export function useCurrentOrganization(): OrganizationSummary | null {
  return useShellProps().organization
}

const WRITING_ROLES: readonly MemberRole[] = ['OWNER', 'ADMIN', 'DEVELOPER']

/**
 * Whether a role may change things, for deciding what to *show*.
 *
 * The server decides what to allow and decides it again on every request. Hiding a
 * button a person cannot use is courtesy; treating this as the authorization check is how
 * the permission ends up living in two places and disagreeing in one of them.
 */
export function mayWrite(role: MemberRole | null | undefined): boolean {
  return role != null && WRITING_ROLES.includes(role)
}
