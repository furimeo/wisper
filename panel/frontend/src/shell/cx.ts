/**
 * Joins class names, dropping anything falsy.
 *
 * Every component here builds its class list from a base plus a handful of conditional
 * parts, and writing that inline as a template literal leaves double spaces and stray
 * `undefined` in the DOM. Twelve lines of the JDK-equivalent rather than a dependency:
 * the panel has no use for the parts of `clsx` that handle nested arrays and objects.
 */
export function cx(...parts: Array<string | false | null | undefined>): string {
  let out = ''
  for (const part of parts) {
    if (!part) {
      continue
    }
    out = out === '' ? part : `${out} ${part}`
  }
  return out
}
