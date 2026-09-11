import {Head, usePage} from '@inertiajs/react'
import {useState} from 'react'

import {ButtonLink, Card, PageHeader, Tabs} from '@/shell'

import {NodeCapacity} from './NodeCapacity'
import {NodeDoctorReportPanel} from './NodeDoctorReportPanel'
import {NodeEnrolmentPanel} from './NodeEnrolmentPanel'
import {NodeFacts} from './NodeFacts'
import {NodeIsolationAlert} from './NodeIsolationAlert'
import {NodeLifecycleActions} from './NodeLifecycleActions'
import {NodeSettingsForm} from './NodeSettingsForm'
import {NodeStateBadges} from './NodeStateBadges'
import {NodeSuspensionAlert} from './NodeSuspensionAlert'
import {NodeTokenHistory} from './NodeTokenHistory'
import type {NodeDetail} from './nodeTypes'
import {nodeSentence} from './nodeVocabulary'
import {useLiveNodes} from './useLiveNodes'

/**
 * `GET /admin/nodes/{nodeId}` - one machine, in full.
 *
 * There is no separate enrol wizard, doctor page, drain page or upgrade page, because all
 * four are things you do to a node while looking at it. They are sections here, and the
 * three tabs below only decide which of them is on screen at 375px - everything is one
 * page and one round trip.
 *
 * The order is fixed and it is not aesthetic. Anything that says this machine is not
 * keeping a promise - suspended, cloned, no gVisor, no enforceable quota - comes before
 * the facts, before the meters and before every button, because it is the thing that
 * changes what an operator should do next.
 */
type AdminNodeDetailProps = {
  node: NodeDetail
  /** Flash props: present on exactly the render after a token is issued. */
  bootstrapToken?: string
  bootstrapTokenExpiresAt?: string
  installCommand?: string
  installChecksum?: string
}

type Section = 'overview' | 'preflight' | 'operations'

export default function AdminNodeDetailPage() {
  const {node, bootstrapToken, bootstrapTokenExpiresAt, installCommand, installChecksum} =
    usePage<AdminNodeDetailProps>().props
  const [section, setSection] = useState<Section>('overview')

  const summary = node.summary
  const settling =
    !summary.converged ||
    summary.lifecycle === 'DRAINING' ||
    summary.lifecycle === 'CREATED' ||
    (summary.lifecycle === 'ENROLLED' && !summary.connected)
  useLiveNodes(['node'], settling)

  const failing =
    node.doctor !== null &&
    (!node.doctor.requiredChecksPassed ||
      node.doctor.checks.some((check) => check.outcome === 'FAIL'))

  return (
    <div className="flex flex-col gap-4">
      <Head title={summary.name} />

      <PageHeader
        title={summary.name}
        description={nodeSentence(summary)}
        actions={
          <ButtonLink
            href={`/admin/metrics/nodes/${summary.id}`}
            variant="secondary"
            block
            className="sm:w-auto"
          >
            Metrics
          </ButtonLink>
        }
      />

      <NodeStateBadges node={summary} />

      <NodeSuspensionAlert node={summary} />
      <NodeIsolationAlert node={summary} />

      <NodeEnrolmentPanel
        node={node}
        bootstrapToken={bootstrapToken}
        bootstrapTokenExpiresAt={bootstrapTokenExpiresAt}
        installCommand={installCommand}
        installChecksum={installChecksum}
      />

      <Tabs
        label="Node sections"
        value={section}
        onSelect={(value) => setSection(value as Section)}
        items={[
          {value: 'overview', label: 'Overview'},
          {
            value: 'preflight',
            label: 'Preflight',
            badge: failing ? <span className="text-failed">!</span> : undefined,
          },
          {value: 'operations', label: 'Operations'},
        ]}
      />

      {section === 'overview' ? (
        <>
          <Card
            title="Capacity"
            description={
              summary.description ??
              'What the node last reported. Placement keeps headroom, so a machine much past ' +
                'eighty per cent starts refusing work before it is actually full.'
            }
          >
            <NodeCapacity node={summary} />
          </Card>
          <NodeFacts node={node} />
        </>
      ) : null}

      {section === 'preflight' ? <NodeDoctorReportPanel report={node.doctor} /> : null}

      {section === 'operations' ? (
        <>
          <NodeLifecycleActions node={node} />
          <NodeSettingsForm node={summary} />
          <NodeTokenHistory nodeId={summary.id} tokens={node.tokens} />
        </>
      ) : null}
    </div>
  )
}
