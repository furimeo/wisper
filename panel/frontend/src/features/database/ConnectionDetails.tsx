import {useState} from 'react'

import {Button, CopyButton, Icon} from '@/shell'

import type {ConnectionString} from './databaseTypes'
import {connectionUri, jdbcUrl, redactedUri} from './databaseVocabulary'

/**
 * The credentials, on the one render they exist.
 *
 * `connection` is a flash prop written by `POST /databases/{id}/reveal` and
 * `.../password`. It is not on the page otherwise and there is no endpoint that returns
 * it, which is the point: a credential that is on the page anyway is a credential in a
 * browser cache, in a screenshot, and in whatever synced the clipboard.
 *
 * The password starts hidden even here. Somebody reading a connection string on a phone is
 * usually doing it in front of other people, and the copy button does not need the value
 * to be visible to work.
 *
 * Three forms because three things consume them: a URI for most drivers, a JDBC URL for
 * anything on the JVM, and the fields separately for a framework's config file. Building
 * them here rather than reading them off the record is not duplication - `uri()`,
 * `jdbcUrl()` and `redactedUri()` are not bean-named accessors and Jackson leaves them in
 * Java.
 */
export function ConnectionDetails({connection}: {connection: ConnectionString}) {
  const [shown, setShown] = useState(false)
  const uri = connectionUri(connection)

  return (
    <section
      aria-labelledby="connection-heading"
      className="rounded-xl border border-degraded/50 bg-degraded/10 p-4 md:p-5"
    >
      <div className="flex items-start gap-3">
        <span className="mt-0.5 shrink-0 text-degraded">
          <Icon name="key" label="Credentials" />
        </span>
        <div className="min-w-0 flex-1">
          <h2 id="connection-heading" className="text-base font-semibold">
            Connection details
          </h2>
          <p className="mt-1.5 text-sm leading-relaxed text-ink-700 dark:text-ink-300">
            Shown on this screen only. Reloading the page loses them, and the panel keeps the
            password encrypted - ask again and it is a fresh audit entry, not a fresh
            password.
          </p>

          <div className="mt-3 flex flex-col gap-3">
            <Line label="Connection URI" value={uri} masked={!shown} shownValue={redactedUri(connection)} />
            <Line label="JDBC URL" value={jdbcUrl(connection)} />

            <dl className="grid grid-cols-2 gap-x-4 gap-y-2 text-sm">
              <Fact label="Host" value={connection.host} />
              <Fact label="Port" value={String(connection.port)} />
              <Fact label="Database" value={connection.database} />
              <Fact label="User" value={connection.username} />
              {connection.charset ? <Fact label="Encoding" value={connection.charset} /> : null}
            </dl>

            <Line
              label="Password"
              value={connection.password}
              masked={!shown}
              shownValue={'•'.repeat(Math.min(connection.password.length, 24))}
            />

            <div className="flex flex-col gap-2 sm:flex-row">
              <Button
                variant="secondary"
                block
                className="sm:w-auto"
                onClick={() => setShown((current) => !current)}
              >
                {shown ? 'Hide the password' : 'Show the password'}
              </Button>
              <CopyButton
                value={uri}
                label="Copy the URI"
                describedAs="Copy the full connection URI including the password"
                className="w-full sm:w-auto"
              />
            </div>
          </div>

          <p className="mt-3 text-xs leading-relaxed text-ink-600 dark:text-ink-400">
            Put it in your service&apos;s secrets, not in a repository. A secret set on a
            service is not shown again either.
          </p>
        </div>
      </div>
    </section>
  )
}

/**
 * One copyable line.
 *
 * The copy button always carries the real value even while the field is masked, because
 * the reason to hide it is a person standing behind the customer, not the clipboard.
 */
function Line({
  label,
  value,
  masked,
  shownValue,
}: {
  label: string
  value: string
  masked?: boolean
  shownValue?: string
}) {
  return (
    <div>
      <p className="text-xs font-medium uppercase tracking-wide text-ink-500 dark:text-ink-400">
        {label}
      </p>
      <div className="mt-1 flex items-start gap-2">
        <p className="min-w-0 flex-1 rounded-lg bg-white px-3 py-2 font-mono text-xs break-all select-all dark:bg-ink-950">
          {masked ? (shownValue ?? value) : value}
        </p>
        <CopyButton value={value} size="sm" describedAs={`Copy the ${label.toLowerCase()}`} />
      </div>
    </div>
  )
}

function Fact({label, value}: {label: string; value: string}) {
  return (
    <div className="min-w-0">
      <dt className="text-xs text-ink-500 dark:text-ink-400">{label}</dt>
      <dd className="truncate font-mono text-sm select-all">{value}</dd>
    </div>
  )
}
