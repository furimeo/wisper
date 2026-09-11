import {useMemo} from 'react'

import {buildQrSymbol} from './qrMatrix'

/**
 * A QR code as inline SVG, drawn in the browser from the text it encodes.
 *
 * Nothing is fetched. The payload here is the `otpauth://` URI, which contains the shared
 * secret of somebody's second factor, and asking an image service to render it would put
 * that secret in a third party's URL log for the sake of saving a few hundred lines.
 *
 * Always black on white, in both themes. A scanner needs the dark modules darker than the
 * light ones, and an inverted code - white modules on a dark card - is a decode failure on
 * a good number of phone cameras. The white plate doubles as the four-module quiet zone
 * the standard requires, which is not decoration either: without it a scanner cannot find
 * the edge of the symbol.
 */

type QrCodeProps = {
  /** The text to encode. */
  value: string
  /** Read out in place of the image; the secret is on screen as text as well. */
  label: string
  className?: string
}

/** Modules of white around the symbol. Four is the minimum the standard allows. */
const QUIET_ZONE = 4

export function QrCode({value, label, className}: QrCodeProps) {
  const drawing = useMemo(() => draw(value), [value])

  if (!drawing) {
    return (
      <div
        className={
          'flex items-center rounded-lg border border-ink-300 bg-ink-100 p-4 text-xs ' +
          'leading-relaxed text-ink-600 dark:border-ink-700 dark:bg-ink-900 dark:text-ink-400 ' +
          (className ?? '')
        }
      >
        This address is too long to fit in a QR code. Type the secret below into your
        authenticator app instead - it works exactly the same way.
      </div>
    )
  }

  return (
    <svg
      role="img"
      aria-label={label}
      viewBox={`0 0 ${drawing.extent} ${drawing.extent}`}
      className={className}
      // Square modules with hard edges. Any smoothing here is a scan failure.
      shapeRendering="crispEdges"
    >
      <rect width={drawing.extent} height={drawing.extent} fill="#ffffff" />
      <path d={drawing.path} fill="#000000" />
    </svg>
  )
}

/** The dark modules as one path, or null when the value will not fit in a symbol. */
function draw(value: string): {extent: number; path: string} | null {
  let symbol
  try {
    symbol = buildQrSymbol(value)
  } catch {
    // QrPayloadTooLong. The caller's fallback is the secret in text, which is the same
    // information by a slower route, so there is nothing to report beyond the notice.
    return null
  }

  const parts: string[] = []
  for (let y = 0; y < symbol.size; y++) {
    for (let x = 0; x < symbol.size; x++) {
      if (symbol.modules[y * symbol.size + x] === 1) {
        parts.push(`M${x + QUIET_ZONE} ${y + QUIET_ZONE}h1v1h-1z`)
      }
    }
  }
  return {extent: symbol.size + QUIET_ZONE * 2, path: parts.join('')}
}
