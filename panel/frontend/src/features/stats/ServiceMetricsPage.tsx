import {Head, usePage} from '@inertiajs/react'

import {Card, PageHeader} from '@/shell'

import {ServiceTabs} from '@/features/service/ServiceTabs'
import type {ServiceLocation} from '@/features/service/serviceTypes'

import {MetricDashboard} from './MetricDashboard'
import type {MetricSeries, MetricWindow} from './statsTypes'

/**
 * `GET /services/{serviceId}/metrics` - what one workload is doing to its machine.
 *
 * The first window arrives in the props already drawn, which is the difference between a
 * page and a page that flashes: a chart that mounts empty and then fetches is a grey box
 * for as long as mobile data takes, and the customer has usually decided the panel is
 * broken by then.
 *
 * Readable by any member including a viewer. Looking at a graph changes nothing, and a
 * read-only role that cannot see whether the thing it is watching is healthy is not a
 * useful role - which is the same judgement `ServiceMetricsController` makes.
 */
type ServiceMetricsProps = {
  service: ServiceLocation
  series: MetricSeries
  window: MetricWindow
  liveWindowSeconds: number
}

export default function ServiceMetricsPage() {
  const {service, series, window: span, liveWindowSeconds} =
    usePage<ServiceMetricsProps>().props

  return (
    <div className="flex flex-col gap-4">
      <Head title={`Metrics · ${service.name}`} />
      <ServiceTabs serviceId={service.serviceId} />

      <PageHeader
        title="Metrics"
        description={
          service.placed
            ? 'Pushed by the node holding this service, every fifteen seconds while it runs.'
            : 'Nothing is running this service, so nothing is being measured right now. Anything already recorded is still here.'
        }
      />

      {series.empty && !service.placed ? (
        <Card title="No readings yet">
          <p className="text-sm text-ink-700 dark:text-ink-300">
            A node measures a workload while it is running and pushes the readings to the
            panel. This service has not been placed on one yet, so there is nothing to draw -
            start it and the first points arrive within a few seconds.
          </p>
        </Card>
      ) : (
        <MetricDashboard
          basePath={`/services/${service.serviceId}/metrics`}
          initialSeries={series}
          liveWindowSeconds={liveWindowSeconds}
        />
      )}

      <p className="px-1 text-xs text-ink-500 dark:text-ink-400">
        Raw samples are kept for two days and rolled into hourly and daily buckets after
        that, so a long window is drawn from averages. The window this page opened on ran
        from {new Date(span.from).toLocaleString()} to {new Date(span.to).toLocaleString()}.
      </p>
    </div>
  )
}
