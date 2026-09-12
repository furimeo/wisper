import {router} from '@inertiajs/react'
import {useState} from 'react'

import {t} from '@/i18n'
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
      title: t('database.actions.rotateConfirm.title', {name: database.name}),
      body: t('database.actions.rotateConfirm.body'),
      confirmLabel: t('database.actions.rotateConfirm.confirm'),
      tone: 'danger',
    })
    if (confirmed) {
      post('password')
    }
  }

  async function drop() {
    const confirmed = await askConfirmation({
      title: t('database.actions.dropConfirm.title', {name: database.name}),
      body: t('database.actions.dropConfirm.body'),
      confirmLabel: t('database.actions.dropConfirm.confirm'),
      tone: 'danger',
      requireText: database.name,
      requireTextLabel: t('database.actions.dropConfirm.requireTextLabel', {name: database.name}),
    })
    if (confirmed) {
      post('delete', {confirmation: database.name})
    }
  }

  if (!writable) {
    return (
      <Card title={t('database.actions.title')}>
        <p className="text-sm leading-relaxed text-ink-700 dark:text-ink-300">
          {t('database.actions.readOnlyNotice', {org: database.organizationName})}
        </p>
      </Card>
    )
  }

  const blocked = !database.actionable
  const blockedBecause = !database.nodeReachable
    ? t('database.actions.blockedStream', {node: database.nodeName})
    : database.inFlight
      ? t('database.actions.blockedInFlight')
      : t('database.actions.blockedState')

  return (
    <Card
      title={t('database.actions.title')}
      description={t('database.actions.description')}
    >
      <div className="flex flex-col gap-2 sm:flex-row sm:flex-wrap">
        <Button
          block
          className="sm:w-auto"
          disabled={blocked}
          loading={pending === 'reveal'}
          onClick={() => post('reveal')}
        >
          {t('database.actions.showDetails')}
        </Button>

        <Button
          variant="secondary"
          block
          className="sm:w-auto"
          disabled={blocked}
          loading={pending === 'password'}
          onClick={() => void rotate()}
        >
          {t('database.actions.changePassword')}
        </Button>

        <Button
          variant="danger"
          block
          className="sm:w-auto sm:ml-auto"
          disabled={blocked}
          loading={pending === 'delete'}
          onClick={() => void drop()}
        >
          {t('database.actions.drop')}
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
