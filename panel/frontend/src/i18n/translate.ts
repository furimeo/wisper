/**
 * The whole translation engine. It is deliberately this small.
 *
 * <p>No i18n library. What the panel needs is lookup, `{name}` substitution and a plural
 * choice between two forms; a library brings ICU MessageFormat, a compiler step, a
 * runtime and a plural-rules table for two hundred languages of which this product ships
 * two. English has one plural boundary and Vietnamese has none - `2 dịch vụ` and
 * `1 dịch vụ` differ by the number alone - so the rule below is the whole rule.
 *
 * <p>A missing key returns the key. Not an empty string, which looks like a layout bug and
 * sends somebody hunting through CSS, and not a thrown error, which would take a screen
 * down over a sentence. The key is ugly on purpose: it is visible in a screenshot, it says
 * exactly which entry is missing, and it is greppable.
 */

/** A translated entry: one string, or the two forms English needs. */
export type Message = string | {one: string; other: string}

export type Catalog = Record<string, Message>

export type TranslateParams = Record<string, string | number>

/**
 * Looks up `key` and fills in `params`.
 *
 * <p>When `params.count` is present the entry may be a pair, and the form is chosen by the
 * only rule that differs between the two languages the panel has: English splits at one,
 * Vietnamese does not split at all. A Vietnamese catalogue therefore writes a plain string
 * and never a pair, and gets the same sentence for every number - which is correct, not a
 * shortcut.
 */
export function translate(
  catalog: Catalog,
  locale: string,
  key: string,
  params?: TranslateParams,
): string {
  const entry = catalog[key]
  if (entry === undefined) {
    return key
  }

  const template =
    typeof entry === 'string'
      ? entry
      : pluralFormOf(locale, Number(params?.count ?? 0))
        ? entry.one
        : entry.other

  return params ? fill(template, params) : template
}

/** True when the language wants its singular form for `count`. */
function pluralFormOf(locale: string, count: number): boolean {
  // Vietnamese has no grammatical plural: the noun does not change, so there is one form
  // and the number in front of it carries the whole meaning.
  if (locale === 'vi') {
    return false
  }
  return count === 1
}

/**
 * Replaces `{name}` with `params.name`.
 *
 * <p>A placeholder with no matching parameter is left as it is rather than blanked. A
 * sentence reading "Deleted {name}" is obviously wrong to whoever sees it; one reading
 * "Deleted " looks finished and is not.
 */
function fill(template: string, params: TranslateParams): string {
  return template.replace(/\{(\w+)\}/g, (whole, name: string) => {
    const value = params[name]
    return value === undefined ? whole : String(value)
  })
}
