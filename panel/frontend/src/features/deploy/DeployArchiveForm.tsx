import type {DragEvent} from 'react'
import {useRef, useState} from 'react'

import {t} from '@/i18n'
import {Button, Icon, cx, formatBytes, useFormFields} from '@/shell'

import type {DeploymentTarget} from './deployTypes'

/**
 * `POST /services/{serviceId}/deployments/upload` - deploy a site from a zip.
 *
 * The path for the customer who has a folder of HTML and no interest in learning Git, and
 * the reason this platform does not require a repository to be useful. Sites only: an app
 * deploys the image it is configured with and has nothing to unpack a zip into, which is
 * why `acceptsArchive` gates whether this is rendered at all.
 *
 * One POST rather than the resumable chunked upload the file manager uses. A deploy
 * archive is a few megabytes from a laptop; the hop that is actually unreliable is panel
 * to node, over a tunnel, and that one is resumable already. What this does owe a phone on
 * 4G is a progress bar, because a two-minute upload with no feedback is indistinguishable
 * from a dead page - so `progress` from the form is shown as soon as it is a number.
 */
export function DeployArchiveForm({
  target,
  disabled,
  disabledReason,
}: {
  target: DeploymentTarget
  disabled: boolean
  disabledReason?: string
}) {
  const form = useFormFields<{archive: File | null}>({archive: null})
  const input = useRef<HTMLInputElement | null>(null)
  const [over, setOver] = useState(false)

  const chosen = form.data.archive
  const uploading = form.processing

  function take(files: FileList | null): void {
    const file = files?.[0]
    if (file) {
      form.set('archive', file)
    }
  }

  function onDrop(event: DragEvent<HTMLDivElement>): void {
    event.preventDefault()
    setOver(false)
    if (!disabled && !uploading) {
      take(event.dataTransfer.files)
    }
  }

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault()
        if (chosen) {
          form.submit(`/services/${target.serviceId}/deployments/upload`)
        }
      }}
      className="flex flex-col gap-3"
    >
      <div
        onDragOver={(event) => {
          event.preventDefault()
          setOver(true)
        }}
        onDragLeave={() => setOver(false)}
        onDrop={onDrop}
        className={cx(
          'rounded-xl border border-dashed p-4 transition-colors',
          over
            ? 'border-accent-500 bg-accent-500/5'
            : 'border-ink-300 dark:border-ink-700',
        )}
      >
        <input
          ref={input}
          type="file"
          name="archive"
          accept=".zip,application/zip,application/x-zip-compressed"
          disabled={disabled || uploading}
          onChange={(event) => take(event.target.files)}
          className="sr-only"
        />

        <button
          type="button"
          disabled={disabled || uploading}
          onClick={() => input.current?.click()}
          className={cx(
            'flex w-full touch-target items-center gap-3 rounded-lg px-2 text-left',
            'disabled:cursor-not-allowed disabled:opacity-60',
          )}
        >
          <span className="flex size-11 shrink-0 items-center justify-center rounded-full bg-ink-100 text-ink-500 dark:bg-ink-800 dark:text-ink-400">
            <Icon name="backup" />
          </span>
          <span className="min-w-0">
            <span className="block truncate text-sm font-medium text-ink-900 dark:text-ink-100">
              {chosen ? chosen.name : t('deploy.archive_form.choose_zip')}
            </span>
            <span className="block text-sm text-ink-500 dark:text-ink-400">
              {chosen
                ? formatBytes(chosen.size)
                : t('deploy.archive_form.drag_hint')}
            </span>
          </span>
        </button>
      </div>

      {form.error('archive') ? (
        <p role="alert" className="text-sm text-failed">
          {form.error('archive')}
        </p>
      ) : null}

      {uploading && form.progress !== null ? (
        <div>
          <div
            className="h-1.5 w-full overflow-hidden rounded-full bg-ink-200 dark:bg-ink-800"
            role="progressbar"
            aria-valuenow={form.progress}
            aria-valuemin={0}
            aria-valuemax={100}
            aria-label={t('deploy.archive_form.uploading_aria')}
          >
            <div
              className="h-full rounded-full bg-accent-500 transition-[width]"
              style={{width: `${form.progress}%`}}
            />
          </div>
          <p className="mt-1.5 text-sm text-ink-500 tabular-nums dark:text-ink-400">
            {t('deploy.archive_form.uploading_progress', {progress: form.progress})}
          </p>
        </div>
      ) : null}

      <Button
        type="submit"
        block
        loading={uploading}
        disabled={disabled || chosen === null}
      >
        {t('deploy.archive_form.upload_and_deploy')}
      </Button>

      {disabled && disabledReason ? (
        <p className="text-sm text-ink-500 dark:text-ink-400">{disabledReason}</p>
      ) : null}
    </form>
  )
}
