import type {ResolvedComponent} from '@inertiajs/react'

/**
 * A page component, optionally carrying a persistent layout.
 *
 * Inertia's own type, re-exported so pages have one name to import and a change in the
 * adapter shows up here rather than in forty files. Setting `layout` on the page rather
 * than wrapping every page from this module keeps sign-in and the error screen free of
 * app chrome they have nothing to navigate with, and keeps the list of exceptions out of
 * this file.
 */
export type PageComponent = ResolvedComponent

type PageModule = {default: PageComponent}

/*
 * Every page in the application. Vite turns this into a static map of dynamic imports at
 * build time, so each page is its own chunk and the first load does not carry the
 * terminal and the file editor with it.
 *
 * The pattern is the naming rule, enforced by the bundler: a page is a *Page.tsx inside
 * the feature folder it belongs to. A component that is not a page cannot accidentally
 * become routable.
 */
const pages = import.meta.glob<PageModule>('../features/**/*Page.tsx')

/**
 * Maps the view name a Spring controller returned to the file that renders it.
 *
 * `return "deploy/DeploymentList"` loads `features/deploy/DeploymentListPage.tsx`. The
 * controller decides what renders, exactly as it did with server-side templates, so
 * there is no client-side routing table to fall out of sync with the server's permission
 * checks.
 */
export async function resolvePage(name: string): Promise<PageComponent> {
  const path = `../features/${name}Page.tsx`
  const load = pages[path]
  if (!load) {
    throw new Error(
      `No page for view name "${name}". A controller returned it, so create ` +
        `src/features/${name}Page.tsx - the file must end in "Page.tsx" to be found.`,
    )
  }
  return (await load()).default
}
