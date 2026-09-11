package lhqm.furimeo.wisper.i18n;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.web.servlet.LocaleResolver;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Where the panel's own sentences come from.
 *
 * <p>Two catalogues, not one. This is everything the server writes: flash messages,
 * validation failures, the error pages, the subject of anything mailed. The browser has
 * its own catalogue under {@code frontend/src/i18n} because shipping the server's copy to
 * the client would mean sending every string on every page load, and most of them are for
 * screens the visitor will never open.
 *
 * <p>Spring's own {@code MessageSource} rather than a library: it is already on the
 * classpath, Bean Validation reads from it without wiring, and the alternative is a
 * dependency that does the same thing with different property files.
 */
@Configuration
public class MessagesConfig {

    /**
     * Properties files under {@code wisper/messages}, read as UTF-8.
     *
     * <p>The encoding is not optional. {@code .properties} is ISO-8859-1 by default, and
     * Vietnamese in ISO-8859-1 is mojibake - the file would load without error and every
     * diacritic would be wrong.
     *
     * <p>{@code fallbackToSystemLocale} is off. Left on, a key missing from
     * {@code messages_vi.properties} falls back to whatever the server's own locale
     * happens to be, which on a developer's machine is not the same as in production. Off,
     * it falls back to {@code messages.properties}, which is English and is the same
     * everywhere.
     */
    @Bean
    public MessageSource messageSource() {
        ReloadableResourceBundleMessageSource messages = new ReloadableResourceBundleMessageSource();
        messages.setBasename("classpath:wisper/messages/messages");
        messages.setDefaultEncoding(StandardCharsets.UTF_8.name());
        messages.setFallbackToSystemLocale(false);
        messages.setUseCodeAsDefaultMessage(false);
        return messages;
    }

    /**
     * Resolves the locale the same way every other part of the panel does.
     *
     * <p>Spring's default reads {@code Accept-Language} alone, which would answer a
     * validation error in the browser's language while the surrounding page is in the
     * account's. One resolver, one answer.
     */
    @Bean
    public LocaleResolver localeResolver(PanelLocale panelLocale) {
        return new LocaleResolver() {
            @Override
            public Locale resolveLocale(HttpServletRequest request) {
                return panelLocale.currentLocale(request);
            }

            @Override
            public void setLocale(HttpServletRequest request, HttpServletResponse response,
                                  Locale locale) {
                // The choice lives on the account and is changed in Profile, not by a
                // request parameter. A setter here would be a second way to change it that
                // does not survive the next page load, which is worse than none.
                throw new UnsupportedOperationException(
                        "The language is a property of the account; change it in Profile.");
            }
        };
    }
}
