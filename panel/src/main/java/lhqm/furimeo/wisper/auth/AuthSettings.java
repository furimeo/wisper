package lhqm.furimeo.wisper.auth;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Everything about signing in that an operator might reasonably want to change.
 *
 * <p>Bound by {@code @ConfigurationPropertiesScan} on {@code WisperApplication}, so
 * nothing central had to be edited to introduce it (docs/contracts/panel-configuration.md).
 * Every component carries a {@code @DefaultValue}, because record binding does not fall
 * back to a constructor default and a missing key would otherwise bind to zero and
 * surface months later as a lockout that never expires.
 *
 * @param maxFailedSignIns   wrong passwords in a row before the account is held shut.
 *                           Five is enough to survive a bad keyboard and few enough that
 *                           an online guessing attack gets nowhere
 * @param lockout            how long the account stays shut. Long enough to make guessing
 *                           pointless, short enough that a real person can wait it out
 *                           rather than opening a support ticket
 * @param sessionLifetime    the absolute ceiling on a browser session, independent of the
 *                           container's idle timeout. Matches
 *                           {@code server.servlet.session.timeout} by default
 * @param sessionTouchWindow how stale {@code session.last_seen_at} may get before it is
 *                           rewritten. Without it, every page load is a write
 * @param totpDriftSteps     thirty-second steps accepted either side of now. One is
 *                           ninety seconds of acceptance, which covers clock skew and
 *                           typing speed; raising it lengthens how long a code
 *                           shoulder-surfed off a screen stays usable
 * @param recoveryCodeCount  codes issued per batch
 * @param totpIssuer         the name that appears above the code in the authenticator
 *                           app. Set it per deployment, or two wisper installations look
 *                           identical in the app's list
 * @param finishedSessionRetention how long a revoked or expired session row is kept for
 *                           the sign-in history before the sweep deletes it
 * @param bootstrapAdminEmail the operator account created on a completely empty database,
 *                           because a panel nobody can sign in to has no way to create
 *                           the first account
 */
@ConfigurationProperties("wisper.auth")
public record AuthSettings(
        @DefaultValue("5") int maxFailedSignIns,
        @DefaultValue("15m") Duration lockout,
        @DefaultValue("7d") Duration sessionLifetime,
        @DefaultValue("60s") Duration sessionTouchWindow,
        @DefaultValue("1") int totpDriftSteps,
        @DefaultValue("10") int recoveryCodeCount,
        @DefaultValue("wisper") String totpIssuer,
        @DefaultValue("30d") Duration finishedSessionRetention,
        @DefaultValue("admin@wisper.local") String bootstrapAdminEmail) {

    public AuthSettings {
        if (maxFailedSignIns < 1) {
            throw new IllegalArgumentException(
                    "wisper.auth.max-failed-sign-ins must be at least 1; zero would lock every "
                    + "account on its first attempt");
        }
        if (totpDriftSteps < 0 || totpDriftSteps > 10) {
            throw new IllegalArgumentException(
                    "wisper.auth.totp-drift-steps is a small window either side of now; "
                    + totpDriftSteps + " steps is " + (totpDriftSteps * 30) + " seconds and "
                    + "stops being a second factor");
        }
        if (recoveryCodeCount < 1) {
            throw new IllegalArgumentException(
                    "wisper.auth.recovery-code-count must be at least 1, or enabling two-factor "
                    + "authentication would hand out no way back in");
        }
    }
}
