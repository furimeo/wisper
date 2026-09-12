import {router} from '@inertiajs/react'
import {useState} from 'react'

import {t} from '@/i18n'
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
      title: t('node.confirm.upgrade.title', {name: summary.name, version: node.upgradeAvailable ?? ''}),
      body: t('node.confirm.upgrade.body'),
      confirmLabel: t('node.confirm.upgrade.confirm'),
    })
    if (confirmed) {
      post('upgrade')
    }
  }

  async function remove() {
    const confirmed = await askConfirmation({
      title: t('node.confirm.delete.title', {name: summary.name}),
      body: t('node.confirm.delete.body'),
      confirmLabel: t('node.confirm.delete.confirm'),
      tone: 'danger',
      requireText: summary.name,
      requireTextLabel: t('node.confirm.delete.requireTextLabel', {name: summary.name}),
    })
    if (confirmed) {
      post('delete', {confirmation: summary.name})
    }
  }

  const controllable = summary.lifecycle !== 'CREATED' && summary.lifecycle !== 'RETIRED'

  return (
    <>
      <Card
        title={t('node.ops.title')}
        description={t('node.ops.description')}
      >
        <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
          <Button
            variant="secondary"
            block
            disabled={!controllable || !summary.connected}
            loading={pending === 'drain-survey'}
            onClick={() => post('drain-survey')}
          >
            {t('node.ops.survey')}
          </Button>

          <Button
            variant="secondary"
            block
            disabled={!controllable || summary.lifecycle === 'DRAINING'}
            onClick={() => setAsking('drain')}
          >
            {t('node.ops.drain')}
          </Button>

          {summary.lifecycle === 'SUSPENDED' ? (
            <Button
              variant="secondary"
              block
              loading={pending === 'resume'}
              onClick={() => post('resume')}
            >
              {t('node.ops.resume')}
            </Button>
          ) : (
            <Button
              variant="secondary"
              block
              disabled={!controllable}
              onClick={() => setAsking('suspend')}
            >
              {t('node.ops.suspend')}
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
              ? t('node.ops.agentCurrent')
              : t('node.ops.upgradeTo', {version: node.upgradeAvailable})}
          </Button>

          <Button
            variant="danger"
            block
            className="sm:col-span-2"
            loading={pending === 'delete'}
            onClick={() => void remove()}
          >
            {t('node.ops.deleteRecord')}
          </Button>
        </div>

        {summary.connected ? null : (
          <p className="mt-3 text-sm leading-relaxed text-ink-500 dark:text-ink-400">
            {t('node.ops.streamWarning')}
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
      form.setError('reason', t('node.reason.required'))
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
      title={draining ? t('node.reason.drain.title', {name: nodeName}) : t('node.reason.suspend.title', {name: nodeName})}
      description={
        draining
          ? t('node.reason.drain.description')
          : t('node.reason.suspend.description')
      }
      footer={
        <div className="flex flex-col gap-2 sm:flex-row-reverse">
          <Button block className="sm:w-auto" onClick={submit}>
            {draining ? t('node.reason.drain.submit') : t('node.reason.suspend.submit')}
          </Button>
          <Button variant="ghost" block className="sm:w-auto" onClick={onClose}>
            {t('node.reason.cancel')}
          </Button>
        </div>
      }
    >
      <Textarea
        {...form.bind('reason')}
        label={t('node.reason.why')}
        rows={3}
        autoGrow
        maxLength={500}
        required
        placeholder={draining ? t('node.reason.drain.placeholder') : t('node.reason.suspend.placeholder')}
        hint={t('node.reason.hint')}
      />
    </Modal>
  )
}
