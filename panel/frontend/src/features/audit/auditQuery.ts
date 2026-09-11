import type {AuditFilter} from './auditTypes'

/**
 * The trail's filter as a URL, in one place.
 *
 * `GET /admin/audit` reads ten optional request parameters, and three separate things on
 * the page need to produce that URL: the filter form when it is submitted, the pager when
 * it moves, and the shortcuts on a row that narrow to one actor or one object. Building
 * the string in three places is how the pager ends up dropping the filter, which is the
 * bug that makes an audit screen useless at the moment somebody is relying on it.
 *
 * An empty value is omitted rather than sent blank. The controller treats both the same,
 * but a URL an operator copies into a ticket should say what it filtered on and nothing
 * else.
 */
export type FilterOverrides = Partial<Record<keyof AuditFilter, string | number | null>>

export const AUDIT_PATH = '/admin/audit'

const KEYS: Array<keyof AuditFilter> = [
  'organizationId',
  'accountId',
  'nodeId',
  'action',
  'targetKind',
  'targetId',
  'outcome',
  'from',
  'to',
  'limit',
  'offset',
]

export function auditUrl(filter: AuditFilter, overrides: FilterOverrides = {}): string {
  const query = new URLSearchParams()

  for (const key of KEYS) {
    const value = key in overrides ? overrides[key] : filter[key]
    if (value === null || value === undefined) {
      continue
    }
    const text = String(value).trim()
    if (text.length === 0 || text === '0') {
      // Offset zero and an unset limit are the server's defaults; saying so adds noise to
      // a URL somebody is going to paste somewhere.
      continue
    }
    query.set(key, text)
  }

  const rendered = query.toString()
  return rendered.length === 0 ? AUDIT_PATH : `${AUDIT_PATH}?${rendered}`
}

/** How many filters are actually narrowing the list, for the button on a phone. */
export function activeFilterCount(filter: AuditFilter): number {
  const narrowing: Array<keyof AuditFilter> = [
    'organizationId',
    'accountId',
    'nodeId',
    'action',
    'targetKind',
    'targetId',
    'outcome',
    'from',
    'to',
  ]
  return narrowing.filter((key) => {
    const value = filter[key]
    return value !== null && String(value).trim().length > 0
  }).length
}
