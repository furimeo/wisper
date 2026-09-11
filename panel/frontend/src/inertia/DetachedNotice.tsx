/**
 * Shown when the bundle loads without a page object.
 *
 * There is exactly one way to reach this: opening the Vite dev server directly on
 * :5173. The panel is the thing that renders pages - it writes the shell, injects the
 * props and enforces the permissions - so the dev server on its own has nothing to show.
 * Saying that is better than the alternative, which is Inertia throwing into an error
 * overlay about a missing page object and a blank screen behind it.
 */
export function DetachedNotice() {
  return (
    <main className="flex min-h-dvh items-center justify-center p-6">
      <div className="max-w-sm">
        <p className="font-mono text-sm text-accent-600 dark:text-accent-400">wisper</p>
        <h1 className="mt-2 text-xl font-semibold">Nothing to render here</h1>
        <p className="mt-3 text-sm leading-relaxed text-ink-600 dark:text-ink-400">
          This is the Vite dev server. It compiles the client, but the panel is what
          serves pages and decides what you are allowed to see.
        </p>
        <a
          className="mt-5 inline-flex touch-target items-center rounded-lg bg-accent-600 px-4 font-medium text-white"
          href="http://localhost:8080/"
        >
          Open the panel
        </a>
        <p className="mt-4 text-xs text-ink-500">
          Keep this server running - the panel proxies <code>/assets/app/</code> to it,
          so edits still hot-reload.
        </p>
      </div>
    </main>
  )
}
