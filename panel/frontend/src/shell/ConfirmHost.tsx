import {useEffect, useState, useSyncExternalStore} from 'react'

import {Button} from './Button'
import {Input} from './Input'
import {Modal} from './Modal'
import {
  currentConfirmation,
  registerConfirmHost,
  subscribeToConfirmations,
} from './confirmStore'

/**
 * Renders whatever `askConfirmation` is waiting on.
 *
 * Mounted once by `AppLayout`. Registering itself with the store is what lets
 * `askConfirmation` know whether there is anything to render into - see the fallback
 * there.
 */
export function ConfirmHost() {
  const request = useSyncExternalStore(
    subscribeToConfirmations,
    currentConfirmation,
    currentConfirmation,
  )
  const [typed, setTyped] = useState('')

  useEffect(() => registerConfirmHost(), [])

  // A new question starts with an empty box, whatever the previous one was answered with.
  useEffect(() => {
    setTyped('')
  }, [request?.id])

  if (!request) {
    return null
  }

  const needsText = typeof request.requireText === 'string' && request.requireText.length > 0
  const matches = !needsText || typed === request.requireText

  return (
    <Modal
      open
      onClose={() => request.settle(false)}
      title={request.title}
      size="sm"
      footer={
        <>
          <Button variant="secondary" onClick={() => request.settle(false)}>
            {request.cancelLabel ?? 'Cancel'}
          </Button>
          <Button
            variant={request.tone === 'danger' ? 'danger' : 'primary'}
            disabled={!matches}
            onClick={() => request.settle(true)}
          >
            {request.confirmLabel ?? 'Confirm'}
          </Button>
        </>
      }
    >
      {request.body ? (
        <p className="text-sm leading-relaxed text-ink-700 dark:text-ink-300">
          {request.body}
        </p>
      ) : null}

      {needsText ? (
        <Input
          fieldClassName="mt-4"
          className="font-mono"
          label={request.requireTextLabel ?? `Type ${request.requireText} to confirm`}
          value={typed}
          autoComplete="off"
          autoCapitalize="none"
          autoCorrect="off"
          spellCheck={false}
          onChange={(event) => setTyped(event.target.value)}
        />
      ) : null}
    </Modal>
  )
}
