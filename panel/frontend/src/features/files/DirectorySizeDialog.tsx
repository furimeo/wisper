import {useEffect, useState} from 'react'

import {Badge, ByteQuota, ByteSize, Button, ErrorState, Modal, Spinner} from '@/shell'

import {FileRequestFailed, measureDirectory} from './fileRequests'
import type {DirectorySizeView} from './fileTypes'

/**
 * How much a folder holds, measured when somebody asks.
 *
 * Its own request and its own dialog because it walks the tree, which on a real volume
 * takes seconds. Folding it into the listing would make every directory page pay for a
 * number most people are not looking for - and on a phone, seconds of spinner before any
 * file appears is the difference between a file manager and a screen you close.
 *
 * `approximate` is shown rather than hidden. The node stops at a walk budget on a tree
 * large enough to matter, and a figure labelled "at least" is usable; the same figure
 * presented as exact is a number somebody plans around and is wrong about.
 */
export function DirectorySizeDialog({
  serviceId,
  rootId,
  path,
  onClose,
}: {
  serviceId: string
  rootId: string
  /** The folder to measure, relative to the root, or null when the dialog is shut. */
  path: string | null
  onClose: () => void
}) {
  const [size, setSize] = useState<DirectorySizeView | null>(null)
  const [failure, setFailure] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    if (path === null) {
      setSize(null)
      setFailure(null)
      return
    }
    const abort = new AbortController()
    setSize(null)
    setFailure(null)
    measureDirectory(serviceId, rootId, path, abort.signal)
      .then(setSize)
      .catch((cause: unknown) => {
        if (abort.signal.aborted) {
          return
        }
        setFailure(
          cause instanceof FileRequestFailed
            ? cause.message
            : 'The node did not finish measuring this folder.',
        )
      })
    return () => abort.abort()
  }, [serviceId, rootId, path, attempt])

  return (
    <Modal
      open={path !== null}
      onClose={onClose}
      title="Folder size"
      description={path === '' ? 'The top of this tree' : (path ?? undefined)}
      size="sm"
      footer={
        <Button variant="secondary" onClick={onClose} block>
          Close
        </Button>
      }
    >
      {failure ? (
        <ErrorState
          title="It could not be measured"
          description={failure}
          onRetry={() => setAttempt((count) => count + 1)}
        />
      ) : size === null ? (
        <div className="flex items-center gap-3 py-6 text-sm text-ink-600 dark:text-ink-400">
          <Spinner />
          Walking the tree. A folder with a lot in it takes a few seconds.
        </div>
      ) : (
        <dl className="flex flex-col gap-3 text-sm">
          <div className="flex items-baseline justify-between gap-3">
            <dt className="text-ink-600 dark:text-ink-400">Total</dt>
            <dd className="flex items-center gap-2 font-medium text-ink-900 dark:text-ink-100">
              {size.approximate ? <Badge tone="degraded">at least</Badge> : null}
              <ByteSize bytes={size.bytes} />
            </dd>
          </div>
          <div className="flex items-baseline justify-between gap-3">
            <dt className="text-ink-600 dark:text-ink-400">Files</dt>
            <dd className="tabular-nums">{size.fileCount.toLocaleString()}</dd>
          </div>
          <div className="flex items-baseline justify-between gap-3">
            <dt className="text-ink-600 dark:text-ink-400">Folders</dt>
            <dd className="tabular-nums">{size.directoryCount.toLocaleString()}</dd>
          </div>
          {size.quotaBytes > 0 ? (
            <div className="pt-1">
              <dt className="mb-1 text-ink-600 dark:text-ink-400">Against this tree's quota</dt>
              <dd>
                <ByteQuota used={size.bytes} limit={size.quotaBytes} />
              </dd>
            </div>
          ) : null}
          {size.approximate ? (
            <p className="pt-1 text-xs text-ink-500 dark:text-ink-400">
              The node stopped at its walk budget, so the real total is larger than this.
              Measuring a smaller folder gives an exact answer.
            </p>
          ) : null}
        </dl>
      )}
    </Modal>
  )
}
