package lhqm.furimeo.wisper.backup;

import java.util.UUID;

/**
 * One thing a customer could point a backup policy at, as the create form offers it.
 *
 * <p>The form needs this because a policy's target is a bare id in the schema and a person
 * cannot pick one of those. Offering only what exists is also what stops the most common
 * failure of a form like this: a policy created against something that was deleted last
 * week, which fails on its first run at three in the morning.
 *
 * @param eligible    false for a volume whose {@code backup_enabled} is off. Shown greyed
 *                    out with the reason rather than hidden, because a customer looking for
 *                    a volume that is missing from a list has no way to find out why
 * @param policyCount how many policies already cover it. Not a refusal - two policies with
 *                    different retention to two destinations is a real arrangement - but it
 *                    is the thing to say before somebody makes a duplicate by accident
 */
public record BackupTargetOption(
        BackupTargetKind kind,
        UUID id,
        String label,
        boolean eligible,
        long policyCount) {
}
