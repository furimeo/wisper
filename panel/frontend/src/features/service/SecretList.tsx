import {useState} from 'react'

import {t} from '@/i18n'
import {Button, Card, EmptyState, Icon} from '@/shell'

import {SecretDialog} from './SecretDialog'
import {SecretRow} from './SecretRow'
import type {SecretView, ServiceView} from './serviceTypes'

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
      title={t('service.secrets.title')}
      description={t('service.secrets.description')}
      action={
        writable ? (
          <Button size="sm" onClick={() => setEditor({secret: null})}>
            {t('service.secrets.add_button')}
          </Button>
        ) : null
      }
      padded={false}
    >
      {secrets.length === 0 ? (
        <EmptyState
          icon={<Icon name="shield" />}
          title={t('service.secrets.empty_title')}
          description={t('service.secrets.empty_description')}
          action={
            writable ? (
              <Button onClick={() => setEditor({secret: null})}>
                {t('service.secrets.add_first')}
              </Button>
            ) : null
          }
        />
      ) : (
        <>
          <div className="border-b border-ink-200 px-4 py-2 md:px-5 dark:border-ink-800">
            <span className="text-xs text-ink-500 dark:text-ink-400">
              {t('service.secrets.count_label', {count: secrets.length})}
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
