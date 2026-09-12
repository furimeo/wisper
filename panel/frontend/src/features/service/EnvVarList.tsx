import {useState} from 'react'

import {t} from '@/i18n'
import {Button, Card, EmptyState, Icon} from '@/shell'

import {EnvVarDialog} from './EnvVarDialog'
import {EnvVarRow} from './EnvVarRow'
import type {EnvVar, ServiceView} from './serviceTypes'

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
      title={t('service.variables.title')}
      description={t('service.variables.description')}
      action={
        writable ? (
          <Button size="sm" onClick={() => setEditor({variable: null})}>
            {t('service.variables.add_button')}
          </Button>
        ) : null
      }
      padded={false}
    >
      {variables.length === 0 ? (
        <EmptyState
          icon={<Icon name="key" />}
          title={t('service.variables.empty_title')}
          description={t('service.variables.empty_description')}
          action={
            writable ? (
              <Button onClick={() => setEditor({variable: null})}>
                {t('service.variables.add_first')}
              </Button>
            ) : null
          }
        />
      ) : (
        <>
          <div className="flex items-center justify-between gap-3 border-b border-ink-200 px-4 py-2 md:px-5 dark:border-ink-800">
            <span className="text-xs text-ink-500 dark:text-ink-400">
              {t('service.variables.count_label', {count: variables.length})}
            </span>
            <Button
              variant="ghost"
              size="sm"
              onClick={() => setRevealed((shown) => !shown)}
              aria-pressed={revealed}
            >
              {revealed ? t('service.variables.hide_values') : t('service.variables.show_values')}
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
