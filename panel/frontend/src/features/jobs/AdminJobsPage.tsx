import {Head, usePage} from '@inertiajs/react'

import {PageHeader, Pagination} from '@/shell'

import {FailedJobList} from './FailedJobList'
import {JobQueueHealth} from './JobQueueHealth'
import type {FailedJobPage, JobQueueSummary} from './jobTypes'

/**
 * `GET /admin/jobs` - the work the panel owes itself.
 *
 * <p>Almost everything the panel promises a customer is finished by a job: a deployment is
 * queued, a backup is scheduled, a spec is republished, a stats rollup is swept. When the
 * queue stops, none of that reports an error - it simply never happens, and the first
 * symptom is a customer asking why their deploy has said "queued" for an hour. This screen
 * exists so that question has one place to be answered.
 *
 * <p>Read the queue card first and the list second. The card says whether work is moving at
 * all; the list says which individual jobs have given up. A healthy panel usually shows a
 * busy card and an empty list.
 */
type AdminJobsProps = {
  summary: JobQueueSummary
  page: FailedJobPage
}

export default function AdminJobsPage() {
  const {summary, page} = usePage<AdminJobsProps>().props

  return (
    <div className="flex flex-col gap-4">
      <Head title="Jobs" />

      <PageHeader
        title="Jobs"
        description="The panel's own work queue. Deployments, backups, spec publication and
          the nightly sweeps all run through it, so a stalled queue looks like a dozen
          unrelated things being broken."
      />

      <JobQueueHealth summary={summary} />

      <FailedJobList jobs={page.jobs} />

      <Pagination
        total={page.total}
        offset={page.offset}
        pageSize={page.pageSize}
        unit="failing jobs"
        hrefFor={(offset) => (offset === 0 ? '/admin/jobs' : `/admin/jobs?offset=${offset}`)}
      />
    </div>
  )
}
