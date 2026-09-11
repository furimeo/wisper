package lhqm.furimeo.wisper.org;

/**
 * A well-formed request this organization's rules do not allow.
 *
 * <p>Distinct from Bean Validation, which catches a blank name, and from
 * {@link PermissionDenied}, which catches the wrong role. This is the third category: the
 * form is filled in correctly and the caller is entitled to submit it, and the answer is
 * still no - that slug is taken, nobody has signed up with that address, that is the last
 * owner.
 *
 * <p>It carries the name of the input that was wrong, so the controller can render the
 * message underneath that field rather than as a banner at the top of a form the customer
 * then has to re-read. Use {@code null} for a rule that belongs to no single field.
 */
public class RequestRejected extends RuntimeException {

    private final String field;

    /**
     * @param field   the {@code name} attribute of the input at fault, or null
     * @param message one sentence, addressed to the customer
     */
    public RequestRejected(String field, String message) {
        super(message);
        this.field = field;
    }

    /** The input at fault, or null when the rule belongs to the form as a whole. */
    public String field() {
        return field;
    }
}
