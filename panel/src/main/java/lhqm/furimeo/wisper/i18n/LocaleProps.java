package lhqm.furimeo.wisper.i18n;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import lhqm.furimeo.wisper.auth.SupportedLocale;
import lhqm.furimeo.wisper.web.SharedPropsContributor;

/**
 * Tells every page which language to render in, and which languages exist.
 *
 * <p>The tag is a shared prop rather than something the client works out for itself. The
 * browser cannot: the choice lives on the account, and a client that guessed from
 * {@code navigator.language} would disagree with the server on the very same page - a
 * flash message in one language above a form in another.
 *
 * <p>The list is here too, because the language picker in Profile has to be able to name
 * a language somebody cannot currently read. Each entry carries the language's own name
 * for itself, never a translated one.
 */
@Component
public class LocaleProps implements SharedPropsContributor {

    private static final List<Map<String, String>> LOCALES = List.of(SupportedLocale.values())
            .stream()
            .map(locale -> Map.of("tag", locale.tag(), "name", locale.nativeName()))
            .toList();

    private final PanelLocale panelLocale;

    public LocaleProps(PanelLocale panelLocale) {
        this.panelLocale = panelLocale;
    }

    @Override
    public void contribute(Map<String, Object> props, HttpServletRequest request) {
        props.put("locale", panelLocale.current(request).tag());
        props.put("locales", LOCALES);
    }
}
