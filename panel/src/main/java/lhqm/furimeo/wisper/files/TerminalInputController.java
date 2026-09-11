package lhqm.furimeo.wisper.files;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import jakarta.servlet.http.HttpServletRequest;
import lhqm.furimeo.wisper.web.NotFoundException;

/**
 * Typing into a shell, resizing it, and ending it.
 *
 * <p>Three small posts. Separate from {@link TerminalController} because that file owns the
 * page and the output stream and this one owns the three frame types the browser can send -
 * which are exactly the three in {@code terminal.proto}. There is deliberately no
 * "write whatever" endpoint: an unframed write is what broke the predecessor's terminal,
 * where a resize was indistinguishable from keystrokes (design §11.5).
 *
 * <p>Input is base64. What a customer types is routinely an escape sequence and occasionally
 * not valid UTF-8, and a paste from a phone's clipboard can be anything at all.
 *
 * <p>Every request re-checks the membership and the owner. A shell is not shared, and a
 * membership revoked while somebody had one open has to stop working at the next keystroke
 * rather than at the idle timeout.
 */
@Controller
public class TerminalInputController {

    private final AuthorizeTerminal authorize;
    private final LiveTerminals live;
    private final CloseTerminal closeTerminal;

    public TerminalInputController(AuthorizeTerminal authorize, LiveTerminals live,
                                   CloseTerminal closeTerminal) {
        this.authorize = authorize;
        this.live = live;
        this.closeTerminal = closeTerminal;
    }

    /**
     * Keystrokes.
     *
     * @param data base64 of the bytes the terminal produced
     */
    @PostMapping(path = "/services/{serviceId}/terminal/{sessionId}/input",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public TerminalAck input(@PathVariable UUID serviceId, @PathVariable String sessionId,
                             @RequestParam(name = "data") String data,
                             HttpServletRequest request) {
        TerminalSession session = mine(serviceId, sessionId, request).session();
        session.send(TerminalFrameCodec.decode(data));
        return TerminalAck.of(session);
    }

    /**
     * The window changed.
     *
     * <p>On a phone this fires every time the on-screen keyboard opens. Its own frame type
     * because a curses application that never hears about a resize draws over itself, and the
     * customer sees a broken screen rather than a wrong one.
     */
    @PostMapping(path = "/services/{serviceId}/terminal/{sessionId}/resize",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public TerminalAck resize(@PathVariable UUID serviceId, @PathVariable String sessionId,
                              @RequestParam(name = "columns") int columns,
                              @RequestParam(name = "rows") int rows,
                              HttpServletRequest request) {
        TerminalSession session = mine(serviceId, sessionId, request).session();
        session.resize(columns, rows);
        return TerminalAck.of(session);
    }

    /**
     * Ends the shell.
     *
     * <p>Sent by the page's unload handler as well as by the button, so it has to be safe to
     * call for a session that has already gone.
     */
    @PostMapping(path = "/services/{serviceId}/terminal/{sessionId}/close",
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public TerminalAck close(@PathVariable UUID serviceId, @PathVariable String sessionId,
                             HttpServletRequest request) {
        TerminalAccess access = authorize.forService(serviceId, "terminal.close", request);
        closeTerminal.close(access, sessionId);
        return TerminalAck.closed(sessionId);
    }

    /**
     * The session, if it is this caller's.
     *
     * @throws NotFoundException when it is not theirs or does not exist - the same answer for
     *         both, so a session id cannot be probed for
     */
    private LiveTerminals.Held mine(UUID serviceId, String sessionId, HttpServletRequest request) {
        TerminalAccess access = authorize.forService(serviceId, "terminal.open", request);
        return live.find(serviceId, sessionId)
                .filter(held -> held.accountId().equals(access.membership().accountId()))
                .orElseThrow(() -> NotFoundException.of("terminal session", sessionId));
    }
}
