import {Link, usePage} from '@inertiajs/react'
import {t} from '@/i18n'

/**
 * Rendered by ErrorPageController for every failure that reaches /error - a bad URL, a
 * denied resource, an upload over the limit, a bug.
 *
 * It is an ordinary page on purpose. A failure is the moment a customer most needs to
 * recognise where they are and how to get out, which a white frame with a stack trace
 * does not give them.
 */
type ErrorProps = {
  status: number
  reason: string
  message: string
  path: string
}

export default function ErrorPage() {
  const {status, reason, message, path} = usePage<ErrorProps>().props

  return (
    <main className="flex min-h-dvh flex-col items-center justify-center gap-6 p-6 text-center">
      <div>
        <p className="font-mono text-5xl font-semibold tabular-nums text-ink-300 dark:text-ink-700">
          {status}
        </p>
        <h1 className="mt-2 text-lg font-semibold">{reason}</h1>
      </div>

      <p className="max-w-sm text-sm leading-relaxed text-ink-600 dark:text-ink-400">{message}</p>

      {path ? (
        <p className="max-w-full truncate font-mono text-xs text-ink-500" title={path}>
          {path}
        </p>
      ) : null}

      <Link
        href="/"
        className="inline-flex touch-target items-center rounded-lg bg-accent-600 px-5 font-medium text-white"
      >
        {t('error.page.backToDashboard')}
      </Link>
    </main>
  )
}
