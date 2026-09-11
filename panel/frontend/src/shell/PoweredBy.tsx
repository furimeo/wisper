/**
 * What this panel is built on, in the footer of every page.
 *
 * <p>Not a licence condition - Apache 2.0 asks for the `NOTICE` file to travel with the
 * software and nothing more. It is here for two honest reasons: somebody evaluating a
 * hosting provider should be able to find out what runs it, and a self-hosted project is
 * mostly found by being seen in the footer of something that works.
 *
 * <p>Whoever deploys this is free to change it. `NOTICE` says so and points at this file
 * rather than pretending otherwise, because an attribution that has to be enforced is not
 * attribution, it is a licence term - and this project decided against making it one.
 *
 * <p>It renders inside the application shell rather than on each page, so a screen added
 * later cannot omit it by forgetting.
 */
export function PoweredBy() {
  return (
    <footer className="px-4 pb-4 text-center text-xs text-ink-500 dark:text-ink-400">
      Powered by{' '}
      <a
        href="https://github.com/furimeo/wisper"
        target="_blank"
        rel="noreferrer noopener"
        className="font-medium text-ink-600 underline decoration-ink-300 underline-offset-2 hover:text-accent-600 dark:text-ink-300 dark:decoration-ink-600 dark:hover:text-accent-400"
      >
        wisper
      </a>
    </footer>
  )
}
