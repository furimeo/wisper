import {Icon} from '@/shell'

import type {NodeSummary} from './nodeTypes'

/**
 * The two weaknesses a node is never allowed to report quietly.
 *
 * Design §7.2 makes both of these advisory rather than fatal: a machine without `runsc`
 * still runs workloads, and a machine whose volume filesystem is not XFS still stores
 * them. What it does not permit is the panel treating either as normal. Missing gVisor
 * means every customer container on this machine is isolated by the host kernel alone; a
 * non-XFS filesystem means every volume's size limit is a number this panel displays and
 * nothing on earth enforces.
 *
 * Both are guarantees the platform sells and is not keeping on this machine, so they get
 * a red panel at the top of the page with the consequence spelled out in a sentence -
 * not a grey chip in a facts grid, and never a green tick with an asterisk.
 */
export function NodeIsolationAlert({node}: {node: NodeSummary}) {
  if (!node.lessIsolated && !node.quotaAdvisory) {
    return null
  }

  return (
    <section
      aria-labelledby={`isolation-${node.id}`}
      className="rounded-xl border border-failed/50 bg-failed/10 p-4 md:p-5"
    >
      <div className="flex items-start gap-3">
        <span className="mt-0.5 shrink-0 text-failed">
          <Icon name="shield" label="Warning" />
        </span>
        <div className="min-w-0">
          <h2 id={`isolation-${node.id}`} className="text-base font-semibold">
            {node.name} is running with weaker guarantees than the platform claims
          </h2>

          <ul className="mt-2 flex flex-col gap-3">
            {node.lessIsolated ? (
              <li className="text-sm leading-relaxed text-ink-800 dark:text-ink-200">
                <strong className="font-semibold">gVisor is not installed.</strong> Every
                container placed here runs under <code className="font-mono">runc</code>,
                so a customer&apos;s workload is separated from this machine by the host
                kernel and nothing else. A kernel bug that gVisor would have absorbed is a
                host compromise here.
                <span className="mt-1 block text-ink-600 dark:text-ink-400">
                  Install <code className="font-mono">runsc</code> on the machine and
                  restart sasayaki, or stop scheduling untrusted work here.
                </span>
              </li>
            ) : null}

            {node.quotaAdvisory ? (
              <li className="text-sm leading-relaxed text-ink-800 dark:text-ink-200">
                <strong className="font-semibold">
                  The volume filesystem has no project quota.
                </strong>{' '}
                Every disk limit shown for a volume on this node is advisory: one customer
                can fill the disk and take every other service on the machine down with
                them.
                <span className="mt-1 block text-ink-600 dark:text-ink-400">
                  Project quota needs XFS with <code className="font-mono">pquota</code>{' '}
                  on <code className="font-mono">/var/lib/wisper</code>. Moving the volume
                  root means moving customer data, so decide it before this node fills up.
                </span>
              </li>
            ) : null}
          </ul>
        </div>
      </div>
    </section>
  )
}
