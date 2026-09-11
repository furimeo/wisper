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
      title={destination ? `Edit “${destination.name}”` : 'New destination'}
      description="Snapshots are pushed here by the node itself. The panel never holds a copy, so
        this is the only place they exist."
      size="lg"
      footer={
        <div className="flex flex-col gap-2 sm:flex-row-reverse">
          <Button block className="sm:w-auto" loading={form.processing} onClick={submit}>
            {destination ? 'Save' : 'Add it'}
          </Button>
          <Button variant="ghost" block className="sm:w-auto" onClick={onClose}>
            Cancel
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
          label="Name"
          required
          maxLength={120}
          autoComplete="off"
          placeholder="Offsite bucket"
        />

        <Select
          {...form.bind('kind')}
          label="Kind"
          required
          options={[
            {value: 'S3', label: 'S3-compatible bucket'},
            {value: 'LOCAL', label: 'A path on the node'},
          ]}
          hint={
            local
              ? 'On the node. It survives a container being lost and not the machine being lost, so it is a convenience rather than a backup strategy.'
              : 'Anything speaking the S3 API: AWS, Backblaze B2, MinIO, Garage, Hetzner.'
          }
        />

        {local ? (
          <Input
            {...form.bind('localPath')}
            label="Path on the node"
            required
            autoComplete="off"
            spellCheck={false}
            className="font-mono text-xs"
            hint={`Has to be under ${nodeBackupRoot}. The daemon can write nowhere else - its systemd unit restricts it.`}
          />
        ) : (
          <>
            <Input
              {...form.bind('endpoint')}
              label="Endpoint"
              required
              autoComplete="off"
              spellCheck={false}
              placeholder="https://s3.eu-central-1.amazonaws.com"
              hint="The full URL, scheme included."
            />

            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              <Input
                {...form.bind('bucket')}
                label="Bucket"
                required
                autoComplete="off"
                spellCheck={false}
              />
              <Input
                {...form.bind('region')}
                label="Region"
                autoComplete="off"
                spellCheck={false}
                placeholder="eu-central-1"
                hint="Some providers do not care; the signature does."
              />
            </div>

            <Input
              {...form.bind('pathPrefix')}
              label="Path prefix"
              autoComplete="off"
              spellCheck={false}
              placeholder="wisper/"
              hint="Optional. Keeps these snapshots in one folder of a bucket you use for other
                things too."
            />

            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              <Input
                {...form.bind('accessKeyId')}
                label="Access key id"
                autoComplete="off"
                spellCheck={false}
                className="font-mono text-xs"
              />
              <Input
                {...form.bind('secretAccessKey')}
                label="Secret access key"
                type="password"
                autoComplete="new-password"
                className="font-mono text-xs"
                hint={
                  destination
                    ? 'Leave empty to keep the one already stored. It is encrypted and never shown again.'
                    : 'Stored encrypted. Nothing in the panel displays it afterwards.'
                }
              />
            </div>

            <Input
              {...form.bind('storageClass')}
              label="Storage class"
              autoComplete="off"
              spellCheck={false}
              placeholder="STANDARD"
              hint="Optional. A cold class is cheaper to keep and slower - and sometimes dearer -
                to restore from."
            />
          </>
        )}

        {destination ? (
          <Checkbox
            {...form.check('enabled')}
            label="Accept new snapshots"
            hint="Off stops anything new being written here. What is already stored stays and is
              still restorable."
          />
        ) : null}

        <button type="submit" className="sr-only">
          {destination ? 'Save destination' : 'Add destination'}
        </button>
      </form>
    </Modal>
  )
}
