import {t} from '@/i18n'
import type {BadgeTone} from '@/shell'

import type {
  BuildPreset,
  ConcurrencyPolicy,
  DesiredState,
  ReportedWorkloadState,
  RestartPolicy,
  RuntimeIsolation,
  ServiceKind,
  ServiceSummary,
  WorkloadHealth,
} from './serviceTypes'

/** What the form fills the output directory in with. Mirrors `BuildPreset.defaultOutputDir()`. */
const PRESET_OUTPUT_DIRS: Record<BuildPreset, string> = {
  STATIC: '.',
  NODE: 'dist',
  HUGO: 'public',
  ASTRO: 'dist',
  JEKYLL: '_site',
  CUSTOM: '',
}

const REPORTED_TONES: Record<ReportedWorkloadState, BadgeTone> = {
  PENDING: 'neutral',
  CREATING: 'accent',
  RUNNING: 'running',
  STOPPED: 'neutral',
  CRASHED: 'failed',
  DEGRADED: 'degraded',
  UNKNOWN: 'neutral',
}

/** "App" or "Static site". */
export function kindLabel(kind: ServiceKind): string {
  return t(`service.kinds.${kind}`)
}

/** The sentence under each choice on the new-service form. */
export function kindDescription(kind: ServiceKind): string {
  return t(`service.kind_descriptions.${kind}`)
}

export function presetLabel(preset: BuildPreset): string {
  return t(`service.presets.${preset}`)
}

/** The directory the preset usually leaves the site in; empty for `CUSTOM`. */
export function presetOutputDir(preset: BuildPreset): string {
  return PRESET_OUTPUT_DIRS[preset]
}

/** Whether the customer has to write the build command themselves. */
export function presetNeedsCommand(preset: BuildPreset): boolean {
  return preset === 'CUSTOM'
}

export function restartPolicyLabel(policy: RestartPolicy): string {
  return t(`service.restart_policies.${policy}`)
}

export function restartPolicyHint(policy: RestartPolicy): string {
  return t(`service.restart_hints.${policy}`)
}

export function isolationLabel(isolation: RuntimeIsolation): string {
  return t(`service.isolations.${isolation}`)
}

export function isolationHint(isolation: RuntimeIsolation): string {
  return t(`service.isolation_hints.${isolation}`)
}

/** Whether choosing this obliges the customer to say why. Mirrors `needsReason()`. */
export function isolationNeedsReason(isolation: RuntimeIsolation): boolean {
  return isolation === 'RUNC'
}

/** What the panel warns next to a service running without gVisor. */
export function runcWarning(): string {
  return t('service.runc_warning')
}
export const RUNC_WARNING = runcWarning()

export function concurrencyLabel(policy: ConcurrencyPolicy): string {
  return t(`service.concurrency.${policy}`)
}

export function concurrencyHint(policy: ConcurrencyPolicy): string {
  return t(`service.concurrency_hints.${policy}`)
}

export function reportedStateLabel(state: ReportedWorkloadState): string {
  return t(`service.reported_states.${state}`)
}

export function reportedStateTone(state: ReportedWorkloadState): BadgeTone {
  return REPORTED_TONES[state] ?? 'neutral'
}

export function healthLabel(health: WorkloadHealth): string {
  return t(`service.health.${health}`)
}

/** "Running" or "Stopped", as an intent rather than a fact. */
export function desiredStateLabel(state: DesiredState): string {
  return t(`service.desired_states.${state}`)
}

/**
 * One sentence saying where a service actually is, intent and fact together.
 */
export function statusSentence(status: ServiceSummary | null): string {
  if (!status) {
    return t('service.status_sentences.no_node')
  }
  if (status.archived) {
    return t('service.status_sentences.archived')
  }
  if (status.reportedState === null) {
    return status.wantedRunning
      ? t('service.status_sentences.waiting_node')
      : t('service.status_sentences.stopped_no_report')
  }
  if (status.drifting) {
    return t('service.status_sentences.drifting', {
      state: reportedStateLabel(status.reportedState).toLowerCase(),
    })
  }
  if (status.wantedRunning) {
    return t('service.status_sentences.running_as_asked')
  }
  return t('service.status_sentences.stopped_as_asked')
}
