package lhqm.furimeo.wisper.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import lhqm.furimeo.wisper.auth.PlatformRole;
import lhqm.furimeo.wisper.auth.SignedInAccount;
import lhqm.furimeo.wisper.auth.SupportedLocale;

class PanelLocaleTest {

    private final PanelLocale panelLocale = new PanelLocale();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void signedInAccountLocaleTakesPrecedenceOverAcceptLanguage() {
        SignedInAccount account = new SignedInAccount(
                UUID.randomUUID(),
                "operator@example.com",
                "Operator",
                PlatformRole.ADMIN,
                "vi"
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(account, null, account.authorities())
        );

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Accept-Language", "en-US,en;q=0.9");

        SupportedLocale locale = panelLocale.current(request);
        assertThat(locale).isEqualTo(SupportedLocale.VI);
        assertThat(panelLocale.currentLocale(request)).isEqualTo(SupportedLocale.VI.toLocale());
    }

    @Test
    void anonymousRequestReadsAcceptLanguageHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Accept-Language", "vi-VN,vi;q=0.9,en;q=0.8");

        SupportedLocale locale = panelLocale.current(request);
        assertThat(locale).isEqualTo(SupportedLocale.VI);
    }

    @Test
    void unknownLanguageFallsBackToEnglish() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Accept-Language", "fr-FR,fr;q=0.9,de;q=0.8");

        SupportedLocale locale = panelLocale.current(request);
        assertThat(locale).isEqualTo(SupportedLocale.EN);
    }

    @Test
    void emptyOrMissingHeaderDefaultsToEnglish() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        SupportedLocale locale = panelLocale.current(request);
        assertThat(locale).isEqualTo(SupportedLocale.EN);
        assertThat(panelLocale.currentLocale(null)).isEqualTo(SupportedLocale.EN.toLocale());
    }
}
