import {router} from '@inertiajs/react'
import {useState} from 'react'

import {t} from '@/i18n'
import {Button, RelativeTime} from '@/shell'

import type {OrganizationSummary} from './orgTypes'
import {roleDescription, roleLabel} from './roleVocabulary'

/**
 * An organization the account has been invited to but has not joined.
 *
 * Not a link: there is nothing to open yet. `ResolveMembership` answers 404 for a tenant
 * whose invitation is outstanding, so a row that navigated would lead to a page saying the
 * organization does not exist - which is exactly the wrong sentence for one that is
 * waiting for an answer.
 *
 * The role is spelled out with what it allows. Accepting is the one moment somebody is
 * told what they are agreeing to, and "Developer" on its own does not say whether they are
 * about to be able to delete a database.
 *
 * There is no decline button, and that is not an omission: the panel has no use-case for
 * declining an invitation, so a button would be a door onto nothing. An unwanted
 * invitation is left alone, and whoever sent it can withdraw it from their member list.
 */
export function InvitationRow({invitation}: {invitation: OrganizationSummary}) {
  const [accepting, setAccepting] = useState(false)

  return (
    <li className="rounded-xl border border-accent-500/40 bg-accent-500/5 px-4 py-3.5">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div className="min-w-0">
          <p className="truncate text-sm font-semibold text-ink-900 dark:text-ink-100">
            {invitation.name}
          </p>
          <p className="mt-0.5 text-sm text-ink-600 dark:text-ink-400">
            {t('org.invitation.invitedAs', {role: roleLabel(invitation.role).toLowerCase()})}
            {invitation.invitedAt ? (
              <>
                {' '}
                <RelativeTime at={invitation.invitedAt} />
              </>
            ) : null}
            .
          </p>
          <p className="mt-1 text-xs leading-relaxed text-ink-500 dark:text-ink-400">
            {roleDescription(invitation.role)}
          </p>
        </div>

        <Button
          block
          className="sm:w-auto sm:shrink-0"
          loading={accepting}
          onClick={() => {
            setAccepting(true)
            router.post(
              `/orgs/${invitation.id}/accept`,
              {},
              {preserveScroll: true, onFinish: () => setAccepting(false)},
            )
          }}
        >
          {t('org.invitation.join')}
        </Button>
      </div>
    </li>
  )
}
