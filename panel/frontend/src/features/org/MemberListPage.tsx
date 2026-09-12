import {Head, usePage} from '@inertiajs/react'
import {useState} from 'react'

import {t} from '@/i18n'
import {Button, Card, EmptyState, Icon, PageHeader} from '@/shell'
import type {MemberRole} from '@/shell'

import {InviteMemberDialog} from './InviteMemberDialog'
import {MemberRoleDialog} from './MemberRoleDialog'
import {MemberRow} from './MemberRow'
import {QuotaMeter} from './QuotaMeter'
import type {MemberView, Membership, QuotaAllowance} from './orgTypes'
import {mayAdminister} from './roleVocabulary'

/**
 * `GET /orgs/{organizationId}/members` - who is in this organization.
 *
 * The organization id comes from `viewer.organizationId` rather than from the URL. The
 * membership was resolved by the server for this exact request, so it is the same value
 * and it is one the type system knows is there.
 *
 * A developer or a viewer sees the whole list and can change none of it. That is
 * deliberate: knowing who else has access is not privileged information inside a tenant
 * you are already in, and a screen that hides the list leaves somebody asking whether the
 * panel can even show it.
 *
 * Seats count invitations that have not been answered. Somebody who invites five people
 * and hears back from none of them has five seats gone, which is worth reading here rather
 * than discovering when the sixth invitation is refused.
 */
type MemberListProps = {
  members: MemberView[]
  /** The signed-in person's own standing in this organization. */
  viewer: Membership
  roles: MemberRole[]
  /** The MEMBER quota. */
  seats: QuotaAllowance
}

export default function MemberListPage() {
  const {members, viewer, roles, seats} = usePage<MemberListProps>().props
  const [inviting, setInviting] = useState(false)
  const [editing, setEditing] = useState<MemberView | null>(null)

  const administers = mayAdminister(viewer.role)
  const pending = members.filter((member) => !member.accepted).length
  const owners = members.filter((member) => member.accepted && member.role === 'OWNER').length

  return (
    <div className="flex flex-col gap-4">
      <Head title={t('org.members.title')} />

      <PageHeader
        title={t('org.members.title')}
        description={t('org.members.description')}
        actions={
          administers ? (
            <Button icon={<Icon name="account" />} onClick={() => setInviting(true)}>
              {t('org.members.inviteBtn')}
            </Button>
          ) : null
        }
      />

      <Card>
        <QuotaMeter allowance={seats} className="py-0" />
      </Card>

      {owners === 1 ? (
        <Card>
          <p className="text-sm leading-relaxed text-ink-800 dark:text-ink-200">
            {t('org.members.singleOwnerWarning')}
          </p>
        </Card>
      ) : null}

      {administers ? null : (
        <Card>
          <p className="text-sm text-ink-700 dark:text-ink-300">
            {t('org.members.readOnlyNotice')}
          </p>
        </Card>
      )}

      <Card
        title={t('org.members.peopleTitle')}
        description={
          pending > 0
            ? t('org.members.peoplePending', {total: members.length, pending})
            : t('org.members.peopleCount', {count: members.length})
        }
        padded={false}
      >
        {members.length === 0 ? (
          <EmptyState
            icon={<Icon name="account" />}
            title={t('org.members.emptyTitle')}
            description={t('org.members.emptyDesc')}
            action={
              administers ? <Button onClick={() => setInviting(true)}>{t('org.members.inviteBtn')}</Button> : null
            }
          />
        ) : (
          <ul className="divide-y divide-ink-200 dark:divide-ink-800">
            {members.map((member) => (
              <MemberRow
                key={member.id}
                member={member}
                isViewer={member.accountId === viewer.accountId}
                onOpen={
                  administers || member.accountId === viewer.accountId
                    ? () => setEditing(member)
                    : undefined
                }
              />
            ))}
          </ul>
        )}
      </Card>

      <InviteMemberDialog
        open={inviting}
        onClose={() => setInviting(false)}
        viewer={viewer}
        roles={roles}
        seats={seats}
      />

      {editing ? (
        <MemberRoleDialog
          key={editing.id}
          member={editing}
          viewer={viewer}
          roles={roles}
          lastOwner={editing.accepted && editing.role === 'OWNER' && owners === 1}
          onClose={() => setEditing(null)}
        />
      ) : null}
    </div>
  )
}
