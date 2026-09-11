package lhqm.furimeo.wisper.files;

/**
 * What a keystroke, a resize or a close answers with.
 *
 * <p>Small on purpose: these are the most frequent requests in the panel, one per burst of
 * typing. What the client actually needs back is whether the shell is still there - the exit
 * arrives on the output stream, but a browser whose stream has dropped finds out here on the
 * next key it sends, which is sooner.
 *
 * @param exitCode meaningless until {@code finished}, as {@code TerminalSession} says
 */
public record TerminalAck(String sessionId, boolean finished, int exitCode, String exitReason) {

    /** The session as it stands after the frame was delivered. */
    public static TerminalAck of(TerminalSession session) {
        return new TerminalAck(session.sessionId(), session.isFinished(), session.exitCode(),
                session.exitReason());
    }

    /** The session is over, whether it was this request that ended it or something earlier. */
    public static TerminalAck closed(String sessionId) {
        return new TerminalAck(sessionId, true, 0, "closed from the panel");
    }
}
