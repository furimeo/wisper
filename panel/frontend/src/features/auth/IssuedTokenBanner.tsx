import {t} from '@/i18n'
import {CopyButton} from '@/shell'

/**
 * The one and only sight of a new API token.
 *
 * `ApiTokenSettingsController` hands the value over as a flash attribute, so it is a prop
 * on exactly the render that follows creation and is gone on a reload. The panel keeps
 * only a SHA-256 of it; there is no endpoint that could show it again, which is the point
 * - a token the page could re-fetch is a token stored somewhere it could be re-fetched
 * from.
 *
 * It is deliberately not selected automatically and not put in the URL. Both would leave
 * it somewhere it outlives this render: a clipboard the customer did not ask for, or the
 * browser's history and whatever proxy sits in front of the panel.
 */
type IssuedTokenBannerProps = {
  token: string
  name: string
}

export function IssuedTokenBanner({token, name}: IssuedTokenBannerProps) {
  return (
    <section
      aria-labelledby="issued-token-heading"
      className="rounded-xl border border-degraded/50 bg-degraded/10 p-4 md:p-5"
    >
      <h2 id="issued-token-heading" className="text-base font-semibold">
        {t('auth.tokens.bannerReady', {name})}
      </h2>
      <p className="mt-1.5 text-sm leading-relaxed text-ink-700 dark:text-ink-300">
        {t('auth.tokens.bannerCopyPrompt')}
      </p>

      <p className="mt-3 rounded-lg bg-white px-3 py-2.5 font-mono text-sm break-all select-all dark:bg-ink-950">
        {token}
      </p>

      <div className="mt-3">
        <CopyButton
          value={token}
          label={t('auth.tokens.copyToken')}
          describedAs={t('auth.tokens.copyTokenDesc')}
          className="w-full sm:w-auto"
        />
      </div>

      <p className="mt-3 text-xs leading-relaxed text-ink-600 dark:text-ink-400">
        {t('auth.tokens.bannerFooter')}
      </p>
    </section>
  )
}
