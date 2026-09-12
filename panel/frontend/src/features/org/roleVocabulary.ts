import {t} from '@/i18n'

import type {MemberRole} from './orgTypes'

/**
 * What each role is called and what it may do, on this side of the wire.
 *
 * `MemberRole.label()` and `canAdminister()` are Java methods, not record components, so
 * Jackson never sends them: a page that needs the word "Developer" or needs to know
 * whether to draw the plan picker has to work it out here. The rules below mirror
 * `org.MemberRole` exactly, and the server enforces them again on every request - hiding a
 * control is courtesy, and treating this file as the authorization check is how the rule
 * ends up living in two places and disagreeing in one of them.
 */

/** Descending authority, which is the order the pickers offer. */
export const MEMBER_ROLES: readonly MemberRole[] = ['OWNER', 'ADMIN', 'DEVELOPER', 'VIEWER']

/** "Developer", not "DEVELOPER". */
export function roleLabel(role: MemberRole): string {
  return t(`org.roles.${role}`) || role
}

/** The sentence next to a role in a picker. */
export function roleDescription(role: MemberRole): string {
  return t(`org.roles.desc.${role}`) || ''
}

/** Whether this role may change membership, roles and organization settings. */
export function mayAdminister(role: MemberRole | null | undefined): boolean {
  return role === 'OWNER' || role === 'ADMIN'
}

/** Whether this role is the one role a tenant must always keep at least one of. */
export function isOwner(role: MemberRole | null | undefined): boolean {
  return role === 'OWNER'
}

/** Whether `role` is strictly more privileged than `other`. Mirrors `outranks`. */
export function outranks(role: MemberRole, other: MemberRole): boolean {
  return MEMBER_ROLES.indexOf(role) < MEMBER_ROLES.indexOf(other)
}
