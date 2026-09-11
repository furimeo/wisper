import {useState} from 'react'

import {Button, Card, EmptyState, Icon} from '@/shell'

import {EnvVarDialog} from './EnvVarDialog'
import {EnvVarRow} from './EnvVarRow'
import type {EnvVar, ServiceView} from './serviceTypes'

/**
 * The plain variables on one service.
 *
 * One reveal switch for the whole list rather than one per row. A customer scanning
 * fifteen variables for the one that is wrong wants them all readable in a single tap,
 * and fifteen little eye buttons on a 375px screen is fifteen 44px targets competing with
 * the row they belong to. It starts off, because the common reason to open this page on a
 * phone is to check a name, and the common place to do that is somewhere with people
 * behind you.
 *
 * The switch is presentation only. Every value on this list is already in the props the
 * server sent - `EnvVar` is passed whole, values included, because a plain variable is
 * not a secret and pretending otherwise would mean a second round trip that reveals
 * exactly the same thing. What must never appear is a secret's value, and that is a
 * different table, a different query and the list next to this one.
 */
export function EnvVarList({
  service,
  variables,
  writable,
}: {
  service: ServiceView
  variables: EnvVar[]
  writable: boolean
}) {
  const [revealed, setRevealed] = useState(false)
  const [editor, setEditor] = useState<{variable: EnvVar | null} | null>(null)

  return (
    <Card
      title="Variables"
      description="Readable values, handed to the container as its environment."
      action={
        writable ? (
          <Button size="sm" onClick={() => setEditor({variable: null})}>
            Add
          </Button>
        ) : null
      }
      padded={false}
    >
      {variables.length === 0 ? (
        <EmptyState
          icon={<Icon name="key" />}
          title="No variables yet"
          description="Anything the workload reads from its environment goes here - a port, a log
            level, the address of something it talks to. Values that must not be readable belong
            in the secrets list below."
          action={
            writable ? (
              <Button onClick={() => setEditor({variable: null})}>Add the first one</Button>
            ) : null
          }
        />
      ) : (
        <>
          <div className="flex items-center justify-between gap-3 border-b border-ink-200 px-4 py-2 md:px-5 dark:border-ink-800">
            <span className="text-xs text-ink-500 dark:text-ink-400">
              {variables.length} {variables.length === 1 ? 'variable' : 'variables'}
            </span>
            <Button
              variant="ghost"
              size="sm"
              onClick={() => setRevealed((shown) => !shown)}
              aria-pressed={revealed}
            >
              {revealed ? 'Hide values' : 'Show values'}
            </Button>
          </div>

          <ul className="divide-y divide-ink-200 dark:divide-ink-800">
            {variables.map((variable) => (
              <EnvVarRow
                key={variable.id}
                variable={variable}
                revealed={revealed}
                onOpen={writable ? () => setEditor({variable}) : undefined}
              />
            ))}
          </ul>
        </>
      )}

      {editor ? (
        <EnvVarDialog
          key={editor.variable?.name ?? '@new'}
          service={service}
          variable={editor.variable}
          onClose={() => setEditor(null)}
        />
      ) : null}
    </Card>
  )
}
