import type {CronTaskView} from './serviceTypes'

/**
 * Cron expressions, said in words - and the presets most people actually want.
 *
 * `CronSchedule` on the server parses the full Vixie syntax and computes the next due
 * time, which is the part that has to be right. This file does the other half: it reads
 * an expression back to somebody who is about to save it, so a mistyped field is caught
 * before the job runs at the wrong hour for a month.
 *
 * It deliberately describes only the shapes it can describe exactly - a plain number, a
 * step, a star - and hands back the raw expression for anything else. A near-enough
 * English sentence about a schedule is worse than no sentence: it is believed.
 */
const DAY_NAMES = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday']

/** The schedules that cover most of what a customer schedules, in the order they pick. */
export const SCHEDULE_PRESETS: ReadonlyArray<{expression: string; label: string}> = [
  {expression: '*/5 * * * *', label: 'Every 5 minutes'},
  {expression: '*/15 * * * *', label: 'Every 15 minutes'},
  {expression: '0 * * * *', label: 'Hourly, on the hour'},
  {expression: '0 3 * * *', label: 'Daily at 03:00'},
  {expression: '30 2 * * 1', label: 'Mondays at 02:30'},
  {expression: '0 4 1 * *', label: 'The 1st of the month at 04:00'},
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
    const day = DAY_NAMES[dayOfWeek === '7' ? 0 : Number(dayOfWeek)]
    return day ? `${when}, on ${day}s` : expression
  }
  if (dayOfWeek === '*' && /^\d{1,2}$/.test(dayOfMonth)) {
    return `${when}, on day ${Number(dayOfMonth)} of the month`
  }
  return expression
}

/**
 * How the last completed run went, in one line.
 *
 * A cron whose schedule is right and whose command exits 1 every night looks perfectly
 * healthy on a screen that draws only the schedule, which is why this exists and why it
 * leads with the exit code rather than with the timestamp.
 */
export function lastRunSentence(task: CronTaskView): string {
  if (task.running) {
    return 'Running now.'
  }
  if (task.lastFinishedAt === null && task.lastRunAt === null) {
    return 'Has not run yet.'
  }
  if (task.lastExitCode === null) {
    return 'Started, and the node has not reported how it ended.'
  }
  const took = task.lastDurationMs === null ? '' : ` in ${formatDuration(task.lastDurationMs)}`
  if (task.lastExitCode === 0) {
    return `Last run succeeded${took}.`
  }
  return `Last run exited ${task.lastExitCode}${took}.`
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
export const TIMEOUT_CHOICES: ReadonlyArray<{seconds: number; label: string}> = [
  {seconds: 60, label: '1 minute'},
  {seconds: 300, label: '5 minutes'},
  {seconds: 900, label: '15 minutes'},
  {seconds: 3600, label: '1 hour'},
  {seconds: 21600, label: '6 hours'},
  {seconds: 86400, label: '24 hours'},
]

/** "5 minutes" for a value in the list above, "450 seconds" for anything else. */
export function timeoutLabel(seconds: number): string {
  const known = TIMEOUT_CHOICES.find((choice) => choice.seconds === seconds)
  return known ? known.label : `${seconds} seconds`
}

/**
 * The minute-and-hour half of an expression, or null when it is a shape this does not
 * state exactly.
 */
function timeOfDay(minute: string, hour: string): string | null {
  const everyMinutes = step(minute)
  if (everyMinutes !== null && hour === '*') {
    return `Every ${everyMinutes} minutes`
  }
  if (minute === '*' && hour === '*') {
    return 'Every minute'
  }
  if (!/^\d{1,2}$/.test(minute)) {
    return null
  }
  if (hour === '*') {
    return `Every hour at :${pad(Number(minute))}`
  }
  const everyHours = step(hour)
  if (everyHours !== null) {
    return `Every ${everyHours} hours at :${pad(Number(minute))}`
  }
  if (!/^\d{1,2}$/.test(hour)) {
    return null
  }
  return `At ${pad(Number(hour))}:${pad(Number(minute))}`
}

/** A star-slash-step field such as every fifth minute yields 5; anything else, null. */
function step(field: string): number | null {
  const match = /^\*\/(\d{1,2})$/.exec(field)
  return match && match[1] ? Number(match[1]) : null
}

function pad(value: number): string {
  return value.toString().padStart(2, '0')
}
