/**
 * The address a name would produce, for showing the customer before they submit.
 *
 * This mirrors `project.Slug.normalise` on the server, and the server is the authority:
 * both the new-project and the new-service form leave their slug input empty by default,
 * the server derives the real one, and what this returns is only what the placeholder
 * says. That is why a small disagreement here is harmless and a second validation rule
 * would not be - the panel never sends this value unless the customer typed it.
 *
 * Non-ASCII is dropped rather than transliterated, exactly as the server does. `ö` is
 * `oe` in German and `o` in Swedish, and guessing wrong inside a URL is worse than asking
 * somebody to type an address.
 */
const MAX_LENGTH = 63

export function deriveSlug(raw: string): string {
  const parts = raw
    .toLowerCase()
    .split(/[^a-z0-9]+/)
    .filter((part) => part.length > 0)

  let out = ''
  for (const part of parts) {
    const separator = out.length > 0 ? 1 : 0
    if (out.length + separator + part.length > MAX_LENGTH) {
      // The server budgets the separator with the character it separates and stops at the
      // limit rather than handing back 64 characters the CHECK constraint would refuse.
      const room = MAX_LENGTH - out.length - separator
      if (room > 0) {
        out += (separator ? '-' : '') + part.slice(0, room)
      }
      break
    }
    out += (separator ? '-' : '') + part
  }
  return out
}

/** The sentence shown under a slug input. Mirrors `Slug.rule(minimum)`. */
export function slugRule(minimum: 1 | 2): string {
  return (
    `Use ${minimum} to ${MAX_LENGTH} characters: lower-case letters, digits and dashes, ` +
    'starting with a letter or a digit.'
  )
}
