package lhqm.furimeo.wisper.files;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import lhqm.furimeo.wisper.proto.v1.ByteRange;
import lhqm.furimeo.wisper.proto.v1.UploadState;

/**
 * Where a resumed upload carries on from.
 *
 * <p>Ranges rather than a count is the whole point of the contract: chunks can be uploaded
 * in parallel and a dropped connection leaves holes, not a clean prefix. A client that
 * resumed at "bytes received" instead of "the first gap" would skip the hole and produce a
 * file that is the right length and wrong in the middle - which no checksum catches until
 * the very end, after the customer has uploaded the whole thing again.
 */
class UploadProgressTest {

    private static final long CHUNK = 8L * 1024 * 1024;

    @Test
    void aCleanPrefixResumesAtItsEnd() {
        UploadProgress progress = UploadProgress.of(state(24_000_000, range(0, 16_000_000)),
                CHUNK);

        assertThat(progress.nextOffset()).isEqualTo(16_000_000);
        assertThat(progress.known()).isTrue();
        assertThat(progress.complete()).isFalse();
    }

    @Test
    void aHoleLeftByParallelChunksIsWhereTheClientCarriesOn() {
        // Chunks 0, 1 and 7 landed; 2 to 6 did not. Received bytes cover three chunks, and
        // resuming on that number would skip the hole entirely.
        UploadProgress progress = UploadProgress.of(
                state(8 * CHUNK, range(0, 2 * CHUNK), range(7 * CHUNK, 8 * CHUNK)), CHUNK);

        assertThat(progress.nextOffset()).isEqualTo(2 * CHUNK);
        assertThat(progress.nextChunkIndex()).isEqualTo(2);
        assertThat(progress.complete()).isFalse();
    }

    @Test
    void nothingReceivedResumesAtZero() {
        UploadProgress progress = UploadProgress.of(state(1_000), CHUNK);

        assertThat(progress.nextOffset()).isZero();
        assertThat(progress.nextChunkIndex()).isZero();
    }

    @Test
    void everyByteReceivedIsReportedAsCompleteSoTheClientCallsCompleteRatherThanResending() {
        UploadProgress progress = UploadProgress.of(state(CHUNK, range(0, CHUNK)), CHUNK);

        assertThat(progress.complete()).isTrue();
        assertThat(progress.nextOffset()).isEqualTo(CHUNK);
    }

    @Test
    void aSessionTheNodeHasNeverHeardOfStartsAgainFromZero() {
        UploadProgress progress = UploadProgress.of(UploadState.newBuilder()
                .setSessionId("abc")
                .setKnown(false)
                .build(), CHUNK);

        assertThat(progress.known()).isFalse();
        assertThat(progress.nextOffset()).isZero();
    }

    @Test
    void overlappingRangesDoNotConfuseTheGapCalculation() {
        // The node coalesces, but a node that reports 0-10 and 5-20 must still resume at 20
        // rather than at 10.
        UploadProgress progress = UploadProgress.of(state(100, range(0, 10), range(5, 20)), CHUNK);

        assertThat(progress.nextOffset()).isEqualTo(20);
    }

    private static UploadState state(long total, ByteRange... ranges) {
        UploadState.Builder state = UploadState.newBuilder()
                .setSessionId("session")
                .setKnown(true)
                .setTotalBytes(total);
        long received = 0;
        for (ByteRange range : ranges) {
            state.addReceived(range);
            received += range.getEndExclusive() - range.getStart();
        }
        return state.setReceivedBytes(received).build();
    }

    private static ByteRange range(long start, long endExclusive) {
        return ByteRange.newBuilder().setStart(start).setEndExclusive(endExclusive).build();
    }
}
