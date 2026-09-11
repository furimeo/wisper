import {router} from '@inertiajs/react'
import {useState} from 'react'

import {Button, Card, askConfirmation, mayWrite} from '@/shell'
import type {MemberRole} from '@/shell'

import type {ManagedDatabaseView} from './databaseTypes'

/**
 * The three buttons that act on a database, and the sentence each of them earns.
 *
 * Revealing is a write. It is a POST, it redirects, and it is written to the audit trail
 * before the credentials are put in front of anybody - "who looked at this password and
 * when" is a question an incident asks, and a GET that answered it would leave the
 * password in a browser history.
 *
 * Rotating breaks every application currently connected with the old password. That is
 * said before the press rather than discovered after it.
 *
 * Dropping asks for the database's name typed back, which is what the server checks too.
 * The row is not the data.
 */
export function DatabaseActions({
  database,
  viewerRole,
}: {
  database: ManagedDatabaseView
  viewerRole: MemberRole
}) {
  const [pending, setPending] = useState<string | null>(null)
  const writable = mayWrite(viewerRole)

  function post(path: string, data: Record<string, string> = {}) {
    setPending(path)
    router.post(`/databases/${database.id}/${path}`, data, {
      preserveScroll: true,
      onFinish: () => setPending(null),
    })
  }

  async function rotate() {
    const confirmed = await askConfirmation({
      title: `Change the password for ${database.name}?`,
      body:
        'A new password is set immediately and the old one stops working. Anything connected ' +
        'with it - a running service, a cron job, your laptop - fails until it is updated. The ' +
        'new password is shown once, on the page you come back to.',
      confirmLabel: 'Change it',
      tone: 'danger',
    })
    if (confirmed) {
      post('password')
    }
  }

  async function drop() {
    const confirmed = await askConfirmation({
      title: `Drop ${database.name}?`,
      body:
        'This deletes the database, its login and everything in it. The panel cannot get it ' +
        'back; a backup can, if you have one.',
      confirmLabel: 'Drop it',
      tone: 'danger',
      requireText: database.name,
      requireTextLabel: `Type ${database.name} to confirm`,
    })
    if (confirmed) {
      post('delete', {confirmation: database.name})
    }
  }

  if (!writable) {
    return (
      <Card title="Connecting to it">
        <p className="text-sm leading-relaxed text-ink-700 dark:text-ink-300">
          Your role in {database.organizationName} is read-only, so the connection details and
          the password are not yours to reveal. Ask an owner or an administrator of the
          organization.
        </p>
      </Card>
    )
  }

  const blocked = !database.actionable
  const blockedBecause = !database.nodeReachable
    ? `The panel has no control stream to ${database.nodeName} right now. The database keeps serving; these three need the node to answer.`
    : database.inFlight
      ? 'The platform is still working on this database. These become available when it settles.'
      : 'This database is not in a state these can act on.'

  return (
    <Card
      title="Connecting to it"
      description="Credentials are shown once and the panel records who asked."
    >
      <div className="flex flex-col gap-2 sm:flex-row sm:flex-wrap">
        <Button
          block
          className="sm:w-auto"
          disabled={blocked}
          loading={pending === 'reveal'}
          onClick={() => post('reveal')}
        >
          Show connection details
        </Button>

        <Button
          variant="secondary"
          block
          className="sm:w-auto"
          disabled={blocked}
          loading={pending === 'password'}
          onClick={() => void rotate()}
        >
          Change the password
        </Button>

        <Button
          variant="danger"
          block
          className="sm:w-auto sm:ml-auto"
          disabled={blocked}
          loading={pending === 'delete'}
          onClick={() => void drop()}
        >
          Drop this database
        </Button>
      </div>

      {blocked ? (
        <p className="mt-3 text-sm leading-relaxed text-ink-500 dark:text-ink-400">
          {blockedBecause}
        </p>
      ) : null}
    </Card>
  )
}
