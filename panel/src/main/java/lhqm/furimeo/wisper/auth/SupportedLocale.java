package lhqm.furimeo.wisper.auth;

import java.util.List;
import java.util.Locale;

/**
 * The languages wisper is translated into.
 *
 * <p>One list, and it is the same list the {@code account_locale_supported} CHECK
 * enforces. A locale reaches this enum only when its catalogue is complete on both sides -
 * Java's {@code messages_xx.properties} and the browser's per-feature message files -
 * because a half-translated language is worse than an untranslated one: the reader cannot
 * tell a missing string from a deliberate English term, and the screens they most need are
 * the rare ones nobody got to.
 *
 * @param tag  the BCP 47 tag, and the value stored in {@code account.locale}
 * @param name what the language calls itself, which is what a language picker must show:
 *             somebody who cannot read the current language cannot find "Vietnamese"
 */
public enum SupportedLocale {

    EN("en", "English"),
    VI("vi", "Tiếng Việt");

    private final String tag;
    private final String name;

    SupportedLocale(String tag, String name) {
        this.tag = tag;
        this.name = name;
    }

    public String tag() {
        return tag;
    }

    /** The language's own name for itself. Never translated. */
    public String nativeName() {
        return name;
    }

    public Locale toLocale() {
        return Locale.forLanguageTag(tag);
    }

    public static boolean isSupported(String tag) {
        return tag != null && List.of(values()).stream().anyMatch(l -> l.tag.equals(tag));
    }

    /**
     * The closest supported language to what a browser asked for, falling back to English.
     *
     * <p>Matched on the language subtag alone: {@code vi-VN} and {@code vi} are the same
     * translation, and a panel that answered a {@code vi-VN} browser in English because
     * the region did not match would be technically correct and useless.
     */
    public static SupportedLocale of(String tag) {
        if (tag == null || tag.isBlank()) {
            return EN;
        }
        String language = Locale.forLanguageTag(tag).getLanguage();
        for (SupportedLocale candidate : values()) {
            if (candidate.tag.equals(language)) {
                return candidate;
            }
        }
        return EN;
    }
}
