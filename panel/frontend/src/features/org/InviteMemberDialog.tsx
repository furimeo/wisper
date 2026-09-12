import {t} from '@/i18n'
import {Button, Input, Modal, Select, useFormFields} from '@/shell'
import type {MemberRole} from '@/shell'

import type {Membership, QuotaAllowance} from './orgTypes'
import {quotaSentence} from './quotaVocabulary'
import {roleDescription, roleLabel} from './roleVocabulary'

/**
 * `POST /orgs/{organizationId}/members` - inviting somebody who already has an account.
 *
 * The panel has no public sign-up: `InviteMember` looks the address up and refuses if
 * nobody has one. That is stated on the form rather than left to the refusal, because "no
 * account with that address" arriving after the fact reads like a typo when it is usually
 * a person who has not been created yet.
 *
 * The role picker carries its own description, updated as the choice changes. A dropdown
 * of four words is four words somebody guesses at, and the guess that costs something is
 * Developer for a person who should have been a Viewer. Owner is offered only to an owner,
 * because `InviteMember` refuses it for anybody else and an option that always fails is
 * worse than no option.
 *
 * The seat count is shown before the form is filled in, not after it is refused.
 */
export function InviteMemberDialog({
  open,
  onClose,
  viewer,
  roles,
  seats,
}: {
  open: boolean
  onClose: () => void
  /** The signed-in person's own standing, which decides what may be handed out. */
  viewer: Membership
  /** `MemberRole.values()` in declaration order: descending authority. */
  roles: MemberRole[]
  /** The MEMBER quota, so the form can say what is left before it is submitted. */
  seats: QuotaAllowance
}) {
  const form = useFormFields({email: '', role: 'DEVELOPER'})
  const chosen = form.data.role as MemberRole
  const full = seats.used >= seats.limit

  function invite() {
    form.submit(`/orgs/${viewer.organizationId}/members`, {
      onSuccess: () => {
        form.reset()
        onClose()
      },
    })
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={t('org.invite.title')}
      description={t('org.invite.description')}
      footer={
        <>
          <Button variant="secondary" onClick={onClose} disabled={form.processing}>
            {t('org.invite.cancel')}
          </Button>
          <Button loading={form.processing} disabled={full} onClick={invite}>
            {t('org.invite.submit')}
          </Button>
        </>
      }
    >
      <form
        className="flex flex-col gap-4"
        onSubmit={(event) => {
          event.preventDefault()
          invite()
        }}
      >
        {full ? (
          <p className="rounded-lg border border-degraded/40 bg-degraded/10 px-3 py-2 text-sm leading-relaxed text-ink-800 dark:text-ink-100">
            {t('org.invite.seatsFull', {quota: quotaSentence('MEMBER', seats.used, seats.limit)})}
          </p>
        ) : (
          <p className="text-sm text-ink-500 dark:text-ink-400">
            {t('org.invite.seatsRemaining', {quota: quotaSentence('MEMBER', seats.used, seats.limit)})}
          </p>
        )}

        <Input
          {...form.bind('email')}
          label={t('org.invite.email')}
          type="email"
          required
          autoFocus
          disabled={full}
          maxLength={320}
          autoComplete="off"
          autoCapitalize="none"
          spellCheck={false}
          inputMode="email"
          enterKeyHint="send"
          placeholder="colleague@example.com"
          hint={t('org.invite.emailHint')}
        />

        <Select
          {...form.bind('role')}
          label={t('org.invite.role')}
          required
          disabled={full}
          options={roles.map((role) => ({
            value: role,
            label:
              role === 'OWNER' && !viewer.owner
                ? t('org.invite.roleOwnersOnly', {role: roleLabel(role)})
                : roleLabel(role),
            disabled: role === 'OWNER' && !viewer.owner,
          }))}
          hint={roleDescription(chosen)}
        />

        <button type="submit" className="hidden" aria-hidden="true" tabIndex={-1} />
      </form>
    </Modal>
  )
}
