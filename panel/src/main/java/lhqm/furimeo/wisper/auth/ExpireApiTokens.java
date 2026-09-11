package lhqm.furimeo.wisper.auth;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Revokes API tokens whose own expiry has passed.
 *
 * <p>{@link AuthenticateApiToken} already refuses an expired token, so nothing about
 * security depends on this running. What depends on it is the token list telling the
 * truth: a row that says "expires 3 March" and still shows as live on 4 March is a screen
 * a customer will trust when deciding whether to rotate something.
 *
 * <p>Rows are revoked, never deleted. {@code audit_log.api_token_id} is {@code SET NULL},
 * so deleting a token would anonymise every entry it ever produced - and an expired
 * deploy key is exactly the one somebody comes looking for afterwards.
 */
@Component
public class ExpireApiTokens {

    private static final Logger log = LoggerFactory.getLogger(ExpireApiTokens.class);

    private final ApiTokenRepository tokens;

    public ExpireApiTokens(ApiTokenRepository tokens) {
        this.tokens = tokens;
    }

    /** @return how many tokens were marked expired */
    @Transactional
    public int run() {
        int revoked = tokens.revokeExpired(Instant.now());
        if (revoked > 0) {
            log.info("Token sweep: {} API token(s) passed their expiry", revoked);
        }
        return revoked;
    }
}
