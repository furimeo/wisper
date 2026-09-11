package lhqm.furimeo.wisper.auth;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What a bearer token is allowed to do. Mirrors the {@code api_token_scopes_known} CHECK,
 * value for value: the database refuses a scope this enum does not name, so a token can
 * never ask for a permission nothing in the panel knows how to check.
 *
 * <p>The wire form is {@code resource:verb} because that is what a person reads in a
 * token list and pastes into a CI configuration. The Java name is the same value shouted,
 * which keeps the two mechanically derivable from each other and stops a rename on one
 * side from quietly widening a token on the other.
 *
 * <p>A {@code :write} scope does not imply the matching {@code :read}. Both are granted
 * explicitly, because a token that may deploy but not list is a legitimate thing to want
 * and an implication rule is a rule somebody has to remember exists.
 */
public enum ApiScope {

    PROJECTS_READ("projects:read"),
    PROJECTS_WRITE("projects:write"),
    SERVICES_READ("services:read"),
    SERVICES_WRITE("services:write"),
    DEPLOYMENTS_READ("deployments:read"),
    DEPLOYMENTS_WRITE("deployments:write"),
    DOMAINS_READ("domains:read"),
    DOMAINS_WRITE("domains:write"),
    DATABASES_READ("databases:read"),
    DATABASES_WRITE("databases:write"),
    FILES_READ("files:read"),
    FILES_WRITE("files:write"),
    BACKUPS_READ("backups:read"),
    BACKUPS_WRITE("backups:write"),
    METRICS_READ("metrics:read"),
    NODES_READ("nodes:read"),
    NODES_WRITE("nodes:write");

    /** Prefix Spring Security expects on a non-role authority. */
    public static final String AUTHORITY_PREFIX = "SCOPE_";

    private final String wireName;

    ApiScope(String wireName) {
        this.wireName = wireName;
    }

    /** The form stored in {@code api_token.scopes} and shown to the customer. */
    public String wireName() {
        return wireName;
    }

    /** The authority a request carries once the token has been accepted. */
    public String authority() {
        return AUTHORITY_PREFIX + wireName;
    }

    /**
     * Whether this scope only reads.
     *
     * <p>What the token form uses to offer "read everything" as one checkbox, and what an
     * audit entry reads to say whether a token could have changed anything.
     */
    public boolean isReadOnly() {
        return wireName.endsWith(":read");
    }

    /**
     * The two scopes that touch {@code nodes} are the platform's own, not a customer's.
     *
     * <p>A customer token asking for one is refused at issue time rather than being
     * issued and then failing on every call, which is the version of the mistake that
     * gets reported as "the API is broken".
     */
    public boolean isPlatformOnly() {
        return this == NODES_READ || this == NODES_WRITE;
    }

    /** The scope with this wire name, or empty when nothing answers to it. */
    public static Optional<ApiScope> ofWireName(String wireName) {
        return Arrays.stream(values()).filter(scope -> scope.wireName.equals(wireName)).findFirst();
    }

    /**
     * Reads a stored {@code text[]} back.
     *
     * <p>Unknown values are dropped rather than throwing: the CHECK constraint means one
     * can only appear if a migration removed a scope this code still holds a token for,
     * and in that case the token should lose that permission, not stop working.
     */
    public static Set<ApiScope> ofWireNames(String[] wireNames) {
        Set<ApiScope> scopes = new LinkedHashSet<>();
        if (wireNames == null) {
            return scopes;
        }
        for (String name : wireNames) {
            ofWireName(name).ifPresent(scopes::add);
        }
        return scopes;
    }

    /** The array to store in {@code api_token.scopes}, in declaration order. */
    public static String[] toWireNames(Collection<ApiScope> scopes) {
        return Arrays.stream(values())
                .filter(scopes::contains)
                .map(ApiScope::wireName)
                .toArray(String[]::new);
    }

    /** Every scope, in declaration order, for the checkbox list on the token form. */
    public static List<String> allWireNames() {
        return Arrays.stream(values()).map(ApiScope::wireName).toList();
    }
}
