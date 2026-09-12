import {Link, router} from '@inertiajs/react'
import {useState} from 'react'

import {t} from '@/i18n'
import {Button, Icon, askConfirmation} from '@/shell'

import type {NodeSummary} from './nodeTypes'
import {shortId} from './nodeVocabulary'

/**
 * Why the panel stopped publishing to a machine, and the one button that undoes it.
 *
 * A suspension is never silent and never a grey chip. Three of the four reasons are the
 * panel protecting itself from something it cannot resolve alone, and the fourth is an
 * operator who will not remember why in a fortnight - so the reason is a paragraph, in the
 * place the machine is looked at.
 *
 * Nothing on the machine stops. That sentence appears in every one of these because it is
 * the question an operator has while reading them, and because acting on the wrong answer
 * - assuming the customer is already down - is how a suspension turns into an outage.
 */
export function NodeSuspensionAlert({node}: {node: NodeSummary}) {
  const [resuming, setResuming] = useState(false)

  if (node.lifecycle !== 'SUSPENDED' || node.suspensionReason === null) {
    return null
  }

  const cloned = node.suspensionReason === 'DUPLICATE_FINGERPRINT'

  async function resume() {
    const confirmed = await askConfirmation({
      title: t('node.suspension.resumeConfirm.title', {name: node.name}),
      body: cloned
        ? t('node.suspension.resumeConfirm.bodyCloned')
        : t('node.suspension.resumeConfirm.bodyNormal'),
      confirmLabel: t('node.suspension.resumeConfirm.confirm'),
      tone: cloned ? 'danger' : 'normal',
    })
    if (!confirmed) {
      return
    }
    setResuming(true)
    router.post(
      `/admin/nodes/${node.id}/resume`,
      {},
      {preserveScroll: true, onFinish: () => setResuming(false)},
    )
  }

  return (
    <section
      aria-labelledby={`suspended-${node.id}`}
      className="rounded-xl border border-failed/50 bg-failed/10 p-4 md:p-5"
    >
      <div className="flex items-start gap-3">
        <span className="mt-0.5 shrink-0 text-failed">
          <Icon name="shield" label={t('node.lifecycle.suspended')} />
        </span>
        <div className="min-w-0 flex-1">
          <h2 id={`suspended-${node.id}`} className="text-base font-semibold">
            {cloned
              ? t('node.suspension.headerCloned', {name: node.name})
              : t('node.suspension.headerSuspended', {name: node.name})}
          </h2>

          <p className="mt-1.5 text-sm leading-relaxed text-ink-700 dark:text-ink-300">
            {node.suspensionExplanation}
          </p>

          {cloned ? (
            <p className="mt-2 text-sm leading-relaxed text-ink-700 dark:text-ink-300">
              {t('node.suspension.clonedDetail')}
            </p>
          ) : null}

          <div className="mt-3">
            <Button
              variant="secondary"
              loading={resuming}
              onClick={() => void resume()}
              className="w-full sm:w-auto"
            >
              {t('node.suspension.resumeButton')}
            </Button>
          </div>
        </div>
      </div>
    </section>
  )
}

/**
 * The fleet-wide version: every machine currently suspended for a duplicate fingerprint,
 * together, because the pair is the story.
 *
 * Two rows sitting next to each other is what tells an operator this is a clone rather
 * than two unrelated failures, and one row buried alphabetically in a list of forty is
 * what makes them miss it.
 */
export function ClonedNodeAlert({nodes}: {nodes: NodeSummary[]}) {
  const cloned = nodes.filter((node) => node.suspensionReason === 'DUPLICATE_FINGERPRINT')
  if (cloned.length === 0) {
    return null
  }

  return (
    <section
      aria-labelledby="cloned-nodes"
      className="rounded-xl border border-failed/50 bg-failed/10 p-4 md:p-5"
    >
      <div className="flex items-start gap-3">
        <span className="mt-0.5 shrink-0 text-failed">
          <Icon name="shield" label={t('node.doctor.outcome.warn')} />
        </span>
        <div className="min-w-0 flex-1">
          <h2 id="cloned-nodes" className="text-base font-semibold">
            {t('node.cloned.title', {count: cloned.length})}
          </h2>
          <p className="mt-1.5 text-sm leading-relaxed text-ink-700 dark:text-ink-300">
            {t('node.cloned.description')}
          </p>

          <ul className="mt-3 flex flex-col gap-2">
            {cloned.map((node) => (
              <li key={node.id}>
                <Link
                  href={`/admin/nodes/${node.id}`}
                  className="flex touch-target items-center justify-between gap-3 rounded-lg bg-white px-3 text-sm dark:bg-ink-950"
                >
                  <span className="min-w-0 truncate font-medium">{node.name}</span>
                  <span className="shrink-0 font-mono text-xs text-ink-500 dark:text-ink-400">
                    {node.publicAddress ?? shortId(node.id)}
                  </span>
                  <Icon name="chevronRight" className="size-4 shrink-0 text-ink-400" />
                </Link>
              </li>
            ))}
          </ul>
        </div>
      </div>
    </section>
  )
}
