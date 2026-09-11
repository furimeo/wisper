package lhqm.furimeo.wisper.files;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * The panel is not tracking this upload session.
 *
 * <p>Not an error the customer has to see. It happens for two ordinary reasons - the panel
 * restarted, or the session was swept after being idle - and the client's answer to both is
 * the same: call {@code POST /services/{id}/files/uploads} again with the metadata it still
 * has, which asks the node what it holds and resumes from there.
 *
 * <p>{@code 409 Conflict} rather than {@code 404}, because the resource the client named is
 * not missing so much as no longer in the state the client believes. A 404 on a chunk would
 * be indistinguishable from a mistyped URL, and the client's recovery differs.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class UploadSessionUnknown extends RuntimeException {

    private final String sessionId;

    public UploadSessionUnknown(String sessionId) {
        super("Upload session " + sessionId + " is not being tracked. Start it again to resume; "
                + "whatever the node already has will be reported and reused.");
        this.sessionId = sessionId;
    }

    public String sessionId() {
        return sessionId;
    }
}
