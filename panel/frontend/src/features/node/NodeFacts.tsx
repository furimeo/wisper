import type {ReactNode} from 'react'

import {t} from '@/i18n'
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
    <Card title={t('node.facts.title')}>
      <CardFacts>
        <CardFact label={t('node.facts.nodeId')}>
          <span className="font-mono text-xs" title={summary.id}>
            {shortId(summary.id)}
          </span>
        </CardFact>

        <CardFact label={t('node.facts.publicAddress')}>
          {summary.publicAddress ? (
            <span className="font-mono text-xs">{summary.publicAddress}</span>
          ) : (
            <Muted>{t('node.facts.publicAddressNotSet')}</Muted>
          )}
        </CardFact>

        <CardFact label={t('node.facts.agentVersion')}>
          {summary.agentVersion ? (
            <span className="font-mono text-xs">
              {summary.agentVersion}
              {node.upgradeAvailable ? (
                <span className="ml-1.5 font-sans text-degraded">
                  {t('node.facts.upgradeAvailable', {version: node.upgradeAvailable})}
                </span>
              ) : null}
            </span>
          ) : (
            <Muted>{t('node.facts.neverReported')}</Muted>
          )}
        </CardFact>

        <CardFact label={t('node.facts.protocolVersion')}>
          {summary.protocolVersion === null ? (
            <Muted>{t('node.facts.neverNegotiated')}</Muted>
          ) : (
            <span className="tabular-nums">{summary.protocolVersion}</span>
          )}
        </CardFact>

        <CardFact label={t('node.facts.generation')}>
          <span className="tabular-nums">
            {t('node.facts.generationApplied', {
              applied: summary.appliedGeneration,
              desired: summary.desiredGeneration,
            })}
          </span>
          {summary.converged ? null : (
            <span className="mt-0.5 block text-xs text-degraded">
              {t('node.facts.stillCatchingUp')}
            </span>
          )}
        </CardFact>

        <CardFact label={t('node.facts.workloads')}>
          <span className="tabular-nums">
            {t('node.facts.workloadsCount', {
              running: summary.runningWorkloadCount,
              total: summary.workloadCount,
            })}
          </span>
        </CardFact>

        <CardFact label={t('node.facts.lastHeartbeat')}>
          <RelativeTime at={summary.lastHeartbeatAt} fallback={t('node.facts.none')} />
        </CardFact>

        <CardFact label={t('node.facts.stream')}>
          {summary.connected ? (
            <>
              {t('node.facts.streamOpenSince')}<RelativeTime at={node.lastConnectedAt} fallback={t('node.facts.unknownTime')} />
            </>
          ) : (
            <>
              {t('node.facts.streamClosed')}<RelativeTime at={node.lastDisconnected} fallback={t('node.facts.neverOpened')} />
            </>
          )}
        </CardFact>

        <CardFact label={t('node.facts.dialEndpoint')}>
          <span className="font-mono text-xs break-all">{node.dialEndpoint}</span>
        </CardFact>

        <CardFact label={t('node.facts.docker')}>
          {summary.dockerHealthy === null ? (
            <Muted>{t('node.capacity.notReported')}</Muted>
          ) : summary.dockerHealthy ? (
            <>{node.dockerVersion ?? t('node.facts.dockerReachable')}</>
          ) : (
            <span className="text-failed">
              {t('node.facts.dockerUnreachable')}
            </span>
          )}
        </CardFact>

        <CardFact label={t('node.facts.kernel')}>{node.kernelVersion ?? <Muted>{t('node.capacity.notReported')}</Muted>}</CardFact>

        <CardFact label={t('node.facts.os')}>
          {node.osDescription ?? <Muted>{t('node.capacity.notReported')}</Muted>}
        </CardFact>

        <CardFact label={t('node.facts.volumeFilesystem')}>
          {node.volumeFilesystem ? (
            <span className={summary.quotaAdvisory ? 'text-failed' : undefined}>
              {node.volumeFilesystem}
              {summary.quotaAdvisory ? t('node.facts.noProjectQuota') : ''}
            </span>
          ) : (
            <Muted>{t('node.capacity.notReported')}</Muted>
          )}
        </CardFact>

        <CardFact label={t('node.facts.tags')}>
          {summary.tags.length === 0 ? <Muted>{t('node.facts.none')}</Muted> : summary.tags.join(', ')}
        </CardFact>
      </CardFacts>

      {skew ? (
        <p className="mt-3 rounded-lg border border-degraded/50 bg-degraded/10 px-3 py-2.5 text-sm leading-relaxed">
          {skew}
        </p>
      ) : null}

      {summary.reconcileError ? (
        <p className="mt-3 rounded-lg border border-failed/50 bg-failed/10 px-3 py-2.5 text-sm leading-relaxed">
          <span className="font-medium">{t('node.facts.lastReconcileFailed')}</span>
          {summary.reconcileError}
        </p>
      ) : null}
    </Card>
  )
}

function Muted({children}: {children: ReactNode}) {
  return <span className="text-ink-500 dark:text-ink-400">{children}</span>
}
