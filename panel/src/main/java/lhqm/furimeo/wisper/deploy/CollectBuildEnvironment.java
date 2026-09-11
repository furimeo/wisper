package lhqm.furimeo.wisper.deploy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import lhqm.furimeo.wisper.crypto.SecretCipher;
import lhqm.furimeo.wisper.proto.v1.EnvVar;

/**
 * The variables the ephemeral build container gets, and only those.
 *
 * <p>Build-time and runtime environments are deliberately different sets. A static site
 * has no process, so a runtime variable would go nowhere; what it needs is the API base
 * URL baked into its bundle at build time. Going the other way, a runtime database
 * password handed to a build is a secret that ends up in a public JavaScript file, and
 * mixing the two makes that impossible to avoid - which is why {@code env_var} and
 * {@code secret} both carry {@code build_time} and why this reads only the rows that set
 * it.
 *
 * <p>Secrets are decrypted here because the node cannot: the key never leaves the panel.
 * They are marked {@code secret} on the wire, which is the node's instruction not to put
 * the value in a log line, an error message or any status it sends back.
 *
 * <p>The two tables belong to {@code service}, and this reads them without going through
 * it. That is deliberate and narrow: the ownership rule in schema.md §2 is about who
 * <em>writes</em> a column, there is no port for "the build environment", and a read that
 * needs one decrypt and one filter is not worth inverting a package dependency for.
 */
@Component
public class CollectBuildEnvironment {

    private static final String PLAIN = """
            SELECT name, value FROM env_var
             WHERE service_id = :serviceId AND build_time
             ORDER BY name
            """;

    private static final String ENCRYPTED = """
            SELECT name, value FROM secret
             WHERE service_id = :serviceId AND build_time
             ORDER BY name
            """;

    private final JdbcClient jdbc;
    private final SecretCipher cipher;

    public CollectBuildEnvironment(JdbcClient jdbc, SecretCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    /**
     * Every build-time variable for a service, plain ones first.
     *
     * <p>A repeated list rather than a map, matching {@code Workload.env}: the node hashes
     * the spec to detect drift and protobuf map ordering is not deterministic, so a map
     * would make an unchanged environment look changed on every pass.
     */
    public List<EnvVar> forService(UUID serviceId) {
        List<EnvVar> environment = new ArrayList<>();
        jdbc.sql(PLAIN)
                .param("serviceId", serviceId)
                .query((row, number) -> EnvVar.newBuilder()
                        .setName(row.getString("name"))
                        .setValue(row.getString("value"))
                        .setSecret(false)
                        .build())
                .list()
                .forEach(environment::add);
        jdbc.sql(ENCRYPTED)
                .param("serviceId", serviceId)
                .query((row, number) -> EnvVar.newBuilder()
                        .setName(row.getString("name"))
                        .setValue(cipher.decrypt(row.getString("value")))
                        .setSecret(true)
                        .build())
                .list()
                .forEach(environment::add);
        return List.copyOf(environment);
    }
}
