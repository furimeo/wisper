package lhqm.furimeo.wisper.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;

/**
 * The panel speaks WebSocket, in exactly one place.
 *
 * <p>Realtime in wisper is Server-Sent Events: deployment logs, container logs and metrics
 * are one-way, SSE is one protocol to operate through a tunnel, and none of that changes.
 * A terminal is the exception, and it is an exception on the merits. Carrying its input
 * over HTTP meant a request per keystroke - each one through the whole security filter
 * chain, each one re-reading the signed-in account from the database - so a handful of
 * people typing turned fifty bytes a second into fifty queries a second. A socket is
 * authorised once, at the handshake.
 *
 * <p>This class is the switch and nothing else. It carries no path and no handler:
 * {@code @EnableWebSocket} collects every {@code WebSocketConfigurer} in the application,
 * and the package that owns a path registers its own - {@code files.TerminalSocketRoute}
 * is the only one. Keeping the framework switch here, beside {@link SecurityConfig} and
 * {@link WebMvcConfig}, and the route in the feature is the same division those two
 * already follow: a reader asking "what protocols does the panel serve" looks in
 * {@code web}, and one asking "who may reach this URL" looks at the package that owns it.
 *
 * <p>Servlet-based throughout - {@code spring-websocket} over the embedded Tomcat that is
 * already there. It brings no WebFlux and no reactive runtime, and the doctrine's ban on
 * both is untouched.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig {
}
