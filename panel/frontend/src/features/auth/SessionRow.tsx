import {Badge, Button, CardFact, CardFacts, RelativeTime} from '@/shell'

import type {SessionView} from './authTypes'

/**
 * One signed-in browser.
 *
 * A card of stacked facts rather than a table row: the five things worth knowing about a
 * session - what it is, where from, when it was last used, when it started, when it
 * expires - do not fit across 375px as columns, and a table that scrolls sideways hides
 * the revoke button off the right edge.
 *
 * Revoking marks the row; it does not reach into the other browser's session, because
 * that browser is somewhere else. `SessionGateFilter` turns the revoked row into an
 * actual sign-out on that browser's next request, which is why the wording is "on its
 * next request" and not "instantly".
 */
type SessionRowProps = {
  session: SessionView
  /** True while a revoke is in flight, so the button cannot be pressed twice. */
  busy: boolean
  onRevoke: () => void
}

export function SessionRow({session, busy, onRevoke}: SessionRowProps) {
  return (
    <li className="rounded-lg border border-ink-200 p-3 dark:border-ink-800">
      <div className="flex flex-wrap items-center gap-2">
        <span className="mr-auto text-sm font-medium">{describeAgent(session.userAgent)}</span>
        {session.current ? (
          <Badge tone="accent" dot>
            This device
          </Badge>
        ) : null}
        {session.secondFactorSatisfied ? null : (
          <Badge tone="degraded" dot>
            Second factor outstanding
          </Badge>
        )}
      </div>

      <CardFacts>
        <CardFact label="Address">
          <span className="font-mono break-all">{session.remoteAddress ?? 'Not recorded'}</span>
        </CardFact>
        <CardFact label="Last seen">
          <RelativeTime at={session.lastSeenAt} fallback="Not since it started" />
        </CardFact>
        <CardFact label="Started">
          <RelativeTime at={session.createdAt} />
        </CardFact>
        <CardFact label="Expires">
          <RelativeTime at={session.expiresAt} />
        </CardFact>
      </CardFacts>

      {session.userAgent ? (
        <p className="font-mono text-[0.6875rem] leading-snug break-all text-ink-400 dark:text-ink-600">
          {session.userAgent}
        </p>
      ) : null}

      {session.current ? (
        <p className="mt-3 text-xs text-ink-500">
          Use “Sign out” in the account menu to end this one.
        </p>
      ) : (
        <Button
          variant="danger"
          className="mt-3 w-full sm:w-auto"
          loading={busy}
          onClick={onRevoke}
        >
          Sign this one out
        </Button>
      )}
    </li>
  )
}

/**
 * A readable name for a user-agent string.
 *
 * A summary, not a parse: the full string is printed underneath, so this only has to be
 * good enough for somebody to recognise their own laptop in a list of four. Order matters
 * - Edge and every Chromium browser claim to be Chrome, and Chrome claims to be Safari.
 */
function describeAgent(userAgent: string | null): string {
  if (!userAgent) {
    return 'Unidentified client'
  }

  const browser = userAgent.includes('Edg/')
    ? 'Edge'
    : userAgent.includes('OPR/')
      ? 'Opera'
      : userAgent.includes('Firefox/')
        ? 'Firefox'
        : userAgent.includes('Chrome/')
          ? 'Chrome'
          : userAgent.includes('Safari/')
            ? 'Safari'
            : null

  const platform = userAgent.includes('Android')
    ? 'Android'
    : /iPhone|iPad|iPod/.test(userAgent)
      ? 'iOS'
      : userAgent.includes('Windows')
        ? 'Windows'
        : userAgent.includes('Mac OS X')
          ? 'macOS'
          : userAgent.includes('Linux')
            ? 'Linux'
            : null

  if (browser && platform) {
    return `${browser} on ${platform}`
  }
  if (browser ?? platform) {
    return (browser ?? platform) as string
  }
  // Something that is not a browser at all - curl, a script, a monitoring probe. Showing
  // the first token is more use than calling it unknown.
  return userAgent.split(' ')[0] ?? 'Unidentified client'
}
