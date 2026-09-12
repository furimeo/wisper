import {t} from '@/i18n'
import {Badge, Button, Card, CopyButton, useFormFields, useSharedProps} from '@/shell'

/**
 * Recovery codes: the batch itself when it has just been issued, and the count otherwise.
 *
 * `codes` is a one-shot flash prop. It arrives on exactly the render that follows turning
 * the second factor on or asking for a new batch, and it is gone on a reload - the panel
 * stores only hashes, so there is no second chance to read them. That is why this section
 * is loud about saving them, and why copy and download are both here: on a phone, "write
 * these down" is not advice anybody can act on immediately.
 *
 * Regenerating is offered only when the second factor is on, because
 * `RegenerateRecoveryCodes` refuses otherwise - codes that recover from nothing are a
 * list of secrets with no purpose. That refusal arrives keyed to `code`, a field this
 * form does not have, so it is rendered as its own line rather than under an input.
 */
type RecoveryCodesPanelProps = {
  /** Present once, straight after they were generated. */
  codes?: string[] | undefined
  /** Unused codes still standing. */
  remaining: number
  /** How many the last batch contained, used or not. */
  issued: number
  twoFactorEnabled: boolean
}

export function RecoveryCodesPanel({
  codes,
  remaining,
  issued,
  twoFactorEnabled,
}: RecoveryCodesPanelProps) {
  const {errors} = useSharedProps()
  const regenerate = useFormFields({})

  return (
    <Card
      title={t('auth.recovery.title')}
      description={t('auth.recovery.description')}
      action={
        twoFactorEnabled ? (
          <Badge tone={remaining === 0 ? 'failed' : remaining <= 2 ? 'degraded' : 'running'} dot>
            {t('auth.recovery.badgeLeft', {count: remaining})}
          </Badge>
        ) : null
      }
    >
      <div className="flex flex-col gap-5">
        {codes ? (
          <div className="rounded-lg border border-degraded/50 bg-degraded/10 p-4">
            <p className="text-sm leading-relaxed font-medium text-ink-900 dark:text-ink-100">
              {t('auth.recovery.saveNow')}
            </p>
            <ul className="mt-3 grid grid-cols-2 gap-x-4 gap-y-1.5 font-mono text-sm tabular-nums select-all">
              {codes.map((code) => (
                <li key={code}>{code}</li>
              ))}
            </ul>
            <div className="mt-4 flex flex-wrap gap-2">
              <CopyButton
                value={codes.join('\n')}
                label={t('auth.recovery.copyAll')}
                describedAs={t('auth.recovery.copyAllDesc')}
              />
              <DownloadCodesButton codes={codes} />
            </div>
          </div>
        ) : null}

        {twoFactorEnabled ? (
          <div>
            <p className="text-sm leading-relaxed text-ink-600 dark:text-ink-400">
              {remaining === 0
                ? t('auth.recovery.allUsed', {count: issued})
                : t('auth.recovery.unused', {remaining, issued})}{' '}
              {t('auth.recovery.regenerateNotice')}
            </p>
            {errors.code ? (
              <p role="alert" className="mt-2 text-sm text-failed">
                {errors.code}
              </p>
            ) : null}
            <Button
              variant={remaining === 0 ? 'primary' : 'secondary'}
              className="mt-3 w-full sm:w-auto"
              loading={regenerate.processing}
              onClick={() => regenerate.submit('/settings/security/recovery-codes')}
            >
              {regenerate.processing ? t('auth.recovery.generating') : t('auth.recovery.generate')}
            </Button>
          </div>
        ) : (
          <p className="text-sm leading-relaxed text-ink-600 dark:text-ink-400">
            {t('auth.recovery.turnOnFirst')}
          </p>
        )}
      </div>
    </Card>
  )
}

/**
 * Saves the batch as a text file.
 *
 * A blob URL rather than a `data:` link: a data URL puts the codes in the address bar and
 * therefore in the browser's history. The object URL is revoked as soon as the download
 * has been handed off, so the codes are not left reachable from the page.
 */
function DownloadCodesButton({codes}: {codes: string[]}) {
  return (
    <Button
      variant="secondary"
      onClick={() => {
        const file = new Blob([`${codes.join('\n')}\n`], {type: 'text/plain'})
        const href = URL.createObjectURL(file)
        const link = document.createElement('a')
        link.href = href
        link.download = 'wisper-recovery-codes.txt'
        link.click()
        URL.revokeObjectURL(href)
      }}
    >
      {t('auth.recovery.download')}
    </Button>
  )
}
