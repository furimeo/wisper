package lhqm.furimeo.wisper.auth;

/**
 * Why a browser session stopped being usable. Mirrors the
 * {@code session_revoked_reason_known} CHECK, value for value.
 *
 * <p>Stored rather than inferred because "you were signed out" and "you were signed out
 * because somebody changed your password" are different messages, and the second one is
 * the one a person needs when it was not them.
 */
public enum SessionRevocationReason {

    /** The person pressed sign out in this browser. */
    SIGNED_OUT,

    /** The person pressed "sign out everywhere" from another browser. */
    SIGNED_OUT_EVERYWHERE,

    /** The password changed, so every session that predates the change is void. */
    PASSWORD_CHANGED,

    /** The account was suspended while this session was live. */
    ACCOUNT_SUSPENDED,

    /** The session outlived {@code expires_at} and the sweep caught it. */
    EXPIRED,

    /** An operator ended it from the admin screens. */
    ADMIN
}
