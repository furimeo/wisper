package lhqm.furimeo.wisper.files;

import java.time.Instant;
import java.util.List;

import com.google.protobuf.Timestamp;

import lhqm.furimeo.wisper.proto.v1.ByteRange;
import lhqm.furimeo.wisper.proto.v1.UploadState;

/**
 * What the node already has of an upload, and where to carry on from.
 *
 * <p>This is the answer that makes "resume" mean something. A customer on mobile data loses
 * signal in a lift; the browser reconnects, asks this, and continues from
 * {@link #nextOffset()} instead of starting a 300 MB file again.
 *
 * <p>Ranges rather than a count, because chunks can be uploaded in parallel and a dropped
 * connection leaves holes rather than a clean prefix. The client resumes at the first gap,
 * which is what {@link #nextOffset()} computes, and can fill the rest in any order.
 *
 * @param known        false when the node has never heard of this session: the sweeper took
 *                     it, or it was started against a different node. The browser starts
 *                     again from zero rather than showing an error
 * @param received     coalesced and sorted, half-open like every other range in this
 *                     contract
 * @param expiresAt    when the partial upload will be swept, so the UI can warn rather than
 *                     the customer finding the resume gone
 * @param chunkSize    the size the client must cut the file into. Echoed on every answer so
 *                     a client that reloaded does not have to remember it
 */
public record UploadProgress(
        String sessionId,
        boolean known,
        long totalBytes,
        long receivedBytes,
        List<Range> received,
        long nextOffset,
        long chunkSize,
        Instant expiresAt,
        boolean complete) {

    /** Half-open: {@code start} included, {@code endExclusive} not. */
    public record Range(long start, long endExclusive) {

        static Range of(ByteRange range) {
            return new Range(range.getStart(), range.getEndExclusive());
        }
    }

    public UploadProgress {
        received = received == null ? List.of() : List.copyOf(received);
    }

    /** The node's answer, translated. */
    public static UploadProgress of(UploadState state, long chunkSize) {
        List<Range> ranges = state.getReceivedList().stream().map(Range::of).toList();
        long total = state.getTotalBytes();
        long next = firstGap(ranges);
        return new UploadProgress(
                state.getSessionId(),
                state.getKnown(),
                total,
                state.getReceivedBytes(),
                ranges,
                next,
                chunkSize,
                state.hasExpiresAt() ? instant(state.getExpiresAt()) : null,
                total > 0 && state.getReceivedBytes() >= total && next >= total);
    }

    /** Nothing is on the node: a fresh session, or one that has been swept. */
    public static UploadProgress unknown(String sessionId, long totalBytes, long chunkSize) {
        return new UploadProgress(sessionId, false, totalBytes, 0, List.of(), 0, chunkSize, null,
                false);
    }

    /** Which chunk index the client should send next, given the chunk size it was told. */
    public long nextChunkIndex() {
        return chunkSize <= 0 ? 0 : nextOffset / chunkSize;
    }

    /**
     * The start of the first hole.
     *
     * <p>Ranges arrive coalesced and sorted, so the first one that does not begin where the
     * previous ended is the gap. Walking them rather than trusting {@code receivedBytes}
     * matters: a client that uploaded chunks 0, 1 and 7 has received bytes covering three
     * chunks and a gap starting at chunk 2.
     */
    private static long firstGap(List<Range> ranges) {
        long cursor = 0;
        for (Range range : ranges) {
            if (range.start() > cursor) {
                return cursor;
            }
            cursor = Math.max(cursor, range.endExclusive());
        }
        return cursor;
    }

    private static Instant instant(Timestamp at) {
        return Instant.ofEpochSecond(at.getSeconds(), at.getNanos());
    }
}
