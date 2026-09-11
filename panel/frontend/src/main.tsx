import {createInertiaApp} from '@inertiajs/react'
import {createRoot} from 'react-dom/client'
import {StrictMode} from 'react'

import './styles.css'
import {resolvePage} from './inertia/resolvePage'
import {DetachedNotice} from './inertia/DetachedNotice'
import {AppLayout} from './shell/AppLayout'

const root = document.getElementById('app')

if (!root) {
  throw new Error('No #app element. The server shell in InertiaPage.java writes it.')
}

/*
 * Pages that render without the application chrome.
 *
 * Named here, once, rather than opted out of by each page: the failure this arrangement
 * prevents is a page added later that forgets to ask for the layout and renders as a bare
 * fragment on a white background, which is precisely the class of bug this project exists
 * to stop shipping. Every other page gets the chrome whether or not its author thought
 * about it.
 *
 * Sign-in and the second-factor challenge have nothing to navigate with - there is no
 * account, no organization and no permission to read anything - so chrome there would be
 * a sidebar full of links that all bounce back to the sign-in page.
 */
const BARE_PAGES = new Set(['auth/SignIn', 'auth/TwoFactorChallenge'])

/**
 * The layout for a page that did not choose one.
 *
 * The error page is the interesting case: it is reached both by a signed-in customer who
 * mistyped a URL, who should keep their navigation, and by an anonymous visitor, who has
 * none to keep. `account` answers that, and it is a shared prop on every page including
 * this one.
 */
function defaultLayout(name: string, page: {props: Record<string, unknown>}) {
  if (BARE_PAGES.has(name)) {
    return undefined
  }
  if (name === 'error/Error' && page.props.account == null) {
    return undefined
  }
  return AppLayout
}

/*
 * The page object is a JSON script tag the server writes. Its absence means this bundle
 * was loaded from the Vite dev server directly rather than through the panel, and
 * Inertia would answer that with an error overlay over a blank page.
 */
if (!document.querySelector('script[data-page="app"]')) {
  createRoot(root).render(
    <StrictMode>
      <DetachedNotice />
    </StrictMode>,
  )
} else {
  void createInertiaApp({
    progress: {color: 'oklch(0.66 0.145 232)'},

    /*
     * Send every request body as form data, not JSON.
     *
     * The controllers read their input with @ModelAttribute and @RequestParam, which the
     * servlet container fills from a parsed form body - it does not parse JSON. Inertia
     * defaults to JSON, so without this every form in the panel answers 400 with
     * "Required request parameter is not present", and the page has no way to explain
     * itself.
     *
     * It goes in `defaults` rather than a config call beforehand: createInertiaApp
     * starts by replacing the configuration wholesale, so anything set earlier is
     * silently discarded - present in the bundle, and gone by the first request.
     *
     * And here rather than at each call site, because there will be dozens of those and
     * one forgotten is one broken form that nobody finds until a customer tries it.
     */
    defaults: {visitOptions: () => ({forceFormData: true})},

    resolve: resolvePage,

    layout: defaultLayout,

    /*
     * The document title. A page says what it is with `<Head title="...">`; this adds the
     * suffix so no page has to remember it, and answers for the pages that say nothing.
     */
    title: (title) => (title ? `${title} · wisper` : 'wisper'),

    setup({el, App, props}) {
      createRoot(el).render(
        <StrictMode>
          <App {...props} />
        </StrictMode>,
      )
    },
  })
}
