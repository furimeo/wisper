import {useI18n} from '@/i18n'
import {Badge, Card, EmptyState, Icon, RelativeTime, cx} from '@/shell'

import type {DoctorCheck, DoctorMachine, NodeDoctorReport} from './nodeTypes'
import {outcomeLabel, outcomeTone, severityLabel} from './nodeVocabulary'

/**
 * `sasayaki doctor`, in plain language.
 *
 * The report is what the installer ran before it wrote anything and what the node re-sends
 * on every reconnect. It is rendered failures first, then warnings, then passes, because
 * an operator opening this page has a machine that is behaving oddly and the ordering is
 * the answer.
 *
 * Two findings are lifted out of the list entirely and given their own red panel above it:
 * a missing `runsc` and a volume filesystem without project quota. Both are *advisory*
 * checks - the node runs either way, which is deliberate (design §7.2) - and both mean the
 * platform is not keeping a promise it makes elsewhere in this panel. Left in a list of
 * fourteen green ticks they read as fine, and that is precisely the failure this project
 * was rebuilt to stop shipping.
 *
 * Every check carries its remedy. A check that says no without saying what to type next is
 * a support ticket rather than a check.
 */
const ORDER: Record<string, number> = {FAIL: 0, WARN: 1, UNSPECIFIED: 2, PASS: 3}

export function NodeDoctorReportPanel({report}: {report: NodeDoctorReport | null}) {
  const {t} = useI18n()

  if (report === null) {
    return (
      <Card title={t('node.doctor.title')}>
        <EmptyState
          icon={<Icon name="monitor" />}
          title={t('node.doctor.noReport.title')}
          description={t('node.doctor.noReport.description')}
        />
      </Card>
    )
  }

  const checks = [...report.checks].sort(
    (left, right) => (ORDER[left.outcome] ?? 9) - (ORDER[right.outcome] ?? 9),
  )
  const failures = checks.filter((check) => check.outcome === 'FAIL').length
  const warnings = checks.filter((check) => check.outcome === 'WARN').length

  return (
    <div className="flex flex-col gap-4">
      <MachineWeaknesses machine={report.machine} />

      <Card
        title={t('node.doctor.title')}
        description={
          <>
            Taken <RelativeTime at={report.takenAt} fallback={t('node.doctor.unknownTime')} /> by sasayaki{' '}
            {report.agentVersion ?? t('node.doctor.unrecordedVersion')}.
          </>
        }
        action={
          <Badge tone={report.requiredChecksPassed ? 'running' : 'failed'} dot>
            {report.requiredChecksPassed ? t('node.doctor.requiredPassed') : t('node.doctor.requiredFailed')}
          </Badge>
        }
        padded={false}
      >
        {report.requiredChecksPassed ? null : (
          <p className="border-b border-ink-200 bg-failed/10 px-4 py-3 text-sm leading-relaxed text-ink-800 md:px-5 dark:border-ink-800 dark:text-ink-200">
            {t('node.doctor.requiredFailedNotice')}
          </p>
        )}

        <p className="border-b border-ink-200 px-4 py-2.5 text-xs text-ink-500 md:px-5 dark:border-ink-800 dark:text-ink-400">
          {t('node.doctor.summaryCounts', {
            checks: checks.length,
            failures,
            warnings,
            suffix: warnings === 1 ? '' : 's',
          })}
        </p>

        {checks.length === 0 ? (
          <EmptyState
            title={t('node.doctor.emptyChecks.title')}
            description={t('node.doctor.emptyChecks.description')}
          />
        ) : (
          <ul className="divide-y divide-ink-200 dark:divide-ink-800">
            {checks.map((check) => (
              <CheckRow key={check.id} check={check} />
            ))}
          </ul>
        )}
      </Card>

      {report.machine ? <MachineFacts machine={report.machine} /> : null}
    </div>
  )
}

/** One check: what it looked at, what it found, and what to do about it. */
function CheckRow({check}: {check: DoctorCheck}) {
  const {t} = useI18n()
  const bad = check.outcome === 'FAIL' || check.outcome === 'WARN'
  return (
    <li
      className={cx(
        'flex flex-col gap-1 px-4 py-3 md:px-5',
        check.outcome === 'FAIL' ? 'bg-failed/5' : '',
      )}
    >
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="text-sm font-medium text-ink-900 dark:text-ink-100">{check.title}</p>
          <p className="font-mono text-xs text-ink-500 dark:text-ink-400">
            {check.id} · {severityLabel(check.severity).toLowerCase()}
          </p>
        </div>
        <Badge tone={outcomeTone(check.outcome)} dot={bad} className="shrink-0">
          {outcomeLabel(check.outcome)}
        </Badge>
      </div>

      {check.detail ? (
        <p className="text-sm leading-relaxed text-ink-700 dark:text-ink-300">{check.detail}</p>
      ) : null}

      {bad && check.remedy ? (
        <p className="rounded-lg bg-ink-100 px-3 py-2 text-sm leading-relaxed text-ink-800 dark:bg-ink-950 dark:text-ink-200">
          <span className="font-medium">{t('node.doctor.toFix')}</span>
          {check.remedy}
        </p>
      ) : null}
    </li>
  )
}

/**
 * The two facts that must be visible without expanding anything.
 *
 * They come from the machine block rather than the check list because that is the field
 * the panel itself acts on - `NodeSummary.lessIsolated` and `quotaAdvisory` are derived
 * from the same two booleans - and a check that a future build renames would not change
 * what is true.
 */
function MachineWeaknesses({machine}: {machine: DoctorMachine | null}) {
  const {t} = useI18n()
  if (machine === null) {
    return null
  }
  const weak: Array<{title: string; body: string; remedy: string}> = []

  if (!machine.runscAvailable) {
    weak.push({
      title: t('node.doctor.weaknesses.gvisor.title'),
      body: t('node.doctor.weaknesses.gvisor.body'),
      remedy: t('node.doctor.weaknesses.gvisor.remedy'),
    })
  }
  if (!machine.projectQuotaSupported) {
    weak.push({
      title: t('node.doctor.weaknesses.quota.title', {fs: machine.stateFilesystem || 'this filesystem'}),
      body: t('node.doctor.weaknesses.quota.body'),
      remedy: t('node.doctor.weaknesses.quota.remedy'),
    })
  }
  if (!machine.cgroupsV2) {
    weak.push({
      title: t('node.doctor.weaknesses.cgroups.title'),
      body: t('node.doctor.weaknesses.cgroups.body'),
      remedy: t('node.doctor.weaknesses.cgroups.remedy'),
    })
  }
  if (!machine.clockSynchronised) {
    weak.push({
      title: t('node.doctor.weaknesses.clock.title'),
      body: t('node.doctor.weaknesses.clock.body', {seconds: Math.round(machine.clockOffsetMillis / 1000)}),
      remedy: t('node.doctor.weaknesses.clock.remedy'),
    })
  }

  if (weak.length === 0) {
    return null
  }

  return (
    <section
      aria-labelledby="machine-weaknesses"
      className="rounded-xl border border-failed/50 bg-failed/10 p-4 md:p-5"
    >
      <div className="flex items-start gap-3">
        <span className="mt-0.5 shrink-0 text-failed">
          <Icon name="shield" label="Warning" />
        </span>
        <div className="min-w-0">
          <h2 id="machine-weaknesses" className="text-base font-semibold">
            {t('node.doctor.weaknesses.title')}
          </h2>
          <ul className="mt-2 flex flex-col gap-3">
            {weak.map((item) => (
              <li key={item.title} className="text-sm leading-relaxed">
                <strong className="font-semibold">{item.title}.</strong>{' '}
                <span className="text-ink-800 dark:text-ink-200">{item.body}</span>
                <span className="mt-1 block text-ink-600 dark:text-ink-400">{item.remedy}</span>
              </li>
            ))}
          </ul>
        </div>
      </div>
    </section>
  )
}

/** What the machine is. Useful, unalarming, and therefore at the bottom. */
function MachineFacts({machine}: {machine: DoctorMachine}) {
  const {t} = useI18n()
  const facts: Array<[string, string]> = [
    ['Hostname', machine.hostname || '-'],
    ['Operating system', machine.operatingSystem || '-'],
    ['Kernel', machine.kernelVersion || '-'],
    ['Architecture', machine.architecture || '-'],
    ['CPU cores', String(machine.cpuCores)],
    ['Docker', `${machine.dockerVersion || '-'} (API ${machine.dockerApiVersion || '-'})`],
    ['gVisor', machine.runscAvailable ? machine.runscVersion || 'present' : 'not installed'],
    ['Volume filesystem', machine.stateFilesystem || '-'],
    ['Port 80', machine.port80Free ? 'free' : 'in use by something else'],
    ['Port 443', machine.port443Free ? 'free' : 'in use by something else'],
    [
      'Advertised addresses',
      machine.advertiseAddresses.length === 0 ? '-' : machine.advertiseAddresses.join(', '),
    ],
  ]

  return (
    <Card title={t('node.doctor.machineFacts.title')} description={t('node.doctor.machineFacts.description')}>
      <dl className="grid grid-cols-1 gap-x-6 sm:grid-cols-2">
        {facts.map(([label, value]) => (
          <div key={label} className="flex flex-col gap-0.5 py-2">
            <dt className="text-xs font-medium uppercase tracking-wide text-ink-500 dark:text-ink-400">
              {label}
            </dt>
            <dd className="text-sm break-words text-ink-900 dark:text-ink-100">{value}</dd>
          </div>
        ))}
      </dl>
    </Card>
  )
}
