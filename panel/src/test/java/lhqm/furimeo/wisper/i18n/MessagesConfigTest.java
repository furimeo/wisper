package lhqm.furimeo.wisper.i18n;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.LocaleResolver;

import lhqm.furimeo.wisper.auth.SupportedLocale;

class MessagesConfigTest {

    private final MessagesConfig config = new MessagesConfig();
    private final MessageSource messageSource = config.messageSource();

    @Test
    void resolvesVietnameseMessagesInUtf8() {
        Locale vi = SupportedLocale.VI.toLocale();
        String message = messageSource.getMessage("auth.signin.failed", null, vi);

        assertThat(message).isEqualTo("Email và mật khẩu không khớp với tài khoản nào.");
    }

    @Test
    void missingKeyInVietnameseFallsBackToEnglish() {
        Locale vi = SupportedLocale.VI.toLocale();
        // Even if a message key is present in messages.properties but absent in messages_vi.properties,
        // it falls back to the English bundle.
        String english = messageSource.getMessage("error.notFound", new Object[]{"thing"}, Locale.ENGLISH);
        String vietnamese = messageSource.getMessage("error.notFound", new Object[]{"thứ"}, vi);

        assertThat(english).isEqualTo("There is no thing here.");
        assertThat(vietnamese).isEqualTo("Không có thứ nào ở đây.");
    }

    @Test
    void localeResolverDelegatesToPanelLocaleAndProhibitsMutation() {
        PanelLocale panelLocale = new PanelLocale();
        LocaleResolver resolver = config.localeResolver(panelLocale);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Accept-Language", "vi");

        assertThat(resolver.resolveLocale(request)).isEqualTo(SupportedLocale.VI.toLocale());

        assertThatThrownBy(() -> resolver.setLocale(request, new MockHttpServletResponse(), Locale.ENGLISH))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
