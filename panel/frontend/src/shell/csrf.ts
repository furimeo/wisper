/**
 * The CSRF token, for the requests Inertia does not make.
 *
 * `SecurityConfig` uses `CookieCsrfTokenRepository.withHttpOnlyFalse()`, so the token
 * arrives as the `XSRF-TOKEN` cookie and has to come back as the `X-XSRF-TOKEN` header.
 * Inertia's own client does that for every `router` visit; a feature that reaches for
 * `fetch` directly - the chunked upload, the terminal's keystrokes, the file editor's
 * save - does not get it for free, and a missing header is a 403 whose message says
 * nothing about cookies.
 */

const COOKIE = 'XSRF-TOKEN'
const HEADER = 'X-XSRF-TOKEN'

/** The current token, or null when no write has been attempted and no cookie exists yet. */
export function csrfToken(): string | null {
  for (const entry of document.cookie.split(';')) {
    const separator = entry.indexOf('=')
    if (separator < 0) {
      continue
    }
    if (entry.slice(0, separator).trim() === COOKIE) {
      return decodeURIComponent(entry.slice(separator + 1).trim())
    }
  }
  return null
}

/**
 * Headers for a `fetch` that changes something.
 *
 * Merge, rather than replace, so a caller can add `Content-Type` without having to know
 * the name of the CSRF header.
 */
export function csrfHeaders(extra?: Record<string, string>): Record<string, string> {
  const token = csrfToken()
  const headers: Record<string, string> = {...extra}
  if (token !== null) {
    headers[HEADER] = token
  }
  return headers
}
