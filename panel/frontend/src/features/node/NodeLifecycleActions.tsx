import {router} from '@inertiajs/react'
import {useState} from 'react'

import {Button, Card, Modal, Textarea, askConfirmation, useFormFields} from '@/shell'

import type {NodeDetail} from './nodeTypes'

/**
 * The buttons that change what a machine is doing.
 *
 * Every one of them is a POST that redirects, and every one answers in a sentence the
 * operator can act on. None of them touches a customer's containers: draining moves what
 * it can and lists what it cannot, suspending stops the panel publishing, upgrading swaps
 * a control-plane binary that the workloads do not depend on, and deleting removes a row.
 * That is said on the buttons rather than assumed, because the cost of guessing wrong here
 * is somebody's site.
 *
 * Survey before drain is a real round trip that changes nothing. An operator about to take
 * a machine away needs to know what is pinned to it *before* they commit, and the only
 * thing that knows is the node.
 */
type Reason = 'drain' | 'suspend'

interface ReasonValues {
  reason: string
  [key: string]: string
}

export function NodeLifecycleActions({node}: {node: NodeDetail}) {
  const summary = node.summary
  const [asking, setAsking] = useState<Reason | null>(null)
  const [pending, setPending] = useState<string | null>(null)

  function post(action: string, data: Record<string, string> = {}) {
    setPending(action)
    router.post(`/admin/nodes/${summary.id}/${action}`, data, {
      preserveScroll: true,
      onFinish: () => setPending(null),
    })
  }

  async function upgrade() {
    const confirmed = await askConfirmation({
      title: `Upgrade ${summary.name} to ${node.upgradeAvailable}?`,
      body:
        'The node downloads the new binary, verifies its checksum, swaps it atomically and ' +
        'restarts. Customer containers are not restarted - sasayaki is only the control plane. ' +
        'If the new binary fails to come up, the node rolls back to the one it is running now.',
      confirmLabel: 'Upgrade it',
    })
    if (confirmed) {
      post('upgrade')
    }
  }

  async function remove() {
    const confirmed = await askConfirmation({
      title: `Delete the record for ${summary.name}?`,
      body:
        'Nothing on the machine is touched: containers keep running, volumes keep their bytes ' +
        'and Caddy keeps serving. The panel simply stops knowing about it. Run `sasayaki ' +
        'uninstall` on the machine afterwards to remove the daemon.',
      confirmLabel: 'Delete the record',
      tone: 'danger',
      requireText: summary.name,
      requireTextLabel: `Type ${summary.name} to confirm`,
    })
    if (confirmed) {
      post('delete', {confirmation: summary.name})
    }
  }

  const controllable = summary.lifecycle !== 'CREATED' && summary.lifecycle !== 'RETIRED'

  return (
    <>
      <Card
        title="Operations"
        description="Nothing here stops a customer's containers. Draining moves what can move,
          suspending stops the panel publishing, and deleting only removes the panel's record."
      >
        <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
          <Button
            variant="secondary"
            block
            disabled={!controllable || !summary.connected}
            loading={pending === 'drain-survey'}
            onClick={() => post('drain-survey')}
          >
            What would draining do?
          </Button>

          <Button
            variant="secondary"
            block
            disabled={!controllable || summary.lifecycle === 'DRAINING'}
            onClick={() => setAsking('drain')}
          >
            Drain
          </Button>

          {summary.lifecycle === 'SUSPENDED' ? (
            <Button
              variant="secondary"
              block
              loading={pending === 'resume'}
              onClick={() => post('resume')}
            >
              Resume
            </Button>
          ) : (
            <Button
              variant="secondary"
              block
              disabled={!controllable}
              onClick={() => setAsking('suspend')}
            >
              Suspend
            </Button>
          )}

          <Button
            variant="secondary"
            block
            disabled={node.upgradeAvailable === null || !summary.connected}
            loading={pending === 'upgrade'}
            onClick={() => void upgrade()}
          >
            {node.upgradeAvailable === null
              ? 'Agent is current'
              : `Upgrade to ${node.upgradeAvailable}`}
          </Button>

          <Button
            variant="danger"
            block
            className="sm:col-span-2"
            loading={pending === 'delete'}
            onClick={() => void remove()}
          >
            Delete this node record
          </Button>
        </div>

        {summary.connected ? null : (
          <p className="mt-3 text-sm leading-relaxed text-ink-500 dark:text-ink-400">
            Draining and upgrading need an open control stream, and there is none right now.
            Suspending and deleting are panel-side and work regardless.
          </p>
        )}
      </Card>

      <ReasonDialog
        key={asking ?? 'none'}
        open={asking !== null}
        kind={asking}
        nodeName={summary.name}
        onClose={() => setAsking(null)}
        onSubmit={(action, reason) => {
          setAsking(null)
          post(action, {reason})
        }}
      />
    </>
  )
}

/**
 * Drain and suspend both want a sentence, and the server requires one.
 *
 * It is not bureaucracy: the reason is shown on this page afterwards, and the next
 * operator - or the same one in a fortnight - reads it instead of guessing why a machine
 * is out of the pool.
 */
function ReasonDialog({
  open,
  kind,
  nodeName,
  onClose,
  onSubmit,
}: {
  open: boolean
  kind: Reason | null
  nodeName: string
  onClose: () => void
  onSubmit: (action: Reason, reason: string) => void
}) {
  const form = useFormFields<ReasonValues>({reason: ''})

  function submit() {
    if (kind === null) {
      return
    }
    if (form.data.reason.trim().length === 0) {
      form.setError('reason', 'Say why. It is shown on the node’s page.')
      return
    }
    onSubmit(kind, form.data.reason.trim())
    form.reset()
  }

  const draining = kind === 'drain'

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={draining ? `Drain ${nodeName}?` : `Suspend ${nodeName}?`}
      description={
        draining
          ? 'It stops accepting new placements and evacuates every service that has no volume. ' +
            'Anything holding a volume stays where it is and is listed for you - a silent ' +
            'migration is silent data loss.'
          : 'The panel stops publishing to this machine. Everything already running on it keeps ' +
            'running, and it keeps serving customers.'
      }
      footer={
        <div className="flex flex-col gap-2 sm:flex-row-reverse">
          <Button block className="sm:w-auto" onClick={submit}>
            {draining ? 'Drain it' : 'Suspend it'}
          </Button>
          <Button variant="ghost" block className="sm:w-auto" onClick={onClose}>
            Cancel
          </Button>
        </div>
      }
    >
      <Textarea
        {...form.bind('reason')}
        label="Why"
        rows={3}
        autoGrow
        maxLength={500}
        required
        placeholder={draining ? 'Replacing the disks on Thursday' : 'Investigating a noisy neighbour'}
        hint="Shown on this node's page afterwards."
      />
    </Modal>
  )
}
