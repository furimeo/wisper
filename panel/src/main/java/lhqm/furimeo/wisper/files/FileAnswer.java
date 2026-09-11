package lhqm.furimeo.wisper.files;

import lhqm.furimeo.wisper.proto.v1.DirectoryListing;
import lhqm.furimeo.wisper.proto.v1.DirectorySize;
import lhqm.furimeo.wisper.proto.v1.FileEvent;
import lhqm.furimeo.wisper.proto.v1.FileInfo;
import lhqm.furimeo.wisper.proto.v1.OperationDone;
import lhqm.furimeo.wisper.proto.v1.UploadAck;
import lhqm.furimeo.wisper.proto.v1.UploadState;

/**
 * The terminal event of a file operation, with the answer the caller was expecting.
 *
 * <p>{@code FileEvent} is a {@code oneof} of eight results because one stream carries every
 * operation for a node. A caller that asked for a listing and got an {@code UploadAck} has
 * found a bug in the node or a correlation failure in the transport, and either way what it
 * must not do is quietly treat the default instance as an empty directory - a file manager
 * that shows a customer's populated volume as empty is worse than one that refuses to load.
 *
 * <p>So each accessor names what it wanted and says what it got instead. An error event
 * never reaches here: {@code NodeFiles} turns that into {@link FileOperationFailed} before
 * returning.
 */
public record FileAnswer(FileEvent event) {

    public static FileAnswer of(FileEvent event) {
        return new FileAnswer(event);
    }

    /** One page of a directory. */
    public DirectoryListing listing() {
        expect(FileEvent.ResultCase.LISTING);
        return event.getListing();
    }

    /** One file's metadata. */
    public FileInfo info() {
        expect(FileEvent.ResultCase.INFO);
        return event.getInfo();
    }

    /** One chunk accepted. */
    public UploadAck ack() {
        expect(FileEvent.ResultCase.ACK);
        return event.getAck();
    }

    /** What the node holds of a partial upload. */
    public UploadState state() {
        expect(FileEvent.ResultCase.STATE);
        return event.getState();
    }

    /** How big a directory is. */
    public DirectorySize size() {
        expect(FileEvent.ResultCase.SIZE);
        return event.getSize();
    }

    /** An operation with no payload finished. */
    public OperationDone done() {
        expect(FileEvent.ResultCase.DONE);
        return event.getDone();
    }

    private void expect(FileEvent.ResultCase wanted) {
        if (event.getResultCase() != wanted) {
            throw new IllegalStateException("The node answered request " + event.getRequestId()
                    + " with " + event.getResultCase() + " where " + wanted + " was expected");
        }
    }
}
