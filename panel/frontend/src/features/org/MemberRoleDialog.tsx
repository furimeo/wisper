import {router} from '@inertiajs/react'

import {Button, Modal, Select, askConfirmation, useFormFields} from '@/shell'
import type {MemberRole} from '@/shell'

import type {MemberView, Membership} from './orgTypes'
import {mayAdminister, roleDescription, roleLabel} from './roleVocabulary'

/**
 * What can be done to one member: change the role, or take the access away.
 *
 * A dialog rather than a select on every row. Fifteen rows each holding their own picker
 * is fifteen forms that all post to the same field name, so the message the server returns
 * for one of them lands under all fifteen - `errors` is keyed by field, and every one of
 * those fields is called `role`. One dialog at a time is also the difference between
 * changing somebody's role and brushing a wheel picker with a thumb on a phone.
 *
 * Three rules decide what is offered here, and all three are `ChangeMemberRole`'s and
 * `RemoveMember`'s: only an owner may touch an owner or create one; the last owner may be
 * neither demoted nor removed; and leaving is the one write a developer or a viewer can
 * make about their own row. The controls are shaped to match, so nothing on this dialog is
 * a button that always fails - and the server checks all three again anyway.
 */
export function MemberRoleDialog({
  member,
  viewer,
  roles,
  lastOwner,
  onClose,
}: {
  member: MemberView
  /** The signed-in person's own standing in this organization. */
  viewer: Membership
  roles: MemberRole[]
  /** Whether this member is the only accepted owner left. */
  lastOwner: boolean
  onClose: () => void
}) {
  const form = useFormFields({role: member.role})
  const chosen = form.data.role as MemberRole

  const administers = mayAdminister(viewer.role)
  const isSelf = member.accountId === viewer.accountId
  const targetIsOwner = member.role === 'OWNER'
  const reachingPastSelf = targetIsOwner && !viewer.owner

  const mayChange = administers && !reachingPastSelf && !lastOwner
  const mayApply = mayChange && chosen !== member.role && (chosen !== 'OWNER' || viewer.owner)
  const mayRemove = (isSelf || (administers && !reachingPastSelf)) && !lastOwner

  function save() {
    form.submit(`/orgs/${viewer.organizationId}/members/${member.id}/role`, {
      onSuccess: () => onClose(),
    })
  }

  async function remove() {
    const confirmed = await askConfirmation({
      title: isSelf ? 'Leave this organization?' : `Remove ${member.email}?`,
      body: isSelf
        ? 'You lose access to every project in it. Somebody still inside would have to invite you back.'
        : 'They lose access to every project in this organization straight away. Nothing they built is deleted.',
      confirmLabel: isSelf ? 'Leave' : 'Remove them',
      tone: 'danger',
    })
    if (confirmed) {
      router.post(
        `/orgs/${viewer.organizationId}/members/${member.id}/remove`,
        {},
        {preserveScroll: true, onSuccess: () => onClose()},
      )
    }
  }

  return (
    <Modal
      open
      onClose={onClose}
      title={member.email}
      description={
        member.accepted
          ? `${member.displayName} is ${roleLabel(member.role).toLowerCase()} in this organization.`
          : `${member.displayName} has been invited as ${roleLabel(member.role).toLowerCase()} and has not answered yet.`
      }
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={form.processing}>
            Close
          </Button>
          <Button loading={form.processing} disabled={!mayApply} onClick={save}>
            Change role
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <Select
          {...form.bind('role')}
          label="Role"
          disabled={!mayChange}
          options={roles.map((role) => ({
            value: role,
            label: role === 'OWNER' && !viewer.owner ? `${roleLabel(role)} (owners only)` : roleLabel(role),
            disabled: role === 'OWNER' && !viewer.owner,
          }))}
          hint={roleDescription(chosen)}
        />

        <p className="text-sm leading-relaxed text-ink-600 dark:text-ink-400">
          {explain({lastOwner, reachingPastSelf, administers, isSelf, ownerViewer: viewer.owner})}
        </p>

        <Button
          variant="danger"
          block
          disabled={!mayRemove || form.processing}
          onClick={() => void remove()}
        >
          {isSelf ? 'Leave this organization' : 'Remove from organization'}
        </Button>
      </div>
    </Modal>
  )
}

/** Why the controls above are shaped the way they are, in one sentence. */
function explain({
  lastOwner,
  reachingPastSelf,
  administers,
  isSelf,
  ownerViewer,
}: {
  lastOwner: boolean
  reachingPastSelf: boolean
  administers: boolean
  isSelf: boolean
  ownerViewer: boolean
}): string {
  if (lastOwner) {
    return isSelf
      ? 'You are the last owner. Make somebody else an owner before you change your role or leave - otherwise nobody could administer this organization.'
      : 'This is the last owner, so they cannot be demoted or removed. Make somebody else an owner first.'
  }
  if (reachingPastSelf) {
    return 'Only an owner can change or remove another owner. Ask one of them, or ask to be made an owner yourself.'
  }
  if (!administers) {
    return 'Changing roles is an owner or admin decision, so the picker is off for you. Leaving is your own to make.'
  }
  if (!ownerViewer) {
    return 'An admin can move people between admin, developer and viewer. Making somebody an owner is an owner’s decision.'
  }
  return 'An organization always keeps at least one owner, so the last one cannot be demoted or removed.'
}
