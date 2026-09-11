package lhqm.furimeo.wisper.grpc;

import java.util.ArrayList;
import java.util.List;

import io.grpc.stub.StreamObserver;
import lhqm.furimeo.wisper.proto.v1.PanelMessage;

/**
 * A {@link StreamObserver} that remembers what the panel wrote to it, and can be made to
 * fail the way a dead transport does.
 *
 * <p>The failing mode is the point. gRPC's real observer throws on a write after the peer
 * has gone, and the behaviour that matters - one exception type, every waiting command
 * released - is only exercised by a stream that throws.
 */
final class RecordingStream implements StreamObserver<PanelMessage> {

    private final List<PanelMessage> written = new ArrayList<>();
    private boolean broken;
    private Throwable error;
    private boolean completed;

    List<PanelMessage> written() {
        return List.copyOf(written);
    }

    PanelMessage last() {
        return written.get(written.size() - 1);
    }

    Throwable error() {
        return error;
    }

    boolean isCompleted() {
        return completed;
    }

    /** From here on, writing behaves like writing to a connection that has gone. */
    void breakTransport() {
        broken = true;
    }

    @Override
    public void onNext(PanelMessage message) {
        if (broken) {
            throw new IllegalStateException("call already closed");
        }
        written.add(message);
    }

    @Override
    public void onError(Throwable failure) {
        error = failure;
    }

    @Override
    public void onCompleted() {
        completed = true;
    }
}
