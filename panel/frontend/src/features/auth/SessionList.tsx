import {useState} from 'react'

import {Badge, Button, Card, EmptyState, askConfirmation, useFormFields} from '@/shell'

import type {SessionView} from './authTypes'
import {SessionRow} from './SessionRow'

/**
 * Every browser this account is signed in to, and the buttons that end them.
 *
 * "Sign out everywhere" asks first. It is the one control here whose effect a customer
 * cannot see - the browsers it ends are somewhere else - and pressing it by mistake means
 * signing back in on every device they own.
 *
 * The list is never empty in practice: the browser reading the page is one of its rows.
 * The empty state exists anyway, because the alternative is a card that renders nothing
 * if the sweep in `ExpireStaleSessions` and this request ever cross.
 */
type SessionListProps = {
  sessions: SessionView[]
}

export function SessionList({sessions}: SessionListProps) {
  const revoke = useFormFields({})
  const revokeAll = useFormFields({})
  // Which row is waiting, so only that row's button shows a spinner.
  const [pending, setPending] = useState<string | null>(null)
  const others = sessions.filter((session) => !session.current).length

  async function endEverythingElse() {
    const confirmed = await askConfirmation({
      title: 'Sign out every other browser?',
      body:
        `${others} other session${others === 1 ? '' : 's'} will end on its next request. ` +
        'This browser stays signed in.',
      confirmLabel: 'Sign them out',
      tone: 'danger',
    })
    if (confirmed) {
      revokeAll.submit('/settings/sessions/revoke-all')
    }
  }

  return (
    <Card
      title="Signed-in browsers"
      description="Anything you do not recognise should be signed out, and then your password
        changed."
      action={<Badge>{sessions.length}</Badge>}
    >
      {sessions.length === 0 ? (
        <EmptyState
          title="No live sessions"
          description="Nothing is signed in to this account right now, including this page - which
            means the session behind it has just expired. Reload to sign in again."
        />
      ) : (
        <>
          <ul className="flex flex-col gap-3">
            {sessions.map((session) => (
              <SessionRow
                key={session.id}
                session={session}
                busy={revoke.processing && pending === session.id}
                onRevoke={() => {
                  setPending(session.id)
                  revoke.submit(`/settings/sessions/${session.id}/revoke`, {
                    onFinish: () => setPending(null),
                  })
                }}
              />
            ))}
          </ul>

          <div className="mt-4 border-t border-ink-200 pt-4 dark:border-ink-800">
            <Button
              variant="secondary"
              className="w-full sm:w-auto"
              loading={revokeAll.processing}
              disabled={others === 0}
              onClick={() => void endEverythingElse()}
            >
              {others === 0
                ? 'No other browsers to sign out'
                : `Sign out the other ${others === 1 ? 'browser' : `${others} browsers`}`}
            </Button>
            <p className="mt-2 text-xs leading-relaxed text-ink-500">
              This browser is left alone. The others are signed out on their next request.
            </p>
          </div>
        </>
      )}
    </Card>
  )
}
