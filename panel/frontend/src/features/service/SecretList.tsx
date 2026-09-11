import {useState} from 'react'

import {Button, Card, EmptyState, Icon} from '@/shell'

import {SecretDialog} from './SecretDialog'
import {SecretRow} from './SecretRow'
import type {SecretView, ServiceView} from './serviceTypes'

/**
 * The secrets on one service.
 *
 * There is no reveal switch here and there cannot be one. `ListSecrets` does not select
 * the value column, so no value reaches the props, and a button offering to show one
 * would have to fetch it from somewhere - which is the moment a panel stops being able to
 * say "we cannot read your secrets". What the rows carry instead is when each was last
 * changed, because "did Tuesday's rotation go through?" is the question somebody actually
 * arrives with.
 *
 * Separate from `EnvVarList` rather than one list with a flag: the two differ in what may
 * be drawn, what may be copied and what a save means, and a single component holding both
 * behaviours behind a boolean is one edit away from showing a secret.
 */
export function SecretList({
  service,
  secrets,
  writable,
}: {
  service: ServiceView
  secrets: SecretView[]
  writable: boolean
}) {
  const [editor, setEditor] = useState<{secret: SecretView | null} | null>(null)

  return (
    <Card
      title="Secrets"
      description="Encrypted at rest and write-only from here. The container reads them as ordinary environment variables."
      action={
        writable ? (
          <Button size="sm" onClick={() => setEditor({secret: null})}>
            Add
          </Button>
        ) : null
      }
      padded={false}
    >
      {secrets.length === 0 ? (
        <EmptyState
          icon={<Icon name="shield" />}
          title="No secrets yet"
          description="Anything you would not want on the screen behind you - a database password, an
            API key, a signing secret. It goes into the container's environment exactly like a
            plain variable; the difference is that nothing here can read it back."
          action={
            writable ? (
              <Button onClick={() => setEditor({secret: null})}>Add the first one</Button>
            ) : null
          }
        />
      ) : (
        <>
          <div className="border-b border-ink-200 px-4 py-2 md:px-5 dark:border-ink-800">
            <span className="text-xs text-ink-500 dark:text-ink-400">
              {secrets.length} {secrets.length === 1 ? 'secret' : 'secrets'}, values not readable
            </span>
          </div>

          <ul className="divide-y divide-ink-200 dark:divide-ink-800">
            {secrets.map((secret) => (
              <SecretRow
                key={secret.id}
                secret={secret}
                onOpen={writable ? () => setEditor({secret}) : undefined}
              />
            ))}
          </ul>
        </>
      )}

      {editor ? (
        <SecretDialog
          key={editor.secret?.name ?? '@new'}
          service={service}
          secret={editor.secret}
          onClose={() => setEditor(null)}
        />
      ) : null}
    </Card>
  )
}
