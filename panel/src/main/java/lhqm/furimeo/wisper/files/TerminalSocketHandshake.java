package lhqm.furimeo.wisper.files;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import lhqm.furimeo.wisper.org.PermissionDenied;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * The one place a terminal socket is authorised.
 *
 * <p>This is the entire reason the terminal moved off HTTP. Keystrokes used to be one
 * {@code POST} each, and every one of them traversed the security filter chain and cost a
 * lookup of the signed-in account: ten people typing was fifty database round trips a
 * second to move fifty bytes. The session cookie rides the upgrade request, so the chain
 * runs here once and never again, and afterwards the connection is a socket with an
 * identity already attached to it.
 *
 * <p>Which is why what happens here has to be complete. Four questions, all of them before
 * the upgrade is accepted:
 *
 * <ol>
 * <li>who is asking - the signed-in account, resolved from the session cookie;</li>
 * <li>whether this service is theirs at all, which is a 404 when it is not, because a 403
 *     on somebody else's resource tells an attacker that the id exists;</li>
 * <li>whether they may write - a shell inside a container can do anything the application
 *     can, so a {@code VIEWER} is refused with a 403;</li>
 * <li>whether the session named in the query is one they opened. A shell is not shared,
 *     and a session id is a guess anybody with an account on the same service could
 *     make.</li>
 * </ol>
 *
 * <p>A refusal is an HTTP status on the handshake, never an accepted socket that closes a
 * moment later. The browser's WebSocket API reports a close with no explanation to the
 * page; a failed upgrade at least reaches the network tab, and it never puts a connection
 * in front of the handler that the handler then has to distrust.
 *
 * <p>The origin is checked by Spring before this runs: {@code TerminalSocketRoute}
 * registers no allowed origins, which leaves the default of same-origin only. That is what
 * stops another site opening a socket with the customer's cookie, which is the WebSocket
 * shape of CSRF and the one the CSRF token cannot answer - a handshake is a {@code GET}.
 */
@Component
public class TerminalSocketHandshake implements HandshakeInterceptor {

    /** The authorised caller, so the close can be audited as the person who opened it. */
    static final String ACCESS = TerminalSocketHandshake.class.getName() + ".access";

    /** The shell this socket is reading, resolved once here rather than per frame. */
    static final String HELD = TerminalSocketHandshake.class.getName() + ".held";

    /** Names the session minted by {@code POST /services/{id}/terminal}. */
    private static final String SESSION_PARAMETER = "session";

    private static final Logger log = LoggerFactory.getLogger(TerminalSocketHandshake.class);

    private final AuthorizeTerminal authorize;
    private final LiveTerminals live;

    public TerminalSocketHandshake(AuthorizeTerminal authorize, LiveTerminals live) {
        this.authorize = authorize;
        this.live = live;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler handler, Map<String, Object> attributes) {
        // The panel is Spring MVC on a servlet container and never WebFlux, so the request
        // behind the handshake is always a servlet one - and the audit trail wants it,
        // because an entry without an address is half a trail.
        HttpServletRequest servletRequest = ((ServletServerHttpRequest) request).getServletRequest();
        try {
            UUID serviceId = serviceId(servletRequest);
            String sessionId = sessionId(servletRequest);
            TerminalAccess access = authorize.forService(serviceId, "terminal.attach",
                    servletRequest);
            LiveTerminals.Held held = live.find(serviceId, sessionId)
                    .filter(session -> session.accountId().equals(access.membership().accountId()))
                    .orElseThrow(() -> NotFoundException.of("terminal session", sessionId));
            attributes.put(ACCESS, access);
            attributes.put(HELD, held);
            return true;
        } catch (PermissionDenied denied) {
            return refuse(response, HttpStatus.FORBIDDEN, denied.getMessage());
        } catch (NotFoundException missing) {
            return refuse(response, HttpStatus.NOT_FOUND, missing.getMessage());
        } catch (IllegalArgumentException malformed) {
            return refuse(response, HttpStatus.BAD_REQUEST, malformed.getMessage());
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler handler, Exception failure) {
        if (failure != null) {
            // The upgrade itself failed after this interceptor said yes - a client that
            // hung up mid-handshake, or a container that could not take the connection.
            // Nothing was attached, so there is nothing to release; the reaper ends the
            // shell that was waiting for it.
            log.debug("A terminal upgrade failed after it was authorised: {}",
                    failure.toString());
        }
    }

    /**
     * The service from the path.
     *
     * <p>{@code /services/{serviceId}/terminal/socket} is matched by the handler mapping,
     * which exposes the variables it captured as a request attribute before the handshake
     * runs. Reading them rather than splitting the path again means the route is written
     * once, in {@code TerminalSocketRoute}, and cannot drift from what is parsed here.
     */
    @SuppressWarnings("unchecked")
    private static UUID serviceId(HttpServletRequest request) {
        Object captured = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String value = captured instanceof Map<?, ?> variables
                ? ((Map<String, String>) variables).get("serviceId")
                : null;
        if (value == null) {
            throw new IllegalArgumentException("A terminal socket is opened against a service.");
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException notAUuid) {
            throw new IllegalArgumentException("That is not a service id.");
        }
    }

    private static String sessionId(HttpServletRequest request) {
        String sessionId = request.getParameter(SESSION_PARAMETER);
        if (sessionId == null || sessionId.isBlank()) {
            // The socket attaches to a shell that already exists; it does not open one.
            // Opening is a POST, which is where the audit entry and the CSRF check live.
            throw new IllegalArgumentException("A terminal socket names the session it is "
                    + "attaching to.");
        }
        return sessionId;
    }

    private static boolean refuse(ServerHttpResponse response, HttpStatus status, String reason) {
        response.setStatusCode(status);
        try {
            response.getBody().write(reason.getBytes(StandardCharsets.UTF_8));
        } catch (IOException unwritable) {
            // The client is already gone. The status is what mattered and it is set.
            log.trace("Could not write the reason a terminal upgrade was refused", unwritable);
        }
        return false;
    }
}
