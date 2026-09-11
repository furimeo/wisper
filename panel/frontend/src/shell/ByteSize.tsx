/**
 * A byte count a person can read.
 *
 * Everything the panel measures in bytes is a hosting quantity - a volume's size, a
 * database's usage against its quota, an artefact, a snapshot - and those are always
 * quoted in powers of 1024 with the short unit names. So 1024 bytes is "1 KB" here, which
 * is what the customer's plan says and what `df` on the node says. The exact figure is in
 * the `title`, because "0.98 GB" is not the number to open a support ticket with.
 */
const UNITS = ['bytes', 'KB', 'MB', 'GB', 'TB', 'PB'] as const

const STEP = 1024

export interface ByteSizeProps {
  bytes: number | null | undefined
  /** What to show when the size is not known - a volume the node has not measured yet. */
  fallback?: string
  className?: string
}

export function ByteSize({bytes, fallback = '-', className}: ByteSizeProps) {
  if (bytes == null || !Number.isFinite(bytes)) {
    return <span className={className}>{fallback}</span>
  }
  return (
    <span className={className} title={`${Math.round(bytes).toLocaleString()} bytes`}>
      {formatBytes(bytes)}
    </span>
  )
}

/**
 * The same formatting, for a string rather than an element - a `title`, an aria-label, a
 * chart axis.
 */
export function formatBytes(bytes: number): string {
  const negative = bytes < 0
  let value = Math.abs(bytes)
  let unit = 0
  while (value >= STEP && unit < UNITS.length - 1) {
    value /= STEP
    unit += 1
  }
  // Whole bytes are whole; anything scaled keeps one decimal below 10 and none above,
  // which is the precision a person can act on without the number getting wide enough to
  // wrap on a phone.
  const rounded =
    unit === 0
      ? Math.round(value).toString()
      : value < 10
        ? value.toFixed(1)
        : Math.round(value).toString()
  return `${negative ? '-' : ''}${rounded} ${UNITS[unit] ?? 'bytes'}`
}

/**
 * A quota bar: used against a limit.
 *
 * Every quota in `org.QuotaAllowance` is a used/limit pair, and drawing it is otherwise
 * copied into the organization overview, the volume list, the database detail and the
 * backup screens. Over the limit is not clamped - it is shown as full and coloured as a
 * failure, because a customer over quota needs to see it, not see a bar that looks fine.
 */
export function ByteQuota({
  used,
  limit,
  className,
}: {
  used: number | null | undefined
  limit: number
  className?: string
}) {
  const measured = used == null || !Number.isFinite(used) ? null : Math.max(0, used)
  const share = measured === null || limit <= 0 ? 0 : Math.min(1, measured / limit)
  const over = measured !== null && limit > 0 && measured > limit

  return (
    <div className={className}>
      <div
        className="h-1.5 w-full overflow-hidden rounded-full bg-ink-200 dark:bg-ink-800"
        role="img"
        aria-label={
          measured === null
            ? 'Usage not measured yet'
            : `${formatBytes(measured)} of ${formatBytes(limit)} used`
        }
      >
        <div
          className={over ? 'h-full bg-failed' : share > 0.85 ? 'h-full bg-degraded' : 'h-full bg-accent-500'}
          style={{width: `${Math.round((over ? 1 : share) * 100)}%`}}
        />
      </div>
      <p className="mt-1 text-xs text-ink-500 tabular-nums dark:text-ink-400">
        {measured === null ? 'Not measured yet' : `${formatBytes(measured)} of ${formatBytes(limit)}`}
      </p>
    </div>
  )
}
