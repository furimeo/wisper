import {router} from '@inertiajs/react'
import type {ReactNode} from 'react'
import {useEffect, useState} from 'react'

import {Badge, Button, Card, CopyButton, RelativeTime} from '@/shell'

import type {NodeDetail} from './nodeTypes'

/**
 * Getting one machine to join, from the panel's side.
 *
 * Design §7.1 and §7.3 in three steps, in order, with nothing implicit. The security of
 * the whole arrangement rests on details that look like formatting until they are missing:
 *
 * - **The token is shown exactly once.** Only its SHA-256 is stored, so nothing can
 *   produce it again. It arrives as a flash prop on the render after issuing and is gone
 *   on reload - which is why the copy control is next to it and not somewhere else.
 * - **The token never goes on a command line.** `argv` is world-readable in `ps` on the
 *   machine, so the command reads a file, and the file is written by the operator.
 * - **The checksum is compared by eye.** The `sha256sum -c` line in the command checks the
 *   script against a hash the same download supplied, which proves nothing on its own. The
 *   value shown here comes from the panel over an authenticated session, and comparing the
 *   two is what makes the check mean something.
 *
 * The countdown is not decoration either: fifteen minutes is short, and an operator who
 * walks away mid-install needs to see that the token died rather than discovering it from
 * an enrolment that quietly fails.
 */
export function NodeEnrolmentPanel({
  node,
  bootstrapToken,
  bootstrapTokenExpiresAt,
  installCommand,
  installChecksum,
}: {
  node: NodeDetail
  /** Flash prop: present on exactly the render that follows issuing a token. */
  bootstrapToken?: string
  bootstrapTokenExpiresAt?: string
  installCommand?: string
  installChecksum?: string
}) {
  const [issuing, setIssuing] = useState(false)
  const summary = node.summary
  const live = node.liveToken
  const command = installCommand ?? node.installCommand
  const checksum = installChecksum ?? node.installChecksum
  const expiresAt = bootstrapTokenExpiresAt ?? live?.expiresAt ?? null
  const remaining = useCountdown(expiresAt)

  if (summary.lifecycle !== 'CREATED' && !bootstrapToken && live === null) {
    return null
  }

  function issue() {
    setIssuing(true)
    router.post(
      `/admin/nodes/${summary.id}/tokens`,
      {},
      {preserveScroll: true, onFinish: () => setIssuing(false)},
    )
  }

  return (
    <Card
      title="Enrol this machine"
      description="Run these three lines on the machine as root. It checks itself over before it
        writes anything, so a machine that fails preflight is left exactly as it was."
      action={
        remaining === null ? null : (
          <Badge tone={remaining.expired ? 'failed' : remaining.urgent ? 'degraded' : 'accent'} dot>
            {remaining.expired ? 'Token expired' : `${remaining.text} left`}
          </Badge>
        )
      }
      footer={
        <div className="flex flex-col gap-2 sm:flex-row-reverse">
          <Button
            block
            className="sm:w-auto"
            variant={live === null ? 'primary' : 'secondary'}
            loading={issuing}
            onClick={issue}
          >
            {live === null ? 'Issue a bootstrap token' : 'Issue a fresh token'}
          </Button>
          {live === null ? null : (
            <p className="text-xs leading-relaxed text-ink-500 sm:mr-auto sm:max-w-md dark:text-ink-400">
              Issuing another revokes nothing on its own - the old one is still live until it
              is used, revoked or times out. Revoke it below if you did not mean to leave two.
            </p>
          )}
        </div>
      }
    >
      <ol className="flex flex-col gap-4">
        <Step index={1} title="Write the token to a file on the machine">
          {bootstrapToken ? (
            <>
              <p className="text-sm leading-relaxed text-ink-700 dark:text-ink-300">
                This is the only time it is shown. Put it in{' '}
                <code className="font-mono">token.txt</code> next to the install script; the
                installer deletes the file once it has enrolled.
              </p>
              <p className="mt-2 rounded-lg border border-degraded/50 bg-degraded/10 px-3 py-2.5 font-mono text-sm break-all select-all">
                {bootstrapToken}
              </p>
              <div className="mt-2">
                <CopyButton
                  value={bootstrapToken}
                  label="Copy token"
                  describedAs="Copy the bootstrap token"
                  className="w-full sm:w-auto"
                />
              </div>
              <p className="mt-2 text-xs leading-relaxed text-ink-600 dark:text-ink-400">
                Do not pass it as <code className="font-mono">--token=</code>. Arguments are
                readable by every user on the machine through{' '}
                <code className="font-mono">ps</code>; the installer only accepts a file or
                standard input.
              </p>
            </>
          ) : (
            <p className="text-sm leading-relaxed text-ink-700 dark:text-ink-300">
              {live === null
                ? 'There is no live token. Issue one below - it is single-use, tied to this record, and good for fifteen minutes.'
                : 'A token is live but its text was only shown when it was issued. If you no longer have it, issue a fresh one and revoke the old.'}
            </p>
          )}
        </Step>

        <Step index={2} title="Check the script against this hash">
          <p className="text-sm leading-relaxed text-ink-700 dark:text-ink-300">
            Compare it by eye with the value the command prints. It is the SHA-256 of{' '}
            <code className="font-mono">/install.sh</code> as this panel renders it, sent to
            you over your authenticated session rather than by the download being checked.
          </p>
          <p className="mt-2 rounded-lg bg-ink-100 px-3 py-2.5 font-mono text-xs break-all select-all dark:bg-ink-950">
            {checksum}
          </p>
          <div className="mt-2">
            <CopyButton
              value={checksum}
              label="Copy checksum"
              size="sm"
              describedAs="Copy the install script checksum"
            />
          </div>
        </Step>

        <Step index={3} title="Run the installer">
          {command ? (
            <>
              <pre className="overflow-x-auto rounded-lg bg-ink-950 px-3 py-2.5 font-mono text-xs leading-relaxed text-ink-100 select-all">
                {command}
              </pre>
              <div className="mt-2">
                <CopyButton
                  value={command}
                  label="Copy command"
                  describedAs="Copy the install command"
                  className="w-full sm:w-auto"
                />
              </div>
            </>
          ) : (
            <p className="text-sm leading-relaxed text-ink-700 dark:text-ink-300">
              The command appears once a token is live. Without one it would install a daemon
              that has nothing to enrol with.
            </p>
          )}
          <p className="mt-2 text-xs leading-relaxed text-ink-600 dark:text-ink-400">
            It runs <code className="font-mono">sasayaki doctor</code> first and stops on a
            required failure, pins this panel&apos;s certificate on first use, and dials{' '}
            <code className="font-mono">{node.dialEndpoint}</code> outwards - nothing has to
            reach the machine from here.
          </p>
        </Step>
      </ol>

      {live?.expiresAt ? (
        <p className="mt-4 text-xs text-ink-500 dark:text-ink-400">
          The live token expires <RelativeTime at={live.expiresAt} />.
        </p>
      ) : null}
    </Card>
  )
}

function Step({
  index,
  title,
  children,
}: {
  index: number
  title: string
  children: ReactNode
}) {
  return (
    <li className="flex gap-3">
      <span
        aria-hidden="true"
        className="mt-0.5 flex size-6 shrink-0 items-center justify-center rounded-full bg-ink-200 text-xs font-semibold text-ink-700 dark:bg-ink-800 dark:text-ink-200"
      >
        {index}
      </span>
      <div className="min-w-0 flex-1">
        <h3 className="text-sm font-semibold text-ink-900 dark:text-ink-100">{title}</h3>
        <div className="mt-1">{children}</div>
      </div>
    </li>
  )
}

interface Countdown {
  text: string
  urgent: boolean
  expired: boolean
}

/**
 * Time left on the token, ticking.
 *
 * A second-resolution countdown rather than "in 14 minutes", because the whole point of a
 * fifteen-minute TTL is that an operator can see it running out while they are pasting
 * things into a shell.
 */
function useCountdown(expiresAt: string | null): Countdown | null {
  const [now, setNow] = useState(() => Date.now())

  useEffect(() => {
    if (expiresAt === null) {
      return
    }
    const timer = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(timer)
  }, [expiresAt])

  if (expiresAt === null) {
    return null
  }
  const deadline = Date.parse(expiresAt)
  if (!Number.isFinite(deadline)) {
    return null
  }

  const seconds = Math.round((deadline - now) / 1000)
  if (seconds <= 0) {
    return {text: '0:00', urgent: true, expired: true}
  }
  const minutes = Math.floor(seconds / 60)
  const rest = seconds % 60
  return {
    text: `${minutes}:${String(rest).padStart(2, '0')}`,
    urgent: seconds < 180,
    expired: false,
  }
}
