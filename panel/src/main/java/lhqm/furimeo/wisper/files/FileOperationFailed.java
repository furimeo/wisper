package lhqm.furimeo.wisper.files;

import lhqm.furimeo.wisper.proto.v1.FileError;
import lhqm.furimeo.wisper.proto.v1.FileErrorCode;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * The node refused or could not complete a file operation.
 *
 * <p>Carries the {@link FileError} rather than only its text, because the code decides
 * what happens next and a sentence cannot be branched on:
 *
 * <ul>
 * <li>{@link FileErrorCode#FILE_ERROR_CODE_PATH_ESCAPES_ROOT} is never shown as a hint.
 *     It is an attack or a bug - a customer cannot type it by accident, because the
 *     browser sends paths relative to a root it was given - so the caller records an
 *     audit entry and answers with a flat refusal.</li>
 * <li>{@link FileErrorCode#FILE_ERROR_CODE_QUOTA_EXCEEDED} and
 *     {@link FileErrorCode#FILE_ERROR_CODE_TOO_LARGE} are things a customer can act on,
 *     and the file manager offers the action.</li>
 * <li>{@link FileErrorCode#FILE_ERROR_CODE_UNKNOWN_SESSION} means the resumable upload
 *     the browser was continuing has been swept; the client starts again from zero
 *     rather than showing an error.</li>
 * <li>{@link FileErrorCode#FILE_ERROR_CODE_CHECKSUM_MISMATCH} on a chunk is a retry, not
 *     a failure - mobile networks corrupt more than they are given credit for.</li>
 * </ul>
 *
 * <p>{@code detail} was written by the node for a person to read, so it is safe to put
 * in front of a customer. {@code path} is relative to the root and says which entry an
 * archive extraction stopped on.
 */
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class FileOperationFailed extends RuntimeException {

    private final FileError error;

    public FileOperationFailed(FileError error) {
        super(error.getCode() + " on \"" + error.getPath() + "\": " + error.getDetail());
        this.error = error;
    }

    /** What went wrong, in the form the node reported it. */
    public FileError error() {
        return error;
    }

    /** The code to branch on. */
    public FileErrorCode code() {
        return error.getCode();
    }

    /** Safe to show the customer; written by the node for a person. */
    public String detail() {
        return error.getDetail();
    }

    /** Which path the operation was on when it failed, relative to the root. */
    public String path() {
        return error.getPath();
    }

    /**
     * Whether this is the one error that is never a customer's mistake.
     *
     * <p>True means the caller writes an audit entry with outcome
     * {@link lhqm.furimeo.wisper.audit.AuditOutcome#DENIED} before it answers.
     */
    public boolean isTraversalAttempt() {
        return error.getCode() == FileErrorCode.FILE_ERROR_CODE_PATH_ESCAPES_ROOT;
    }
}
