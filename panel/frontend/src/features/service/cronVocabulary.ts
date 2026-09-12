import {t} from '@/i18n'

import type {CronTaskView} from './serviceTypes'

const DAY_KEYS = [
  'service.cron.days.sunday',
  'service.cron.days.monday',
  'service.cron.days.tuesday',
  'service.cron.days.wednesday',
  'service.cron.days.thursday',
  'service.cron.days.friday',
  'service.cron.days.saturday',
]

/** The schedules that cover most of what a customer schedules, in the order they pick. */
export const SCHEDULE_PRESETS: ReadonlyArray<{expression: string; readonly label: string}> = [
  {expression: '*/5 * * * *', get label() { return t('service.cron.presets.every_5_minutes') }},
  {expression: '*/15 * * * *', get label() { return t('service.cron.presets.every_15_minutes') }},
  {expression: '0 * * * *', get label() { return t('service.cron.presets.hourly') }},
  {expression: '0 3 * * *', get label() { return t('service.cron.presets.daily_0300') }},
  {expression: '30 2 * * 1', get label() { return t('service.cron.presets.mondays_0230') }},
  {expression: '0 4 1 * *', get label() { return t('service.cron.presets.monthly_first_0400') }},
]

/**
 * The expression as a sentence, or the expression itself when it is not one of the shapes
 * this can state exactly.
 */
export function describeSchedule(expression: string): string {
  const fields = expression.trim().split(/\s+/)
  if (fields.length !== 5) {
    return expression
  }
  const [minute, hour, dayOfMonth, month, dayOfWeek] = fields as [
    string,
    string,
    string,
    string,
    string,
  ]

  if (month !== '*') {
    return expression
  }
  const when = timeOfDay(minute, hour)
  if (when === null) {
    return expression
  }

  if (dayOfMonth === '*' && dayOfWeek === '*') {
    return when
  }
  if (dayOfMonth === '*' && /^[0-7]$/.test(dayOfWeek)) {
    const dayKey = DAY_KEYS[dayOfWeek === '7' ? 0 : Number(dayOfWeek)]
    return dayKey ? t('service.cron.on_day_of_week', {when, day: t(dayKey)}) : expression
  }
  if (dayOfWeek === '*' && /^\d{1,2}$/.test(dayOfMonth)) {
    return t('service.cron.on_day_of_month', {when, day: Number(dayOfMonth)})
  }
  return expression
}

/**
 * How the last completed run went, in one line.
 */
export function lastRunSentence(task: CronTaskView): string {
  if (task.running) {
    return t('service.cron.last_run.running')
  }
  if (task.lastFinishedAt === null && task.lastRunAt === null) {
    return t('service.cron.last_run.not_run_yet')
  }
  if (task.lastExitCode === null) {
    return t('service.cron.last_run.unreported_end')
  }
  const took = task.lastDurationMs === null ? '' : t('service.cron.last_run.in_duration', {duration: formatDuration(task.lastDurationMs)})
  if (task.lastExitCode === 0) {
    return t('service.cron.last_run.succeeded', {took})
  }
  return t('service.cron.last_run.exited', {code: task.lastExitCode, took})
}

/** Whether the last completed run ended badly, which is what the screen colours red. */
export function lastRunFailed(task: CronTaskView): boolean {
  return task.lastExitCode !== null && task.lastExitCode !== 0
}

/** `1500` -> `1.5s`, `65000` -> `1m 5s`. */
export function formatDuration(millis: number): string {
  if (millis < 1000) {
    return `${millis}ms`
  }
  const seconds = millis / 1000
  if (seconds < 60) {
    return `${seconds < 10 ? seconds.toFixed(1) : Math.round(seconds)}s`
  }
  const minutes = Math.floor(seconds / 60)
  const rest = Math.round(seconds - minutes * 60)
  return rest === 0 ? `${minutes}m` : `${minutes}m ${rest}s`
}

/** The timeout picker's options, in the units a person thinks about a job in. */
export const TIMEOUT_CHOICES: ReadonlyArray<{seconds: number; readonly label: string}> = [
  {seconds: 60, get label() { return t('service.cron.timeouts.one_minute') }},
  {seconds: 300, get label() { return t('service.cron.timeouts.five_minutes') }},
  {seconds: 900, get label() { return t('service.cron.timeouts.fifteen_minutes') }},
  {seconds: 3600, get label() { return t('service.cron.timeouts.one_hour') }},
  {seconds: 21600, get label() { return t('service.cron.timeouts.six_hours') }},
  {seconds: 86400, get label() { return t('service.cron.timeouts.twenty_four_hours') }},
]

/** "5 minutes" for a value in the list above, "450 seconds" for anything else. */
export function timeoutLabel(seconds: number): string {
  const known = TIMEOUT_CHOICES.find((choice) => choice.seconds === seconds)
  return known ? known.label : t('service.cron.timeouts.custom_seconds', {seconds})
}

/**
 * The minute-and-hour half of an expression, or null when it is a shape this does not
 * state exactly.
 */
function timeOfDay(minute: string, hour: string): string | null {
  const everyMinutes = step(minute)
  if (everyMinutes !== null && hour === '*') {
    return t('service.cron.every_minutes', {step: everyMinutes})
  }
  if (minute === '*' && hour === '*') {
    return t('service.cron.every_minute')
  }
  if (!/^\d{1,2}$/.test(minute)) {
    return null
  }
  if (hour === '*') {
    return t('service.cron.every_hour_at', {minute: pad(Number(minute))})
  }
  const everyHours = step(hour)
  if (everyHours !== null) {
    return t('service.cron.every_hours_at', {step: everyHours, minute: pad(Number(minute))})
  }
  if (!/^\d{1,2}$/.test(hour)) {
    return null
  }
  return t('service.cron.at_time', {hour: pad(Number(hour)), minute: pad(Number(minute))})
}

/** A star-slash-step field such as every fifth minute yields 5; anything else, null. */
function step(field: string): number | null {
  const match = /^\*\/(\d{1,2})$/.exec(field)
  return match && match[1] ? Number(match[1]) : null
}

function pad(value: number): string {
  return value.toString().padStart(2, '0')
}
