import {t} from '@/i18n'
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
  unit: QuotaUnit
}

const VOCABULARY: Record<QuotaResource, Vocabulary> = {
  PROJECT: {unit: 'count'},
  SERVICE: {unit: 'count'},
  DOMAIN: {unit: 'count'},
  MANAGED_DATABASE: {unit: 'count'},
  CRON_TASK: {unit: 'count'},
  MEMBER: {unit: 'count'},
  API_TOKEN: {unit: 'count'},
  VOLUME_BYTES: {unit: 'bytes'},
  MEMORY_BYTES: {unit: 'bytes'},
  CPU_MILLICORES: {unit: 'millicores'},
  BACKUP_BYTES: {unit: 'bytes'},
  RESTORE_POINT: {unit: 'count'},
  DEPLOYMENTS_PER_DAY: {unit: 'count'},
}

/** The customer-facing name of a limit. */
export function quotaLabel(resource: QuotaResource): string {
  return t(`org.quota.label.${resource}`) || resource
}

/** Whether the figure is a size, a share of a core, or a plain count. */
export function quotaUnit(resource: QuotaResource): QuotaUnit {
  return VOCABULARY[resource].unit
}

/** What hitting this ceiling stops the customer doing. */
export function quotaConsequence(resource: QuotaResource): string {
  return t(`org.quota.consequence.${resource}`) || ''
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
  const unit = t('org.quota.coresUnit', {count: written})
  return `${written} ${unit.replace('{count}', '').trim() || (cores === 1 ? 'core' : 'cores')}`
}

/** "3 of 10 projects", the sentence under a quota bar. */
export function quotaSentence(resource: QuotaResource, used: number, limit: number): string {
  const noun = t(`org.quota.noun.${resource}`)
  const figures = t('org.quota.of', {
    used: quotaFigure(resource, used),
    limit: quotaFigure(resource, limit),
  })
  if (!noun) {
    return figures
  }
  // Plural suffix in English: if noun ends up as english word and limit !== 1, append s
  const withNoun = noun.startsWith('org.') ? '' : `${figures} ${limit === 1 || noun.includes(' ') ? noun : `${noun}s`}`
  return withNoun || figures
}

/** Where the number in force came from, said in words. */
export function quotaSourceLabel(source: QuotaSource): string {
  return t(`org.quota.source.${source}`) || source
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
