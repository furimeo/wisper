package lhqm.furimeo.wisper.auth;

/**
 * An account-management request was refused, and this says which input was at fault.
 *
 * <p>One exception rather than six, because every caller does the same thing with it:
 * puts {@link #field()} and {@link #getMessage()} into
 * {@code InertiaFlash.errors(flash, Map.of(...))} and redirects back to the form. The
 * field name is the {@code name} attribute of the input, which is the contract
 * docs/contracts/panel-http.md sets for error props - so the message lands under the
 * control that produced it with no translation table in between.
 *
 * <p>Unchecked, so a use-case can refuse on its first line and stop reading.
 *
 * <p>This is not a Spring Security {@code AuthenticationException}. Those describe a
 * failed sign-in and are handled by the filter chain; this describes a form the signed-in
 * person filled in wrongly, and is handled by a controller.
 */
public class CredentialRejected extends RuntimeException {

    private final String field;

    public CredentialRejected(String field, String message) {
        super(message);
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException(
                    "A rejection names the input that caused it, so the form can show it there");
        }
        this.field = field;
    }

    /** Which form input this belongs under. */
    public String field() {
        return field;
    }

    /** Reads better than the constructor at a call site that is already three lines long. */
    public static CredentialRejected of(String field, String message) {
        return new CredentialRejected(field, message);
    }
}
