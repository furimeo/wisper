package lhqm.furimeo.wisper.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.unit.DataSize;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.socket.WebSocketHandler;

import jakarta.servlet.http.HttpServletRequest;
import lhqm.furimeo.wisper.audit.AuditActor;
import lhqm.furimeo.wisper.org.MemberRole;
import lhqm.furimeo.wisper.org.Membership;
import lhqm.furimeo.wisper.org.PermissionDenied;
import lhqm.furimeo.wisper.service.ServiceKind;
import lhqm.furimeo.wisper.service.ServiceLocation;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * The upgrade is where a terminal is authorised, and the only place it is.
 *
 * <p>That is the whole point of the change: after this runs, keystrokes travel on an open
 * socket and no longer traverse the security filter chain one at a time. The cost of
 * getting it once is that it has to be got completely - so every way of not being allowed
 * a shell is refused <em>before</em> the connection exists, with a status the browser's
 * network tab can show, rather than by accepting a socket and closing it a moment later.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TerminalSocketHandshakeTest {

    private static final FilesSettings SETTINGS = new FilesSettings(
            DataSize.ofMegabytes(8), Duration.ofHours(24), 200, 1000, DataSize.ofMegabytes(2),
            Duration.ofMinutes(2), Duration.ofMinutes(30), Duration.ofMinutes(15),
            Duration.ofSeconds(20), Duration.ofMinutes(15), Duration.ofHours(4),
            Duration.ofSeconds(20));

    private final UUID serviceId = UUID.randomUUID();
    private final UUID organizationId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();
    private final String sessionId = UUID.randomUUID().toString();

    private final LiveTerminals live = new LiveTerminals(SETTINGS);
    private final Map<String, Object> attributes = new HashMap<>();
    private final MockHttpServletResponse rawResponse = new MockHttpServletResponse();
    private final ServletServerHttpResponse response = new ServletServerHttpResponse(rawResponse);

    @Mock
    private AuthorizeTerminal authorize;

    @Mock
    private WebSocketHandler handler;

    private TerminalSocketHandshake handshake;

    @BeforeEach
    void wire() {
        handshake = new TerminalSocketHandshake(authorize, live);
    }

    @AfterEach
    void releaseEverything() {
        live.close();
    }

    @Test
    @DisplayName("the account that opened the shell is let through, with its session attached")
    void theOwnerIsLetThrough() {
        allow(MemberRole.DEVELOPER);
        LiveTerminals.Held held = live.reserve(sessionId, serviceId, accountId);

        boolean upgraded = handshake.beforeHandshake(request(sessionId), response, handler,
                attributes);

        assertThat(upgraded).isTrue();
        // Resolved once here so no frame afterwards has to look anything up.
        assertThat(attributes).containsEntry(TerminalSocketHandshake.HELD, held);
        assertThat(attributes.get(TerminalSocketHandshake.ACCESS)).isInstanceOf(
                TerminalAccess.class);
    }

    @Test
    @DisplayName("a viewer is refused with a 403 and no socket at all")
    void aViewerIsRefused() {
        // A shell inside a container can delete the database, rewrite the application and
        // read every injected secret. It is a write, whatever the file manager allows.
        deny(new PermissionDenied("terminal.attach", MemberRole.VIEWER, "write access"));
        live.reserve(sessionId, serviceId, accountId);

        boolean upgraded = handshake.beforeHandshake(request(sessionId), response, handler,
                attributes);

        assertThat(upgraded).isFalse();
        assertThat(rawResponse.getStatus()).isEqualTo(403);
        assertThat(attributes).isEmpty();
    }

    @Test
    @DisplayName("a service in another organization is refused with a 404, not a 403")
    void aServiceInAnotherOrganizationIsRefused() {
        // 404 on purpose: a 403 would confirm that the id exists, which is the enumeration
        // oracle every other lookup in the panel avoids.
        deny(NotFoundException.of("service", serviceId));
        live.reserve(sessionId, serviceId, accountId);

        boolean upgraded = handshake.beforeHandshake(request(sessionId), response, handler,
                attributes);

        assertThat(upgraded).isFalse();
        assertThat(rawResponse.getStatus()).isEqualTo(404);
        assertThat(attributes).isEmpty();
    }

    @Test
    @DisplayName("a colleague's shell is not reachable by guessing its session id")
    void somebodyElsesSessionIsRefused() {
        // Both are developers on the same service, so the membership check passes. A shell
        // is still not shared: it is the one this account opened or it is nothing.
        allow(MemberRole.DEVELOPER);
        live.reserve(sessionId, serviceId, UUID.randomUUID());

        boolean upgraded = handshake.beforeHandshake(request(sessionId), response, handler,
                attributes);

        assertThat(upgraded).isFalse();
        assertThat(rawResponse.getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("a session this panel does not hold is refused")
    void anUnknownSessionIsRefused() {
        allow(MemberRole.OWNER);

        boolean upgraded = handshake.beforeHandshake(request("a-session-that-never-existed"),
                response, handler, attributes);

        assertThat(upgraded).isFalse();
        assertThat(rawResponse.getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("a socket that names no session is refused rather than opening a shell")
    void aHandshakeWithoutASessionIsRefused() {
        allow(MemberRole.OWNER);
        MockHttpServletRequest naked = servletRequest();
        naked.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                Map.of("serviceId", serviceId.toString()));

        boolean upgraded = handshake.beforeHandshake(new ServletServerHttpRequest(naked), response,
                handler, attributes);

        assertThat(upgraded).isFalse();
        assertThat(rawResponse.getStatus()).isEqualTo(400);
    }

    @Test
    @DisplayName("a path variable that is not a service id is refused before anything is looked up")
    void aServiceIdThatIsNotOneIsRefused() {
        MockHttpServletRequest bogus = servletRequest();
        bogus.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                Map.of("serviceId", "../../etc/passwd"));
        bogus.setParameter("session", sessionId);

        boolean upgraded = handshake.beforeHandshake(new ServletServerHttpRequest(bogus), response,
                handler, attributes);

        assertThat(upgraded).isFalse();
        assertThat(rawResponse.getStatus()).isEqualTo(400);
    }

    private void allow(MemberRole role) {
        Membership membership = new Membership(organizationId, accountId, role);
        ServiceLocation service = new ServiceLocation(serviceId, UUID.randomUUID(),
                organizationId, UUID.randomUUID(), "api", "API", ServiceKind.APP);
        given(authorize.forService(eq(serviceId), eq("terminal.attach"),
                any(HttpServletRequest.class)))
                .willReturn(new TerminalAccess(service, membership,
                        AuditActor.account(accountId, "dev@wisper.local", servletRequest())));
    }

    private void deny(RuntimeException refusal) {
        willThrow(refusal).given(authorize).forService(eq(serviceId), eq("terminal.attach"),
                any(HttpServletRequest.class));
    }

    private ServletServerHttpRequest request(String session) {
        MockHttpServletRequest request = servletRequest();
        // What the handler mapping captured from /services/{serviceId}/terminal/socket.
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                Map.of("serviceId", serviceId.toString()));
        request.setParameter("session", session);
        return new ServletServerHttpRequest(request);
    }

    private MockHttpServletRequest servletRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET",
                "/services/" + serviceId + "/terminal/socket");
        request.setRemoteAddr("203.0.113.7");
        request.addHeader("User-Agent", "Mobile Safari");
        return request;
    }
}
