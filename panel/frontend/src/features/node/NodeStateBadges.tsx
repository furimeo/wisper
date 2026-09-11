import {Badge} from '@/shell'

import type {NodeSummary} from './nodeTypes'
import {
  connectionLabel,
  connectionTone,
  lifecycleLabel,
  lifecycleTone,
} from './nodeVocabulary'

/**
 * The pills that say what a node is and what it is doing.
 *
 * Two of them, never merged, for the same reason a service shows intent and fact
 * separately: the lifecycle is what the panel decided and the connection is what the
 * machine is doing about it. "Enrolled" and "Offline" together is a real and common state
 * - a tunnel dropped, the containers are fine - and one pill could not say it.
 *
 * The generation pill only appears when it disagrees. A node at the published generation
 * is the normal case and does not need a badge to say so.
 */
export function NodeStateBadges({node}: {node: NodeSummary}) {
  return (
    <div className="flex flex-wrap items-center gap-1.5">
      <Badge tone={lifecycleTone(node.lifecycle)} dot pulse={node.lifecycle === 'DRAINING'}>
        {lifecycleLabel(node.lifecycle)}
      </Badge>

      {node.lifecycle === 'CREATED' ? null : (
        <Badge tone={connectionTone(node.connectionState)} dot>
          {connectionLabel(node.connectionState)}
        </Badge>
      )}

      {!node.converged && node.lifecycle !== 'CREATED' ? (
        <Badge tone="degraded" dot pulse>
          Generation {node.appliedGeneration}/{node.desiredGeneration}
        </Badge>
      ) : null}

      {node.needsUpgrade ? <Badge tone="degraded">Update available</Badge> : null}

      {!node.schedulable && node.lifecycle === 'ENROLLED' ? (
        <Badge tone="neutral">Unschedulable</Badge>
      ) : null}
    </div>
  )
}

/**
 * The isolation pills, which are shown on their own wherever a node is listed.
 *
 * Separate from the state pills because they are not states - they are properties of the
 * machine that weaken what the platform promises, and they must not be buried at the end
 * of a row of five neutral badges where the eye stops reading.
 */
export function NodeIsolationBadges({node}: {node: NodeSummary}) {
  if (!node.lessIsolated && !node.quotaAdvisory) {
    return null
  }
  return (
    <div className="flex flex-wrap items-center gap-1.5">
      {node.lessIsolated ? <Badge tone="failed" dot>No gVisor</Badge> : null}
      {node.quotaAdvisory ? <Badge tone="failed" dot>Disk quota unenforced</Badge> : null}
    </div>
  )
}
