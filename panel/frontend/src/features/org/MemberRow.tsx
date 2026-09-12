import {t} from '@/i18n'
import {Badge, Icon, RelativeTime} from '@/shell'

import type {MemberView} from './orgTypes'
import {roleLabel} from './roleVocabulary'

/**
 * One person in an organization.
 *
 * The address is the headline, not the display name. Two colleagues called "Minh" are one
 * row apart on this screen and the thing that tells them apart is the email - which is
 * also what was typed to invite them, and what a customer is checking against when they
 * wonder whether the invitation went to the right place.
 *
 * An outstanding invitation is drawn as a state on the row rather than as a separate list.
 * "Invited three weeks ago" next to somebody's name is a question that answers itself; the
 * same row in a section further down is a row nobody scrolls to.
 */
export function MemberRow({
  member,
  isViewer,
  onOpen,
}: {
  member: MemberView
  /** Marks the signed-in person's own row, so leaving is not mistaken for removing. */
  isViewer: boolean
  /** Absent when the viewer cannot change this member. */
  onOpen?: () => void
}) {
  const body = (
    <>
      <span className="min-w-0 flex-1">
        <span className="flex flex-wrap items-center gap-2">
          <span className="truncate text-sm font-medium text-ink-900 dark:text-ink-100">
            {member.email}
          </span>
          <Badge tone={member.accepted ? 'neutral' : 'degraded'}>
            {member.accepted ? roleLabel(member.role) : t('org.members.invitedBadge')}
          </Badge>
          {isViewer ? <Badge tone="accent">{t('org.members.youBadge')}</Badge> : null}
        </span>

        <span className="mt-0.5 block truncate text-sm text-ink-500 dark:text-ink-400">
          {member.displayName}
        </span>

        <span className="mt-1 block text-xs text-ink-500 dark:text-ink-400">
          {member.accepted ? (
            member.acceptedAt ? (
              <>
                {t('org.members.joined', {
                  time: '',
                  role: roleLabel(member.role).toLowerCase(),
                }).split('{time}')[0]}
                <RelativeTime at={member.acceptedAt} />
                {t('org.members.joined', {
                  time: '',
                  role: roleLabel(member.role).toLowerCase(),
                }).split('{time}')[1]}
              </>
            ) : (
              <>{roleLabel(member.role)}</>
            )
          ) : (
            <>
              {t('org.members.invitedAs', {
                role: roleLabel(member.role).toLowerCase(),
                time: '',
              }).split('{time}')[0]}
              {member.invitedAt ? (
                <>
                  {' '}
                  <RelativeTime at={member.invitedAt} />
                </>
              ) : null}
              {t('org.members.invitedAs', {
                role: roleLabel(member.role).toLowerCase(),
                time: '',
              }).split('{time}')[1]}
            </>
          )}
        </span>
      </span>

      {onOpen ? <Icon name="chevronRight" className="size-4 shrink-0 text-ink-400" /> : null}
    </>
  )

  return (
    <li>
      {onOpen ? (
        <button
          type="button"
          onClick={onOpen}
          className="flex w-full touch-target items-start gap-3 px-4 py-3 text-left transition-colors hover:bg-ink-100 md:px-5 dark:hover:bg-ink-800"
        >
          {body}
        </button>
      ) : (
        <div className="flex w-full touch-target items-start gap-3 px-4 py-3 md:px-5">{body}</div>
      )}
    </li>
  )
}
