package lhqm.furimeo.wisper.auth;

import java.util.Map;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

import lhqm.furimeo.wisper.web.SharedPropsContributor;

/**
 * Puts who is signed in on every page.
 *
 * <p>The header, the account menu and every "is this person an operator" branch in the
 * client need it, and threading it through forty controllers by hand is how it comes to
 * be present on some screens and missing on the one that was added last.
 *
 * <p>The value is {@link SignedInAccount}, which holds an id, an address, a name and a
 * role and no credentials - which is what makes it safe to serialise into the page. It is
 * null on the sign-in page and the error page, and the client's shared-props type says
 * so, so a component that reads it has to decide what to do about that rather than
 * finding out at runtime.
 *
 * <p>Free: the principal is already in the {@code SecurityContext}, so this adds no
 * query. That matters, because this runs on every render including partial reloads.
 */
@Component
public class SignedInAccountProps implements SharedPropsContributor {

    /** The prop name. {@code account} rather than {@code user}: the table is {@code account}. */
    public static final String PROP = "account";

    @Override
    public void contribute(Map<String, Object> props, HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.getPrincipal() instanceof SignedInAccount account) {
            props.put(PROP, account);
            return;
        }
        // Present and null, never absent. A page that has to write account?.email in one
        // place and account.email in another eventually gets it wrong where nobody looked.
        props.put(PROP, null);
    }
}
