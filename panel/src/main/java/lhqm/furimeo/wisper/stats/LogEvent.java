package lhqm.furimeo.wisper.stats;

import java.time.Instant;

/**
 * One log event as the browser receives it.
 *
 * <p>A run of text and not a line. A container writes half a line and then thinks for ten
 * seconds; buffering until a newline arrives makes a build or a boot look hung, so what is
 * forwarded is whatever has arrived and the viewer appends it. Lines are the renderer's
 * problem, which is the only place that knows how wide the screen is.
 *
 * @param text         the decoded run of output, never null
 * @param at           the node's timestamp for the first byte in it
 * @param stderr       whether it came from the error stream, so a viewer can colour it
 * @param droppedBytes output the node discarded rather than block the process writing it.
 *                     Non-zero has to be shown: a customer told that lines are missing can
 *                     act, a customer shown a silent gap cannot (design §7.6)
 * @param end          the source ended - the container exited, the cron run returned - so
 *                     the viewer can stop showing a spinner instead of waiting for a
 *                     timeout it would read as a failure
 */
public record LogEvent(String text, Instant at, boolean stderr, long droppedBytes, boolean end) {

    public LogEvent {
        text = text == null ? "" : text;
    }

    /** Output. */
    public static LogEvent output(String text, Instant at, boolean stderr, long droppedBytes) {
        return new LogEvent(text, at, stderr, droppedBytes, false);
    }

    /** The last event on a subscription. */
    public static LogEvent ended(String trailing, Instant at, long droppedBytes) {
        return new LogEvent(trailing, at, false, droppedBytes, true);
    }
}
