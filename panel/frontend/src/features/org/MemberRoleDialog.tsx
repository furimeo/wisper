import {router} from '@inertiajs/react'

import {t} from '@/i18n'
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
      title: isSelf ? t('org.memberDialog.confirmLeaveTitle') : t('org.memberDialog.confirmRemoveTitle', {email: member.email}),
      body: isSelf
        ? t('org.memberDialog.confirmLeaveBody')
        : t('org.memberDialog.confirmRemoveBody'),
      confirmLabel: isSelf ? t('org.memberDialog.confirmLeaveBtn') : t('org.memberDialog.confirmRemoveBtn'),
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
          ? t('org.memberDialog.descAccepted', {
              name: member.displayName,
              role: roleLabel(member.role).toLowerCase(),
            })
          : t('org.memberDialog.descInvited', {
              name: member.displayName,
              role: roleLabel(member.role).toLowerCase(),
            })
      }
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={form.processing}>
            {t('org.memberDialog.close')}
          </Button>
          <Button loading={form.processing} disabled={!mayApply} onClick={save}>
            {t('org.memberDialog.save')}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <Select
          {...form.bind('role')}
          label={t('org.memberDialog.role')}
          disabled={!mayChange}
          options={roles.map((role) => ({
            value: role,
            label:
              role === 'OWNER' && !viewer.owner
                ? t('org.memberDialog.roleOwnersOnly', {role: roleLabel(role)})
                : roleLabel(role),
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
          {isSelf ? t('org.memberDialog.leaveBtn') : t('org.memberDialog.removeBtn')}
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
      ? t('org.memberDialog.explainLastOwnerSelf')
      : t('org.memberDialog.explainLastOwnerOther')
  }
  if (reachingPastSelf) {
    return t('org.memberDialog.explainReachingPastSelf')
  }
  if (!administers) {
    return t('org.memberDialog.explainNotAdmin')
  }
  if (!ownerViewer) {
    return t('org.memberDialog.explainNotOwner')
  }
  return t('org.memberDialog.explainDefault')
}
