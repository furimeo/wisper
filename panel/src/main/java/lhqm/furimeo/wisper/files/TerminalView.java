package lhqm.furimeo.wisper.files;

/**
 * A newly opened shell, as the browser receives it.
 *
 * <p>{@code columns} and {@code rows} are what the PTY actually took, which is the size the
 * browser asked for after clamping. xterm.js has to be told: a terminal that believes it is
 * 200 columns wide while the PTY is 120 wraps every line in the wrong place, and the customer
 * sees a screen that redraws itself into nonsense.
 *
 * @param containerId the container the PTY lives in, so an operator can match a session in
 *                    the audit log to what Docker saw
 */
public record TerminalView(String sessionId, String containerId, int columns, int rows) {
}
