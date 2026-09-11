package lhqm.furimeo.wisper.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The header names that make up the Inertia protocol.
 *
 * <p>Collected here because five different classes read them and a typo in any one of
 * them fails silently: the request simply looks like an ordinary browser navigation and
 * the server answers with the HTML shell, which the client then shows in an error
 * dialog complaining about something unrelated.
 *
 * @see <a href="https://inertiajs.com/the-protocol">The Inertia protocol</a>
 */
public final class InertiaHeaders {

    /** Present and "true" when the request came from the Inertia client, not the address bar. */
    public static final String INERTIA = "X-Inertia";

    /** The asset version the client's bundle was built from. */
    public static final String VERSION = "X-Inertia-Version";

    /** Where to send the browser on a hard navigation, carried by a 409. */
    public static final String LOCATION = "X-Inertia-Location";

    /** Comma-separated prop names the client still wants on a partial reload. */
    public static final String PARTIAL_DATA = "X-Inertia-Partial-Data";

    /** Comma-separated prop names the client explicitly does not want. */
    public static final String PARTIAL_EXCEPT = "X-Inertia-Partial-Except";

    /** The component a partial reload belongs to; a mismatch means send everything. */
    public static final String PARTIAL_COMPONENT = "X-Inertia-Partial-Component";

    private InertiaHeaders() {
    }

    public static boolean isInertiaRequest(HttpServletRequest request) {
        return "true".equals(request.getHeader(INERTIA));
    }
}
