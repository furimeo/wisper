import {useEffect, useState} from 'react'

import {t} from '@/i18n'
import {Button, Modal, Textarea} from '@/shell'

/**
 * The fallback when the browser will not hand over the clipboard.
 *
 * `navigator.clipboard.readText` is not available on every browser this panel has to
 * work on - Safari grants it only behind its own permission prompt, and it does not exist
 * at all outside a secure context, which is how somebody terminating TLS at their own
 * tunnel will run the panel. Pasting into a shell is not optional on a phone, where
 * retyping a connection string is not a realistic alternative, so there has to be a way
 * that always works.
 *
 * This is it: a box the platform's own paste gesture can target, and a button that sends
 * what landed in it. Nothing clever, and nothing that can be refused.
 */
export function TerminalPasteDialog({
  open,
  onClose,
  onSend,
}: {
  open: boolean
  onClose: () => void
  onSend: (text: string) => void
}) {
  const [text, setText] = useState('')

  useEffect(() => {
    if (open) {
      setText('')
    }
  }, [open])

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={t('terminal.paste_dialog.title')}
      description={t('terminal.paste_dialog.description')}
      size="sm"
      footer={
        <>
          <Button variant="secondary" onClick={onClose} block>
            {t('terminal.paste_dialog.cancel')}
          </Button>
          <Button
            onClick={() => {
              onSend(text)
              onClose()
            }}
            disabled={text === ''}
            block
          >
            {t('terminal.paste_dialog.send')}
          </Button>
        </>
      }
    >
      <Textarea
        label={t('terminal.paste_dialog.text_label')}
        value={text}
        onChange={(event) => setText(event.target.value)}
        rows={6}
        autoFocus
        autoCapitalize="off"
        autoCorrect="off"
        spellCheck={false}
        hint={t('terminal.paste_dialog.text_hint')}
      />
    </Modal>
  )
}
