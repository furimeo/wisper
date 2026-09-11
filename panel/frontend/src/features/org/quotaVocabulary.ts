import {formatBytes} from '@/shell'

import type {QuotaResource, QuotaSource} from './orgTypes'

/**
 * What each limit is called, and how its number is written.
 *
 * `QuotaResource.name()` is a constant like `VOLUME_BYTES`, which is precise and reads
 * like a database column. The panel is looked at by customers, so every screen that shows
 * a quota - the organization overview, the volume list, the new-service form, the
 * operator's tenant and plan screens - needs the same three answers: what to call it,
 * whether the number is a count or a size, and what "5" means next to it. Deriving those
 * from the enum in four places is how three of them end up saying "512 bytes" for half a
 * core.
 */

/** The unit a limit is counted in, which decides how it is written and typed in. */
export type QuotaUnit = 'count' | 'bytes' | 'millicores'

interface Vocabulary {
  label: string
  unit: QuotaUnit
  /** The singular noun for a count, so "1 project" is not "1 projects". */
  noun?: string
  /** What being at the ceiling stops the customer doing. Shown when they are near it. */
  consequence: string
}

const VOCABULARY: Record<QuotaResource, Vocabulary> = {
  PROJECT: {
    label: 'Projects',
    unit: 'count',
    noun: 'project',
    consequence: 'You cannot open another project.',
  },
  SERVICE: {
    label: 'Services',
    unit: 'count',
    noun: 'service',
    consequence: 'You cannot add another service.',
  },
  DOMAIN: {
    label: 'Domains',
    unit: 'count',
    noun: 'domain',
    consequence: 'You cannot point another hostname at a service.',
  },
  MANAGED_DATABASE: {
    label: 'Databases',
    unit: 'count',
    noun: 'database',
    consequence: 'You cannot create another database.',
  },
  CRON_TASK: {
    label: 'Scheduled tasks',
    unit: 'count',
    noun: 'task',
    consequence: 'You cannot schedule another command.',
  },
  MEMBER: {
    label: 'Members',
    unit: 'count',
    noun: 'member',
    consequence: 'You cannot invite anybody else.',
  },
  API_TOKEN: {
    label: 'API tokens',
    unit: 'count',
    noun: 'token',
    consequence: 'You cannot issue another token.',
  },
  VOLUME_BYTES: {
    label: 'Disk',
    unit: 'bytes',
    consequence: 'You cannot attach or grow a volume.',
  },
  MEMORY_BYTES: {
    label: 'Memory',
    unit: 'bytes',
    consequence: 'You cannot give a service more memory.',
  },
  CPU_MILLICORES: {
    label: 'CPU',
    unit: 'millicores',
    consequence: 'You cannot give a service more CPU.',
  },
  BACKUP_BYTES: {
    label: 'Backup storage',
    unit: 'bytes',
    consequence: 'The next snapshot will be refused.',
  },
  RESTORE_POINT: {
    label: 'Snapshots',
    unit: 'count',
    noun: 'snapshot',
    consequence: 'The oldest snapshot has to go before another is taken.',
  },
  DEPLOYMENTS_PER_DAY: {
    label: 'Deployments a day',
    unit: 'count',
    noun: 'deployment',
    consequence: 'Deploys are paused until the rolling day moves on.',
  },
}

/** The customer-facing name of a limit. */
export function quotaLabel(resource: QuotaResource): string {
  return VOCABULARY[resource].label
}

/** Whether the figure is a size, a share of a core, or a plain count. */
export function quotaUnit(resource: QuotaResource): QuotaUnit {
  return VOCABULARY[resource].unit
}

/** What hitting this ceiling stops the customer doing. */
export function quotaConsequence(resource: QuotaResource): string {
  return VOCABULARY[resource].consequence
}

/**
 * One figure, written in the unit the resource is measured in.
 *
 * Millicores become cores because "500 millicores" is a number nobody's plan is written
 * in, and 1000 of them is one core everywhere else in the panel.
 */
export function quotaFigure(resource: QuotaResource, value: number): string {
  switch (VOCABULARY[resource].unit) {
    case 'bytes':
      return formatBytes(value)
    case 'millicores':
      return formatCores(value)
    default:
      return value.toLocaleString()
  }
}

/** `750` -> `0.75 cores`, `1000` -> `1 core`. */
export function formatCores(millicores: number): string {
  const cores = millicores / 1000
  const written = Number.isInteger(cores) ? cores.toString() : cores.toFixed(2).replace(/0$/, '')
  return `${written} ${cores === 1 ? 'core' : 'cores'}`
}

/** "3 of 10 projects", the sentence under a quota bar. */
export function quotaSentence(resource: QuotaResource, used: number, limit: number): string {
  const noun = VOCABULARY[resource].noun
  const figures = `${quotaFigure(resource, used)} of ${quotaFigure(resource, limit)}`
  return noun ? `${figures} ${limit === 1 ? noun : `${noun}s`}` : figures
}

/** Where the number in force came from, said in words. */
export function quotaSourceLabel(source: QuotaSource): string {
  switch (source) {
    case 'ORGANIZATION_OVERRIDE':
      return 'Exception granted for this organization'
    case 'PLAN':
      return 'From the plan'
    default:
      return 'Not set on the plan'
  }
}

/**
 * The share of a limit that is used, capped at 1 for the bar and reported honestly above
 * it. A limit of zero is fully used by definition - nothing is allowed at all.
 */
export function quotaShare(used: number, limit: number): number {
  if (limit <= 0) {
    return 1
  }
  return Math.min(1, used / limit)
}

/**
 * The threshold at which a quota stops being background information.
 *
 * Ninety per cent, because that is roughly one more of whatever it is: at 9 of 10
 * services the next one fails, and a customer told at 9 can plan, while one told at 10 is
 * already stuck.
 */
export const QUOTA_WARNING_SHARE = 0.9
