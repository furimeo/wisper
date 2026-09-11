package lhqm.furimeo.wisper.deploy;

/**
 * What set a deployment going. Mirrors the {@code deployment_trigger_known} CHECK.
 *
 * <p>Separate from {@link DeploymentSource}, which says where the bytes came from, and
 * the two genuinely vary independently: a customer can press the button on a repository
 * (MANUAL + GIT), a push can redeploy an image (GIT_PUSH + IMAGE), and a rollback reuses
 * whatever the deployment it is undoing used.
 *
 * <p>It is the column an operator filters on when a service redeployed at three in the
 * morning and nobody admits to it.
 */
public enum DeploymentTrigger {

    /** Somebody pressed deploy in the panel. */
    MANUAL,

    /** A Git provider posted to {@code /webhooks/**} and the signature checked out. */
    GIT_PUSH,

    /** A previous release was put back. The new row names the one it undid. */
    ROLLBACK,

    /** A program called {@code /api/v1/**} with a scoped token. */
    API,

    /** The platform started it: a retry, or a redeploy a policy asked for. */
    SCHEDULED;

    /** The phrase the deployment list puts under the sequence number. */
    public String label() {
        return switch (this) {
            case MANUAL -> "Manual";
            case GIT_PUSH -> "Git push";
            case ROLLBACK -> "Rollback";
            case API -> "API";
            case SCHEDULED -> "Scheduled";
        };
    }
}
