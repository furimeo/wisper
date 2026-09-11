package lhqm.furimeo.wisper.files;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Where the terminal socket lives.
 *
 * <p>Under {@code /services/{serviceId}/terminal/**}, which {@code files} owns
 * (panel-http.md) - the same prefix, the same authorization surface and the same volume
 * the shell will be standing in as the file manager. Nesting it under the service is not
 * cosmetic: {@code /terminal/socket?service=41} is the version of this route whose handler
 * forgets to check whose service 41 is.
 *
 * <p>Registered from this package rather than from {@code web}, for the same reason
 * {@code HttpSecurityContribution} exists: the shared configuration answers "does the
 * panel speak WebSocket", and the package that owns a path answers "at what, and who may
 * reach it". {@code web} never learns that there is a terminal.
 */
@Component
public class TerminalSocketRoute implements WebSocketConfigurer {

    private static final String PATH = "/services/{serviceId}/terminal/socket";

    private final TerminalSocketHandler handler;
    private final TerminalSocketHandshake handshake;

    public TerminalSocketRoute(TerminalSocketHandler handler, TerminalSocketHandshake handshake) {
        this.handler = handler;
        this.handshake = handshake;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        /*
         * No allowed origins are declared, which leaves Spring's default of same-origin
         * only. That is deliberate and it is load-bearing: a handshake is a GET carrying
         * the session cookie, so the CSRF token cannot protect it, and the origin check is
         * what stops another site opening a shell in a customer's container with a cookie
         * their browser attached for it.
         *
         * No SockJS fallback either. It exists for browsers without WebSocket, which is
         * none of the browsers a customer administering hosting from a phone is using, and
         * its polling transports would put the per-keystroke request back.
         */
        registry.addHandler(handler, PATH).addInterceptors(handshake);
    }
}
