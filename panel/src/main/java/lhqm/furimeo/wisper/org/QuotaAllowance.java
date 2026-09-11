package lhqm.furimeo.wisper.org;

/**
 * What an organization is allowed of one resource, and how much of it is gone.
 *
 * <p>Returned so a screen can draw a bar without asking twice and without doing the
 * subtraction differently on each page. Also what a caller reads before offering an
 * action it knows will be refused.
 *
 * @param resource which limit this is
 * @param limit    the effective ceiling, already resolved through the override; zero is
 *                 a real answer and means "not on this plan"
 * @param used     how much is spoken for right now
 * @param source   where the number came from, so the plan screen can say "raised for
 *                 this organization" instead of showing a figure that matches no plan
 */
public record QuotaAllowance(QuotaResource resource, long limit, long used, QuotaSource source) {

    public QuotaAllowance {
        if (resource == null || source == null) {
            throw new IllegalArgumentException("A quota allowance needs a resource and a source");
        }
        if (limit < 0 || used < 0) {
            throw new IllegalArgumentException("Quota figures are never negative");
        }
    }

    /** How much is left, floored at zero - usage can exceed a limit that was lowered. */
    public long remaining() {
        return Math.max(0, limit - used);
    }

    /**
     * Whether one more request of this size would fit.
     *
     * <p>Written as a subtraction rather than as {@code used + amount <= limit} because
     * the addition can overflow on a byte figure and the overflow answers "yes". Both
     * {@code limit} and {@code used} are non-negative, so {@code limit - used} cannot
     * overflow, and the comparison is exact for every value a caller can construct.
     */
    public boolean permits(long amount) {
        return amount <= limit - used;
    }

    /** Where the effective limit came from. */
    public enum QuotaSource {

        /** A live {@code quota_override} row for this organization. */
        ORGANIZATION_OVERRIDE,

        /** The {@code quota} row on the organization's plan. */
        PLAN,

        /**
         * Neither exists.
         *
         * <p>The limit is zero, never unlimited. Reading a missing row as "no limit"
         * turns forgetting to seed a plan into an unmetered platform, which is the one
         * mistake this whole mechanism exists to prevent.
         */
        UNSET
    }
}
