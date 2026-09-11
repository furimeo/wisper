import type {Catalog} from './translate'

/**
 * Loading the right catalogue, and holding it where a render can reach it.
 *
 * <h2>One language ships, not both</h2>
 *
 * <p>The two catalogues are separate modules behind a static `import()`, which is what
 * lets Vite split them into their own chunks. Every sentence in this product is
 * translated, including the long explanatory ones, so bundling both would mean every
 * visitor downloading a language they cannot read.
 *
 * <h2>Why a module variable and not a React context</h2>
 *
 * <p>The catalogue is loaded once, before the app mounts, and cannot change afterwards:
 * the language lives on the account, and changing it is a form post followed by a fresh
 * page. A context would thread a provider through the tree and re-render everything to
 * model a value that never changes within the life of the page.
 */
let active: Catalog = {}
let activeLocale = 'en'

/** Fetches a locale's catalogue. Unknown tags fall back to English rather than failing. */
export async function loadCatalog(locale: string): Promise<Catalog> {
  switch (locale) {
    case 'vi':
      return (await import('./vi')).catalog
    default:
      return (await import('./en')).catalog
  }
}

/**
 * Called once by `main.tsx`, before the first render.
 *
 * <p>Not named `useCatalog`: React reserves that prefix for hooks and the linter enforces
 * the rules of hooks on anything wearing it, which this is not.
 */
export function installCatalog(locale: string, catalog: Catalog): void {
  activeLocale = locale
  active = catalog
}

export function currentCatalog(): Catalog {
  return active
}

export function currentLocale(): string {
  return activeLocale
}
