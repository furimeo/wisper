-- The language this account reads the panel in.
--
-- On the account rather than in the browser because it has to reach two places that a
-- browser preference cannot: the flash message a controller writes before the page is
-- rendered, and any mail or notification generated for this person while they are not
-- looking at a screen. A localStorage toggle would leave those in English for ever.
--
-- 'en' is the default and the fallback. A locale that is not in the CHECK is refused at
-- the boundary rather than silently rendering a screen of missing-key placeholders; the
-- list grows when a translation is complete, not when one is started.
ALTER TABLE account
    ADD COLUMN locale text NOT NULL DEFAULT 'en';

ALTER TABLE account
    ADD CONSTRAINT account_locale_supported CHECK (locale IN ('en', 'vi'));
