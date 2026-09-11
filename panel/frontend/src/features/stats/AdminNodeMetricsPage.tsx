import {Head, usePage} from '@inertiajs/react'

import {ButtonLink, Icon, PageHeader} from '@/shell'

import {MetricDashboard} from './MetricDashboard'
import type {MetricSeries, MetricWindow} from './statsTypes'

/**
 * `GET /admin/metrics/nodes/{nodeId}` - one machine's own load.
 *
 * The other half of what a node pushes. `PushStats` carries a reading per workload *and*
 * one for the machine, and without this screen the machine-level samples would be stored,
 * rolled up, retained and never looked at.
 *
 * The series excludes the workloads the machine is carrying - it is the machine's own
 * totals - so a node is never shown using twice the CPU it has. `ROLE_ADMIN`, enforced by
 * `SecurityConfig`: a node's load says how many other tenants are on it and how busy they
 * are.
 */
type AdminNodeMetricsProps = {
  nodeId: string
  series: MetricSeries
  window: MetricWindow
  liveWindowSeconds: number
}

export default function AdminNodeMetricsPage() {
  const {nodeId, series, window: span, liveWindowSeconds} =
    usePage<AdminNodeMetricsProps>().props

  return (
    <div className="flex flex-col gap-4">
      <Head title="Node metrics" />

      <PageHeader
        title="Node metrics"
        description="The machine's own totals, not the sum of the workloads it is carrying."
        actions={
          <ButtonLink
            href={`/admin/nodes/${nodeId}`}
            variant="secondary"
            icon={<Icon name="node" />}
          >
            Back to the node
          </ButtonLink>
        }
      />

      <MetricDashboard
        basePath={`/admin/metrics/nodes/${nodeId}`}
        initialSeries={series}
        liveWindowSeconds={liveWindowSeconds}
      />

      <p className="px-1 text-xs text-ink-500 dark:text-ink-400">
        Node {nodeId}. The window this page opened on ran from{' '}
        {new Date(span.from).toLocaleString()} to {new Date(span.to).toLocaleString()}; raw
        samples are kept for two days and hourly and daily buckets for a year.
      </p>
    </div>
  )
}
