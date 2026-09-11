import type {ReactNode} from 'react'

import {Card, CardFact, CardFacts, RelativeTime} from '@/shell'

import type {NodeDetail} from './nodeTypes'
import {clockSkewSentence, shortId} from './nodeVocabulary'

/**
 * The identifying facts: which sasayaki, which protocol, which generation, and the two
 * timestamps that say whether the stream is healthy or merely present.
 *
 * Generation is here rather than in a badge because the interesting reading is the pair.
 * The panel owns `desiredGeneration` and the node owns `appliedGeneration`; equal means
 * converged, and a gap that is not closing within a reconcile or two is the node telling
 * you something without an error message.
 *
 * Clock skew gets a sentence rather than a number. A machine three minutes out breaks ACME
 * and TLS with symptoms that point at DNS, at the certificate authority, at anything but
 * the time - so if it is worth showing at all, it is worth saying what it will break.
 */
export function NodeFacts({node}: {node: NodeDetail}) {
  const summary = node.summary
  const skew = clockSkewSentence(node.clockSkewMillis)

  return (
    <Card title="Facts">
      <CardFacts>
        <CardFact label="Node id">
          <span className="font-mono text-xs" title={summary.id}>
            {shortId(summary.id)}
          </span>
        </CardFact>

        <CardFact label="Public address">
          {summary.publicAddress ? (
            <span className="font-mono text-xs">{summary.publicAddress}</span>
          ) : (
            <Muted>not set - a domain has nothing to point at</Muted>
          )}
        </CardFact>

        <CardFact label="Agent version">
          {summary.agentVersion ? (
            <span className="font-mono text-xs">
              {summary.agentVersion}
              {node.upgradeAvailable ? (
                <span className="ml-1.5 font-sans text-degraded">
                  → {node.upgradeAvailable} available
                </span>
              ) : null}
            </span>
          ) : (
            <Muted>never reported</Muted>
          )}
        </CardFact>

        <CardFact label="Protocol version">
          {summary.protocolVersion === null ? (
            <Muted>never negotiated</Muted>
          ) : (
            <span className="tabular-nums">{summary.protocolVersion}</span>
          )}
        </CardFact>

        <CardFact label="Generation">
          <span className="tabular-nums">
            {summary.appliedGeneration} applied / {summary.desiredGeneration} published
          </span>
          {summary.converged ? null : (
            <span className="mt-0.5 block text-xs text-degraded">
              Still catching up. It reconciles every fifteen seconds.
            </span>
          )}
        </CardFact>

        <CardFact label="Workloads">
          <span className="tabular-nums">
            {summary.runningWorkloadCount} running of {summary.workloadCount}
          </span>
        </CardFact>

        <CardFact label="Last heartbeat">
          <RelativeTime at={summary.lastHeartbeatAt} fallback="never" />
        </CardFact>

        <CardFact label="Stream">
          {summary.connected ? (
            <>
              open since <RelativeTime at={node.lastConnectedAt} fallback="an unknown time" />
            </>
          ) : (
            <>
              closed <RelativeTime at={node.lastDisconnected} fallback="- never opened" />
            </>
          )}
        </CardFact>

        <CardFact label="Dial endpoint">
          <span className="font-mono text-xs break-all">{node.dialEndpoint}</span>
        </CardFact>

        <CardFact label="Docker">
          {summary.dockerHealthy === null ? (
            <Muted>not reported</Muted>
          ) : summary.dockerHealthy ? (
            <>{node.dockerVersion ?? 'reachable'}</>
          ) : (
            <span className="text-failed">
              unreachable - the node is retrying and has deleted nothing
            </span>
          )}
        </CardFact>

        <CardFact label="Kernel">{node.kernelVersion ?? <Muted>not reported</Muted>}</CardFact>

        <CardFact label="Operating system">
          {node.osDescription ?? <Muted>not reported</Muted>}
        </CardFact>

        <CardFact label="Volume filesystem">
          {node.volumeFilesystem ? (
            <span className={summary.quotaAdvisory ? 'text-failed' : undefined}>
              {node.volumeFilesystem}
              {summary.quotaAdvisory ? ' - no project quota' : ''}
            </span>
          ) : (
            <Muted>not reported</Muted>
          )}
        </CardFact>

        <CardFact label="Tags">
          {summary.tags.length === 0 ? <Muted>none</Muted> : summary.tags.join(', ')}
        </CardFact>
      </CardFacts>

      {skew ? (
        <p className="mt-3 rounded-lg border border-degraded/50 bg-degraded/10 px-3 py-2.5 text-sm leading-relaxed">
          {skew}
        </p>
      ) : null}

      {summary.reconcileError ? (
        <p className="mt-3 rounded-lg border border-failed/50 bg-failed/10 px-3 py-2.5 text-sm leading-relaxed">
          <span className="font-medium">Last reconcile failed: </span>
          {summary.reconcileError}
        </p>
      ) : null}
    </Card>
  )
}

function Muted({children}: {children: ReactNode}) {
  return <span className="text-ink-500 dark:text-ink-400">{children}</span>
}
