import {router} from '@inertiajs/react'
import {useEffect, useRef, useState} from 'react'

import {Button, Input, Modal, Select, csrfToken} from '@/shell'

import {filesBase} from './fileRequests'

/** `zip` opens anywhere; `tar.gz` keeps permissions and symlinks a zip would flatten. */
const FORMATS = [
  {value: 'zip', label: 'Zip (.zip)'},
  {value: 'tar.gz', label: 'Gzipped tar (.tar.gz)'},
]

/**
 * Pack the selected entries into one archive, on the node.
 *
 * This is the operation that makes a phone a usable way to administer a volume: a
 * customer can package a directory they could never download first, then take the one
 * file. It is also the closest thing this file manager has to an undo, which is why the
 * dialog says what it is about to write rather than choosing a name silently.
 *
 * The body is built by hand rather than handed to `useFormFields`. `paths` binds to a
 * Java `List<String>`, which needs the field repeated under exactly that name, and
 * Inertia's object-to-form-data conversion would send `paths[]` instead - a parameter
 * Spring does not see at all. Passing a `FormData` through makes the wire format the
 * thing being read here rather than something inferred two libraries away.
 */
export function ArchiveDialog({
  open,
  onClose,
  serviceId,
  rootId,
  directory,
  paths,
}: {
  open: boolean
  onClose: () => void
  serviceId: string
  rootId: string
  /** Where the archive is written, relative to the root. */
  directory: string
  /** What goes in it. Never empty when the dialog is open. */
  paths: string[]
}) {
  const [destination, setDestination] = useState('')
  const [format, setFormat] = useState('zip')
  const [processing, setProcessing] = useState(false)
  const [error, setError] = useState<string | null>(null)
  // Read by the effect below without making it depend on the format.
  const formatRef = useRef(format)
  formatRef.current = format

  /*
   * Reopened with a different selection: the suggested name has to follow it.
   *
   * Keyed on the joined paths rather than the array, which the caller rebuilds on every
   * render, and deliberately not on the format - changing that below rewrites the suffix
   * in place, and re-deriving the whole name would throw away what was typed.
   */
  const selectionKey = paths.join('\n')
  useEffect(() => {
    if (open) {
      setDestination(suggestName(directory, selectionKey.split('\n'), formatRef.current))
      setError(null)
    }
  }, [open, directory, selectionKey])

  const submit = () => {
    const target = destination.trim()
    if (target === '') {
      setError('The archive needs a name.')
      return
    }
    const body = new FormData()
    body.append('rootId', rootId)
    body.append('path', directory)
    body.append('destination', target)
    body.append('format', format)
    for (const path of paths) {
      body.append('paths', path)
    }
    const token = csrfToken()
    router.post(`${filesBase(serviceId)}/archive`, body, {
      // Inertia adds the header itself for its own requests; a hand-built FormData body
      // goes through the same visit, so this is belt and braces rather than duplication.
      headers: token ? {'X-XSRF-TOKEN': token} : {},
      preserveScroll: true,
      onStart: () => setProcessing(true),
      onFinish: () => setProcessing(false),
      onSuccess: onClose,
    })
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={paths.length === 1 ? 'Compress' : `Compress ${paths.length} entries`}
      description="The node builds the archive; nothing is uploaded or downloaded to do it."
      size="sm"
      footer={
        <>
          <Button variant="secondary" onClick={onClose} block>
            Cancel
          </Button>
          <Button onClick={submit} loading={processing} block>
            Compress
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
        <Select
          label="Format"
          value={format}
          options={FORMATS}
          onChange={(event) => {
            const next = event.target.value
            setFormat(next)
            setDestination((current) => withSuffix(current, next))
          }}
        />
        <Input
          label="Archive path"
          name="destination"
          value={destination}
          onChange={(event) => setDestination(event.target.value)}
          error={error ?? undefined}
          autoCapitalize="off"
          autoCorrect="off"
          spellCheck={false}
          hint="Relative to the top of this tree."
        />
        <p className="text-sm text-ink-600 dark:text-ink-400">
          {paths.length === 1
            ? paths[0]
            : `${paths.length} entries from this folder, packed as they are named here.`}
        </p>
      </form>
    </Modal>
  )
}

/** A name somebody would have typed: the entry's own for one, the folder's for several. */
function suggestName(directory: string, paths: string[], format: string): string {
  const single = paths.length === 1 ? paths[0] : undefined
  if (single) {
    return `${single}.${suffixOf(format)}`
  }
  const folder = directory === '' ? 'files' : (directory.split('/').pop() ?? 'files')
  return directory === ''
    ? `${folder}.${suffixOf(format)}`
    : `${directory}/${folder}.${suffixOf(format)}`
}

function withSuffix(current: string, format: string): string {
  const stripped = current.replace(/\.(zip|tar\.gz|tgz)$/i, '')
  return `${stripped}.${suffixOf(format)}`
}

function suffixOf(format: string): string {
  return format === 'tar.gz' ? 'tar.gz' : 'zip'
}
