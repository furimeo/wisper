package lhqm.furimeo.wisper.org;

import java.util.Locale;
import java.util.UUID;

/**
 * The organization is not allowed this much of that resource.
 *
 * <p>Unchecked, so a use-case can call {@code require} as its first line and stop
 * reading. The controller catches it, writes {@code InertiaFlash.failure} with
 * {@link #message()} and redirects back to the form, and the audit entry for the attempt
 * is recorded as {@link lhqm.furimeo.wisper.audit.AuditOutcome#DENIED} - a refusal is a
 * thing that happened and belongs in the trail.
 *
 * <p>It carries the numbers rather than only a sentence, so a screen can offer the right
 * next step: an organization at its plan's ceiling needs a bigger plan, one at zero
 * needs the feature enabled at all.
 */
public class QuotaExceeded extends RuntimeException {

    private final UUID organizationId;
    private final QuotaResource resource;
    private final long requested;
    private final QuotaAllowance allowance;

    public QuotaExceeded(UUID organizationId, QuotaResource resource, long requested,
                         QuotaAllowance allowance) {
        this(describe(resource, requested, allowance), organizationId, resource, requested,
                allowance);
    }

    private QuotaExceeded(String message, UUID organizationId, QuotaResource resource,
                          long requested, QuotaAllowance allowance) {
        super(message);
        this.organizationId = organizationId;
        this.resource = resource;
        this.requested = requested;
        this.allowance = allowance;
    }

    /**
     * The refusal a suspended organization gets, for every resource alike.
     *
     * <p>The same type, because every caller already catches it and a second exception
     * would be a second {@code catch} block for each of them to forget. A different
     * sentence, because "your plan does not include projects" is not true and sends the
     * customer to the wrong screen: they need to know the account is suspended and why.
     */
    public static QuotaExceeded suspended(UUID organizationId, QuotaResource resource,
                                          long requested, QuotaAllowance allowance,
                                          String reason) {
        String message = "This organization is suspended, so nothing new can be created"
                + (reason == null || reason.isBlank() ? "." : ": " + reason);
        return new QuotaExceeded(message, organizationId, resource, requested, allowance);
    }

    public UUID organizationId() {
        return organizationId;
    }

    public QuotaResource resource() {
        return resource;
    }

    /** How much was asked for, in the resource's own unit. */
    public long requested() {
        return requested;
    }

    /** The limit and the usage as they were when the request was refused. */
    public QuotaAllowance allowance() {
        return allowance;
    }

    /** The sentence to put in front of the customer. */
    public String message() {
        return getMessage();
    }

    private static String describe(QuotaResource resource, long requested,
                                   QuotaAllowance allowance) {
        String noun = resource.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        if (allowance.limit() == 0) {
            return "Your plan does not include " + noun + ".";
        }
        // Saturating, because used + requested is a byte figure on four of the thirteen
        // resources and an overflow here would print a negative number at the customer.
        long attempted = allowance.used() + requested;
        if (attempted < 0) {
            attempted = Long.MAX_VALUE;
        }
        return "That would take " + noun + " to " + attempted + " of " + allowance.limit()
                + " allowed on your plan.";
    }
}
