import {Head, usePage} from '@inertiajs/react'
import {useState} from 'react'

import {Button, Card, EmptyState, Icon, PageHeader} from '@/shell'

import {CreateOrganizationDialog} from './CreateOrganizationDialog'
import {InvitationRow} from './InvitationRow'
import {OrganizationRow} from './OrganizationRow'
import type {OrganizationSummary, Plan} from './orgTypes'

/**
 * `GET /orgs` - the tenants this account belongs to, and the ones waiting for an answer.
 *
 * Invitations are first and they are not a subtle strip at the bottom. An invitation is the
 * only thing on this screen that is waiting on the person reading it, and one sitting
 * unanswered is somebody who thinks they were never invited.
 *
 * Memberships and invitations arrive as two props rather than as one list with a flag,
 * because they are two different rows: one navigates, the other has a button, and
 * `ResolveMembership` answers 404 for the second. A single list would need the flag checked
 * in three places, and the place it gets forgotten is the link.
 */
type OrganizationListProps = {
  memberships: OrganizationSummary[]
  invitations: OrganizationSummary[]
  /** The tiers a new organization may be opened on. */
  plans: Plan[]
}

export default function OrganizationListPage() {
  const {memberships, invitations, plans} = usePage<OrganizationListProps>().props
  const [creating, setCreating] = useState(false)

  return (
    <div className="flex flex-col gap-4">
      <Head title="Organizations" />

      <PageHeader
        title="Organizations"
        description="Each one has its own members, its own plan and its own limits. Projects live inside them."
        actions={
          <Button icon={<Icon name="organization" />} onClick={() => setCreating(true)}>
            New organization
          </Button>
        }
      />

      {invitations.length > 0 ? (
        <section className="flex flex-col gap-2">
          <h2 className="px-1 text-xs font-semibold uppercase tracking-wide text-ink-500 dark:text-ink-400">
            Waiting for you
          </h2>
          <ul className="flex flex-col gap-2">
            {invitations.map((invitation) => (
              <InvitationRow key={invitation.id} invitation={invitation} />
            ))}
          </ul>
        </section>
      ) : null}

      {memberships.length === 0 ? (
        <Card padded={false}>
          <EmptyState
            icon={<Icon name="organization" />}
            title={invitations.length > 0 ? 'Not a member of anything yet' : 'No organizations yet'}
            description={
              invitations.length > 0
                ? 'Join one of the invitations above, or open an organization of your own - a project has to live inside one.'
                : 'An organization owns the projects, the members and the plan. Open one and the first project can go straight in it.'
            }
            action={<Button onClick={() => setCreating(true)}>Open your first organization</Button>}
          />
        </Card>
      ) : (
        <section className="flex flex-col gap-2">
          {invitations.length > 0 ? (
            <h2 className="px-1 text-xs font-semibold uppercase tracking-wide text-ink-500 dark:text-ink-400">
              Your organizations
            </h2>
          ) : null}
          <ul className="flex flex-col gap-2">
            {memberships.map((organization) => (
              <OrganizationRow key={organization.id} organization={organization} />
            ))}
          </ul>
        </section>
      )}

      <CreateOrganizationDialog
        open={creating}
        onClose={() => setCreating(false)}
        plans={plans}
      />
    </div>
  )
}
