import {Head, usePage} from '@inertiajs/react'

import {t} from '@/i18n'
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
      <Head title={t('stats.serviceMetrics.title', {name: service.name})} />
      <ServiceTabs serviceId={service.serviceId} />

      <PageHeader
        title={t('stats.serviceMetrics.header')}
        description={
          service.placed
            ? t('stats.serviceMetrics.descPlaced')
            : t('stats.serviceMetrics.descUnplaced')
        }
      />

      {series.empty && !service.placed ? (
        <Card title={t('stats.serviceMetrics.emptyTitle')}>
          <p className="text-sm text-ink-700 dark:text-ink-300">
            {t('stats.serviceMetrics.emptyDesc')}
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
        {t('stats.serviceMetrics.footer', {
          from: new Date(span.from).toLocaleString(),
          to: new Date(span.to).toLocaleString(),
        })}
      </p>
    </div>
  )
}
