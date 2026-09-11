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

import jakarta.servlet.http.HttpServletRequest;

/**
 * The web shell: the page, and opening a session.
 *
 * <p>Two requests, and neither of them carries a keystroke. The {@code GET} renders
 * xterm.js and a key bar for phones; the {@code POST} starts a PTY and returns its id.
 * Everything after that - output, typing, resizing, ending it - is one WebSocket, handled
 * by {@link TerminalSocketHandler}.
 *
 * <p>Opening stays a {@code POST} rather than becoming the socket's first frame, because
 * it is the state change: it starts a process inside a customer's container and writes the
 * {@code terminal.open} audit entry. A post is where CSRF protection and that entry
 * already live, and a handshake - a {@code GET} the CSRF token cannot cover - is not.
 *
 * <p>The socket is the one place in the panel that is not Server-Sent Events, and it is
 * not a preference. Input over HTTP was a request per keystroke, each running the whole
 * security filter chain and re-reading the signed-in account; the upgrade carries the
 * session cookie once and the connection is authorised for its lifetime. Logs and metrics
 * are genuinely one-way and stay on SSE.
 *
 * <p>Renders {@code features/files/TerminalPage.tsx}.
 */
@Controller
public class TerminalController {

    private final AuthorizeTerminal authorize;
    private final OpenTerminal open;
    private final FilesSettings settings;

    public TerminalController(AuthorizeTerminal authorize, OpenTerminal open,
                              FilesSettings settings) {
        this.authorize = authorize;
        this.open = open;
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
     *
     * <p>The shell exists before anything connects to it, and the panel buffers what it
     * prints in the meantime. A browser that never brings its socket - a tab closed in that
     * second - loses the shell to the reaper rather than leaving a process running as the
     * customer's application until the node's idle timeout.
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
}
