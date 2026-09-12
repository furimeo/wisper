import {useEffect} from 'react'

import {t} from '@/i18n'
import {Button, Checkbox, Input, Modal, useFormFields} from '@/shell'

import {filesBase} from './fileRequests'
import {permissionText} from './fileKinds'
import type {FileEntryView} from './fileTypes'

/**
 * The permission bits, as the four octal digits somebody types.
 *
 * `ChangeFileMode` masks everything above the low nine, so setuid, setgid and the sticky
 * bit cannot be set from here whatever is typed. That is a deliberate refusal rather than
 * an oversight: a web file manager handing out setuid inside a container that already
 * runs as a fixed user is a privilege escalation with a text box in front of it. The
 * dialog says so instead of silently dropping the digit.
 *
 * The `rwx` preview updates as the digits are typed, because four octal digits are the
 * format the server wants and not the format most people read.
 */
export function PermissionsDialog({
  entry,
  onClose,
  serviceId,
  rootId,
}: {
  entry: FileEntryView | null
  onClose: () => void
  serviceId: string
  rootId: string
}) {
  const form = useFormFields({
    rootId,
    path: entry?.path ?? '',
    mode: entry?.modeOctal ?? '0644',
    recursive: false,
  })
  const {patch} = form

  useEffect(() => {
    if (entry) {
      patch({path: entry.path, mode: entry.modeOctal, recursive: false})
    }
  }, [entry, patch])

  const parsed = parseMode(form.data.mode)

  const submit = () => {
    if (parsed === null) {
      form.setError('mode', t('files.permissions.invalid_mode_error'))
      return
    }
    form.submit(`${filesBase(serviceId)}/chmod`, {onSuccess: onClose})
  }

  return (
    <Modal
      open={entry !== null}
      onClose={onClose}
      title={t('files.permissions.title')}
      description={entry?.path}
      size="sm"
      footer={
        <>
          <Button variant="secondary" onClick={onClose} block>
            {t('files.permissions.cancel')}
          </Button>
          <Button onClick={submit} loading={form.processing} disabled={parsed === null} block>
            {t('files.permissions.apply')}
          </Button>
        </>
      }
    >
      <form
        className="flex flex-col gap-4"
        onSubmit={(event) => {
          event.preventDefault()
          submit()
        }}
      >
        <Input
          {...form.bind('mode')}
          label={t('files.permissions.mode_label')}
          inputMode="numeric"
          autoFocus
          autoCapitalize="off"
          autoCorrect="off"
          spellCheck={false}
          maxLength={4}
          suffix={
            <span className="font-mono text-xs">
              {parsed === null ? '-' : permissionText(parsed)}
            </span>
          }
          hint={t('files.permissions.mode_hint')}
        />
        {entry?.directory ? (
          <Checkbox
            {...form.check('recursive')}
            label={t('files.permissions.recursive_label')}
            hint={t('files.permissions.recursive_hint')}
          />
        ) : null}
      </form>
    </Modal>
  )
}

/** The nine bits the panel will actually set, or null when the digits are not a mode. */
function parseMode(text: string): number | null {
  const digits = text.trim()
  if (!/^[0-7]{3,4}$/.test(digits)) {
    return null
  }
  return Number.parseInt(digits, 8) & 0o777
}
