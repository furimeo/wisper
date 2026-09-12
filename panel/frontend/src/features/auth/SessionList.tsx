import {useState} from 'react'

import {t} from '@/i18n'
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
      title: t('auth.sessions.confirmTitle'),
      body:
        others === 1
          ? t('auth.sessions.confirmBody', {count: others})
          : t('auth.sessions.confirmBodyPlural', {count: others}),
      confirmLabel: t('auth.sessions.confirmBtn'),
      tone: 'danger',
    })
    if (confirmed) {
      revokeAll.submit('/settings/sessions/revoke-all')
    }
  }

  return (
    <Card
      title={t('auth.sessions.title')}
      description={t('auth.sessions.description')}
      action={<Badge>{sessions.length}</Badge>}
    >
      {sessions.length === 0 ? (
        <EmptyState
          title={t('auth.sessions.emptyTitle')}
          description={t('auth.sessions.emptyDesc')}
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
                ? t('auth.sessions.btnNoOther')
                : t('auth.sessions.btnSignOutOthers', {count: others})}
            </Button>
            <p className="mt-2 text-xs leading-relaxed text-ink-500">
              {t('auth.sessions.note')}
            </p>
          </div>
        </>
      )}
    </Card>
  )
}
