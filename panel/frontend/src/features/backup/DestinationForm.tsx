import {t} from '@/i18n'
import {Button, Checkbox, Input, Modal, Select, useFormFields} from '@/shell'

import type {DestinationView} from './backupTypes'

/**
 * Where snapshots are pushed: an S3-compatible bucket, or a path on the node itself.
 *
 * One form for both scopes. A customer's destinations are created under
 * `/backups/{organizationId}/destinations` and an operator's platform-wide ones under
 * `/admin/backups/destinations`; the fields are identical and the use-case behind each
 * refuses a row from the other scope, so the only thing that differs here is the path this
 * posts to.
 *
 * The secret key is write-only. `DestinationView` has no field for it - the panel keeps it
 * encrypted and nothing displays it - so on an edit the box is empty and leaving it empty
 * keeps the stored one. That is said next to the field, because an empty box that means
 * "unchanged" and an empty box that means "clear it" look the same.
 *
 * A local destination is offered and it is not the safe default: it survives a lost
 * container and not a lost machine. The hint says so rather than letting somebody discover
 * it the week the disk dies.
 */
interface DestinationValues {
  name: string
  kind: string
  endpoint: string
  region: string
  bucket: string
  pathPrefix: string
  accessKeyId: string
  secretAccessKey: string
  storageClass: string
  localPath: string
  enabled: boolean
  [key: string]: string | boolean
}

export function DestinationForm({
  open,
  onClose,
  basePath,
  destination,
  nodeBackupRoot,
}: {
  open: boolean
  onClose: () => void
  /** `/backups/{organizationId}/destinations` or `/admin/backups/destinations`. */
  basePath: string
  /** Null when adding. */
  destination: DestinationView | null
  /** Where a local destination has to live on the node. */
  nodeBackupRoot: string
}) {
  const form = useFormFields<DestinationValues>({
    name: destination?.name ?? '',
    kind: destination?.kind ?? 'S3',
    endpoint: destination?.endpoint ?? '',
    region: destination?.region ?? '',
    bucket: destination?.bucket ?? '',
    pathPrefix: destination?.pathPrefix ?? '',
    accessKeyId: destination?.accessKeyId ?? '',
    secretAccessKey: '',
    storageClass: destination?.storageClass ?? '',
    localPath: destination?.localPath ?? nodeBackupRoot,
    enabled: destination?.enabled ?? true,
  })

  const local = form.data.kind === 'LOCAL'

  function submit() {
    form.submit(destination ? `${basePath}/${destination.id}` : basePath, {
      onSuccess: () => {
        form.reset()
        onClose()
      },
    })
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={destination ? t('backup.destForm.titleEdit', {name: destination.name}) : t('backup.destForm.titleNew')}
      description={t('backup.destForm.description')}
      size="lg"
      footer={
        <div className="flex flex-col gap-2 sm:flex-row-reverse">
          <Button block className="sm:w-auto" loading={form.processing} onClick={submit}>
            {destination ? t('backup.destForm.save') : t('backup.destForm.add')}
          </Button>
          <Button variant="ghost" block className="sm:w-auto" onClick={onClose}>
            {t('backup.destForm.cancel')}
          </Button>
        </div>
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
          {...form.bind('name')}
          label={t('backup.destForm.name')}
          required
          maxLength={120}
          autoComplete="off"
          placeholder={t('backup.destForm.namePlaceholder')}
        />

        <Select
          {...form.bind('kind')}
          label={t('backup.destForm.kind')}
          required
          options={[
            {value: 'S3', label: t('backup.destForm.kindS3')},
            {value: 'LOCAL', label: t('backup.destForm.kindLocal')},
          ]}
          hint={
            local
              ? t('backup.destForm.hintLocal')
              : t('backup.destForm.hintS3')
          }
        />

        {local ? (
          <Input
            {...form.bind('localPath')}
            label={t('backup.destForm.localPath')}
            required
            autoComplete="off"
            spellCheck={false}
            className="font-mono text-xs"
            hint={t('backup.destForm.localPathHint', {root: nodeBackupRoot})}
          />
        ) : (
          <>
            <Input
              {...form.bind('endpoint')}
              label={t('backup.destForm.endpoint')}
              required
              autoComplete="off"
              spellCheck={false}
              placeholder="https://s3.eu-central-1.amazonaws.com"
              hint={t('backup.destForm.endpointHint')}
            />

            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              <Input
                {...form.bind('bucket')}
                label={t('backup.destForm.bucket')}
                required
                autoComplete="off"
                spellCheck={false}
              />
              <Input
                {...form.bind('region')}
                label={t('backup.destForm.region')}
                autoComplete="off"
                spellCheck={false}
                placeholder="eu-central-1"
                hint={t('backup.destForm.regionHint')}
              />
            </div>

            <Input
              {...form.bind('pathPrefix')}
              label={t('backup.destForm.pathPrefix')}
              autoComplete="off"
              spellCheck={false}
              placeholder="wisper/"
              hint={t('backup.destForm.pathPrefixHint')}
            />

            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              <Input
                {...form.bind('accessKeyId')}
                label={t('backup.destForm.accessKeyId')}
                autoComplete="off"
                spellCheck={false}
                className="font-mono text-xs"
              />
              <Input
                {...form.bind('secretAccessKey')}
                label={t('backup.destForm.secretAccessKey')}
                type="password"
                autoComplete="new-password"
                className="font-mono text-xs"
                hint={
                  destination
                    ? t('backup.destForm.secretHintEdit')
                    : t('backup.destForm.secretHintNew')
                }
              />
            </div>

            <Input
              {...form.bind('storageClass')}
              label={t('backup.destForm.storageClass')}
              autoComplete="off"
              spellCheck={false}
              placeholder="STANDARD"
              hint={t('backup.destForm.storageClassHint')}
            />
          </>
        )}

        {destination ? (
          <Checkbox
            {...form.check('enabled')}
            label={t('backup.destForm.enabled')}
            hint={t('backup.destForm.enabledHint')}
          />
        ) : null}

        <button type="submit" className="sr-only">
          {destination ? t('backup.destForm.save') : t('backup.destForm.add')}
        </button>
      </form>
    </Modal>
  )
}
