package lhqm.furimeo.wisper.files;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * The web shell: the page, opening a session, and reading its output.
 *
 * <p>Three requests because a terminal is not a document. The page renders xterm.js and a
 * key bar for phones; a {@code POST} starts a PTY and gets an id back; an
 * {@code EventSource} against that id carries the output. Typing goes the other way, through
 * {@link TerminalInputController}.
 *
 * <p>Server-Sent Events rather than a WebSocket. The panel already streams deployment logs
 * and metrics this way, one realtime protocol is one thing to operate through a tunnel, and
 * the direction that needs framing - output - is the direction SSE handles. Input is a small
 * {@code POST} per keystroke burst, which on a phone is what the on-screen keyboard produces
 * anyway.
 *
 * <p>Output is base64 ({@link TerminalFrameCodec}). A PTY carries bytes, and the
 * predecessor's terminal broke precisely because they were treated as text somewhere in the
 * middle (design §11.5).
 *
 * <p>Renders {@code features/files/TerminalPage.tsx}.
 */
@Controller
public class TerminalController {

    private final AuthorizeTerminal authorize;
    private final OpenTerminal open;
    private final LiveTerminals live;
    private final FilesSettings settings;

    public TerminalController(AuthorizeTerminal authorize, OpenTerminal open, LiveTerminals live,
                              FilesSettings settings) {
        this.authorize = authorize;
        this.open = open;
        this.live = live;
        this.settings = settings;
    }

    /**
     * The page.
     *
     * <p>No session is opened here. A customer who lands on the terminal tab while reading
     * something else should not have started a shell inside their container by doing so, and
     * a PTY opened by a page load is one that stays open through every back-button visit.
     *
     * <p>Props: {@code service}, {@code canOpen}, {@code placed}, {@code idleTimeoutSeconds},
     * {@code maxDurationSeconds}.
     */
    @GetMapping("/services/{serviceId}/terminal")
    public String page(@PathVariable UUID serviceId, HttpServletRequest request, Model model) {
        TerminalAccess access = authorize.toView(serviceId, request);
        model.addAttribute("service", access.service());
        // A VIEWER sees the page and is told why the button is not there, which is a better
        // answer than a 403 on a link the navigation offered them.
        model.addAttribute("canOpen", access.membership().canWrite());
        model.addAttribute("placed", access.service().isPlaced());
        model.addAttribute("idleTimeoutSeconds", settings.terminalIdleTimeout().toSeconds());
        model.addAttribute("maxDurationSeconds", settings.terminalMaxDuration().toSeconds());
        return "files/Terminal";
    }

    /**
     * Starts a shell and returns its id.
     *
     * <p>The browser sends the size it has, so the first prompt is drawn at the right width
     * rather than at 80x24 and then redrawn.
     */
    @PostMapping(path = "/services/{serviceId}/terminal", produces =
            MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public TerminalView start(@PathVariable UUID serviceId,
                              @RequestParam(name = "columns", defaultValue = "0") int columns,
                              @RequestParam(name = "rows", defaultValue = "0") int rows,
                              HttpServletRequest request) {
        TerminalAccess access = authorize.forService(serviceId, "terminal.open", request);
        return open.open(access, columns, rows);
    }

    /**
     * The output stream.
     *
     * <p>Only the account that opened the session may read it. A shell is not shared, and a
     * session id in a URL is a guess anybody with an account on the same service could make.
     */
    @GetMapping(path = "/services/{serviceId}/terminal/{sessionId}/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ResponseBody
    public SseEmitter stream(@PathVariable UUID serviceId, @PathVariable String sessionId,
                             HttpServletRequest request, HttpServletResponse response) {
        TerminalAccess access = authorize.forService(serviceId, "terminal.open", request);
        LiveTerminals.Held held = live.find(serviceId, sessionId)
                .filter(session -> session.accountId().equals(access.membership().accountId()))
                .orElseThrow(() -> NotFoundException.of("terminal session", sessionId));

        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Accel-Buffering", "no");

        SseEmitter emitter = new SseEmitter(settings.terminalMaxDuration().toMillis());
        // Detach, not release: a browser that dropped its connection reconnects and picks up
        // the same shell, which is the difference between a tunnel hiccup and losing the
        // command somebody was half way through typing.
        emitter.onCompletion(held::detach);
        emitter.onTimeout(held::detach);
        emitter.onError(failure -> held.detach());
        held.attach(emitter);
        return emitter;
    }
}
