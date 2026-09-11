package lhqm.furimeo.wisper.files;

import lhqm.furimeo.wisper.proto.v1.UploadAck;

/**
 * The node's acknowledgement of one chunk.
 *
 * <p>The client waits for this before releasing the chunk from memory, which is what stops a
 * phone from buffering a 2 GB file it cannot hold ({@code files.proto}).
 *
 * @param nextOffset      where the node wants the next chunk. Usually the end of this one;
 *                        different when the node already had a later chunk from an earlier
 *                        attempt and is telling the client to skip ahead
 * @param nextChunkIndex  the same thing as an index, so the client does not divide
 * @param complete        every byte is on the node. The client's cue to call complete, which
 *                        is what actually assembles the file
 */
public record ChunkReceipt(
        String sessionId,
        long chunkIndex,
        long receivedBytes,
        long totalBytes,
        long nextOffset,
        long nextChunkIndex,
        boolean complete) {

    /**
     * @param sessionId the id the browser minted, not the prefixed one the node answered in
     */
    public static ChunkReceipt of(String sessionId, UploadAck ack, UploadTicket ticket) {
        long next = ack.getNextOffset();
        return new ChunkReceipt(
                sessionId,
                ack.getChunkIndex(),
                ack.getReceivedBytes(),
                ticket.totalBytes(),
                next,
                ticket.chunkSize() <= 0 ? 0 : next / ticket.chunkSize(),
                ticket.totalBytes() > 0 && ack.getReceivedBytes() >= ticket.totalBytes());
    }

    /** How far along the upload is, for a progress bar. */
    public int percent() {
        if (totalBytes <= 0) {
            return 100;
        }
        return (int) Math.min(100, receivedBytes * 100 / totalBytes);
    }
}
