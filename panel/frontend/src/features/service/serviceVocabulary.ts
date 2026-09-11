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

/**
 * What each enum is called on screen, and what colour a state is.
 *
 * The Java enums have `label()` methods, and Jackson does not serialise them: they are not
 * bean-named accessors, so `BuildPreset.NODE.label()` never reaches the browser. The words
 * therefore have to live somewhere on this side, and this is that somewhere - once, rather
 * than a `switch` in the five screens that show a build preset.
 *
 * The strings match the Java `label()` methods deliberately. A flash message written by
 * the server and a dropdown written here appear in the same sentence often enough that
 * calling the same thing two names is a support ticket.
 */

const KINDS: Record<ServiceKind, string> = {
  APP: 'App',
  SITE: 'Static site',
}

const KIND_DESCRIPTIONS: Record<ServiceKind, string> = {
  APP: 'A container the platform keeps running: a web server, a worker, a bot, a queue consumer.',
  SITE: 'Files built from a repository and served straight off the node. Nothing runs between deploys.',
}

const PRESETS: Record<BuildPreset, string> = {
  STATIC: 'Plain files, no build',
  NODE: 'Node',
  HUGO: 'Hugo',
  ASTRO: 'Astro',
  JEKYLL: 'Jekyll',
  CUSTOM: 'Custom command',
}

/** What the form fills the output directory in with. Mirrors `BuildPreset.defaultOutputDir()`. */
const PRESET_OUTPUT_DIRS: Record<BuildPreset, string> = {
  STATIC: '.',
  NODE: 'dist',
  HUGO: 'public',
  ASTRO: 'dist',
  JEKYLL: '_site',
  CUSTOM: '',
}

const RESTART_POLICIES: Record<RestartPolicy, string> = {
  ALWAYS: 'Always',
  ON_FAILURE: 'On failure',
  NEVER: 'Never',
}

const RESTART_HINTS: Record<RestartPolicy, string> = {
  ALWAYS: 'Bring it back whenever it stops, including after the node reboots.',
  ON_FAILURE: 'Bring it back only when it exits non-zero. A clean exit is left alone.',
  NEVER: 'Leave it stopped. For a one-shot job you start yourself.',
}

const ISOLATIONS: Record<RuntimeIsolation, string> = {
  RUNSC: 'gVisor',
  RUNC: 'runc',
}

const ISOLATION_HINTS: Record<RuntimeIsolation, string> = {
  RUNSC: 'Syscalls are filtered in userspace. The default, and what you want unless something breaks.',
  RUNC: 'Only kernel namespaces separate this from the host. For workloads gVisor cannot run, such as io_uring.',
}

const CONCURRENCY: Record<ConcurrencyPolicy, string> = {
  ALLOW: 'Allow overlap',
  FORBID: 'Skip the new run',
  REPLACE: 'Kill the old run',
}

const CONCURRENCY_HINTS: Record<ConcurrencyPolicy, string> = {
  ALLOW: 'Start it anyway. Two copies can be running at once.',
  FORBID: 'If the last run has not finished, skip this one and say so.',
  REPLACE: 'Stop what is still running, then start the new one.',
}

const REPORTED_STATES: Record<ReportedWorkloadState, string> = {
  PENDING: 'Pending',
  CREATING: 'Creating',
  RUNNING: 'Running',
  STOPPED: 'Stopped',
  CRASHED: 'Crashed',
  DEGRADED: 'Degraded',
  UNKNOWN: 'Unknown',
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

const HEALTH: Record<WorkloadHealth, string> = {
  HEALTHY: 'Healthy',
  UNHEALTHY: 'Unhealthy',
  UNKNOWN: 'No health check',
}

/** "App" or "Static site". */
export function kindLabel(kind: ServiceKind): string {
  return KINDS[kind]
}

/** The sentence under each choice on the new-service form. */
export function kindDescription(kind: ServiceKind): string {
  return KIND_DESCRIPTIONS[kind]
}

export function presetLabel(preset: BuildPreset): string {
  return PRESETS[preset]
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
  return RESTART_POLICIES[policy]
}

export function restartPolicyHint(policy: RestartPolicy): string {
  return RESTART_HINTS[policy]
}

export function isolationLabel(isolation: RuntimeIsolation): string {
  return ISOLATIONS[isolation]
}

export function isolationHint(isolation: RuntimeIsolation): string {
  return ISOLATION_HINTS[isolation]
}

/** Whether choosing this obliges the customer to say why. Mirrors `needsReason()`. */
export function isolationNeedsReason(isolation: RuntimeIsolation): boolean {
  return isolation === 'RUNC'
}

/** What the panel warns next to a service running without gVisor. */
export const RUNC_WARNING =
  'This service runs without gVisor, so only the kernel separates it from the host.'

export function concurrencyLabel(policy: ConcurrencyPolicy): string {
  return CONCURRENCY[policy]
}

export function concurrencyHint(policy: ConcurrencyPolicy): string {
  return CONCURRENCY_HINTS[policy]
}

/*
 * `ServiceSummary.reportedState` and `.health` are `String` on the Java side, not enums -
 * they are whatever the node put in `workload_status`. The unions say what a node is
 * supposed to send; these three fall back rather than render an empty pill if one ever
 * sends something else, because a blank status is the failure mode this project exists to
 * stop shipping.
 */
export function reportedStateLabel(state: ReportedWorkloadState): string {
  return REPORTED_STATES[state] ?? state
}

export function reportedStateTone(state: ReportedWorkloadState): BadgeTone {
  return REPORTED_TONES[state] ?? 'neutral'
}

export function healthLabel(health: WorkloadHealth): string {
  return HEALTH[health] ?? health
}

/** "Running" or "Stopped", as an intent rather than a fact. */
export function desiredStateLabel(state: DesiredState): string {
  return state === 'RUNNING' ? 'Should be running' : 'Should be stopped'
}

/**
 * One sentence saying where a service actually is, intent and fact together.
 *
 * The interesting case is the one a single pill cannot show: the customer asked for it to
 * run and the node says it crashed. So this reads the pair rather than either half, and
 * says the disagreement out loud.
 */
export function statusSentence(status: ServiceSummary | null): string {
  if (!status) {
    return 'No node has reported this service yet.'
  }
  if (status.archived) {
    return 'Archived. Nothing is running and nothing was deleted.'
  }
  if (status.reportedState === null) {
    return status.wantedRunning
      ? 'Waiting for a node to pick it up.'
      : 'Stopped, and no node has reported it.'
  }
  if (status.drifting) {
    return `You asked for this to run and the node reports it ${reportedStateLabel(
      status.reportedState,
    ).toLowerCase()}.`
  }
  if (status.wantedRunning) {
    return 'Running as asked.'
  }
  return 'Stopped as asked. Its volumes and logs are where you left them.'
}
