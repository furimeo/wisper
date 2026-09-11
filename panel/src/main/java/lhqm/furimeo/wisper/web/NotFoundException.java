package lhqm.furimeo.wisper.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when a record does not exist, or exists and does not belong to the caller.
 *
 * <p>Those two cases produce the same answer on purpose. Distinguishing them tells an
 * attacker which project ids are real, and a 403 on someone else's resource is exactly
 * the enumeration oracle a 404 avoids. Repositories return {@code Optional}; the
 * use-case turns an empty one into this.
 *
 * <p>Spring turns it into a forward to {@code /error}, where
 * {@link ErrorPageController} renders it as an ordinary page.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }

    /** For "no service 41 in project 7" style messages, which is most of them. */
    public static NotFoundException of(String what, Object id) {
        return new NotFoundException("No " + what + " with id " + id);
    }
}
