package lhqm.furimeo.wisper.i18n;

import java.util.Locale;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import lhqm.furimeo.wisper.auth.SignedInAccount;
import lhqm.furimeo.wisper.auth.SupportedLocale;

/**
 * Which language this request is answered in.
 *
 * <h2>The account first, the browser second</h2>
 *
 * <p>A signed-in account carries its own choice, and it wins. Anything else would mean a
 * person who has picked Vietnamese sees English the moment they open the panel on a
 * borrowed laptop, and a shared machine would flip languages between two colleagues.
 *
 * <p>Before sign-in there is no account, so {@code Accept-Language} is all there is - and
 * it is the right answer there: the sign-in page and the error pages are the two screens
 * somebody sees before the panel knows who they are, and answering them in the browser's
 * language is better than answering them in English by default.
 *
 * <p>Anything unrecognised falls back to English rather than failing. A language the
 * panel is not translated into is a fact about the panel, not an error in the request.
 */
@Component
public class PanelLocale {

    /** The language for the request being handled, from the security context. */
    public SupportedLocale current(HttpServletRequest request) {
        SupportedLocale chosen = fromAccount();
        return chosen != null ? chosen : fromBrowser(request);
    }

    /** The same as a {@link Locale}, for {@code MessageSource}. */
    public Locale currentLocale(HttpServletRequest request) {
        return current(request).toLocale();
    }

    private static SupportedLocale fromAccount() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        if (authentication.getPrincipal() instanceof SignedInAccount account) {
            return SupportedLocale.of(account.locale());
        }
        return null;
    }

    /**
     * The first language in {@code Accept-Language} the panel actually has.
     *
     * <p>Walked in the order the browser sent rather than taking the first entry, because
     * a browser configured as {@code fr, vi, en} should get Vietnamese here and not
     * English: the header is a preference list and honouring only its head throws away
     * the part that matters when the top choice is missing.
     */
    private static SupportedLocale fromBrowser(HttpServletRequest request) {
        String header = request == null ? null : request.getHeader("Accept-Language");
        if (header == null || header.isBlank()) {
            return SupportedLocale.EN;
        }
        for (Locale.LanguageRange range : Locale.LanguageRange.parse(header)) {
            for (SupportedLocale candidate : SupportedLocale.values()) {
                if (range.getRange().startsWith(candidate.tag())) {
                    return candidate;
                }
            }
        }
        return SupportedLocale.EN;
    }
}
