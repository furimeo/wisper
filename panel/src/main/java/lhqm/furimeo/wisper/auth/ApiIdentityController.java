package lhqm.furimeo.wisper.auth;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/me} - who this token is, and what it may do.
 *
 * <p>Every API client needs this before it needs anything else: it is how a script
 * verifies the token it was given works, how a person debugging a pipeline finds out
 * which of their four tokens is in the environment, and how a client discovers whether it
 * has the scope for the call it is about to make instead of finding out from a 403 in the
 * middle of a deployment.
 *
 * <p>It requires no scope. A token is always allowed to describe itself; requiring a
 * permission to read one's own identity would mean a token issued with the wrong scopes
 * could not even report that.
 *
 * <p>A {@code @RestController}, not an Inertia page. Everything under {@code /api/v1} is
 * called by programs, answers JSON, authenticates per request and is CSRF-exempt; the
 * browser client never comes here, because Inertia is what removes the need for it to.
 */
@RestController
public class ApiIdentityController {

    @GetMapping("/api/v1/me")
    public ResponseEntity<Map<String, Object>> me(
            @AuthenticationPrincipal ApiTokenPrincipal principal) {

        if (principal == null) {
            /*
             * Authenticated, but with a browser session rather than a token - the
             * argument resolver hands back null when the principal is a different type.
             * The answer is 401 rather than a description of the session: /api/v1 is the
             * token surface, and letting a session cookie work here would make it a
             * second, CSRF-exempt way in for a credential that was never meant to have
             * one.
             */
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "error", "unauthorized",
                    "message", "Authenticate with an API token: Authorization: Bearer wsp_..."));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accountId", principal.accountId());
        body.put("email", principal.accountEmail());
        body.put("platformRole", principal.role().name());
        body.put("tokenId", principal.tokenId());
        body.put("tokenName", principal.tokenName());
        // Null means the token acts across every organization its owner belongs to.
        body.put("organizationId", principal.organizationId());
        body.put("scopes", principal.scopes().stream().map(ApiScope::wireName).sorted().toList());
        return ResponseEntity.ok(body);
    }
}
