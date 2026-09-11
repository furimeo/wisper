package lhqm.furimeo.wisper.placement;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lhqm.furimeo.wisper.crypto.SecretCipher;
import lhqm.furimeo.wisper.proto.v1.EnvVar;

/**
 * The environment of every service on one node, plain values and secrets together.
 *
 * <p>Two tables, one list. {@code env_var} and {@code secret} are separate rows because the
 * difference changes how a value is stored, whether a GET returns it and what an audit
 * entry may contain (schema.md §3) - but a process does not have two environments, so by
 * the time this reaches a spec it is one sorted list with a flag on each entry.
 *
 * <p>This is the one place in the panel that decrypts a secret. The value is read, unsealed
 * and put straight on the wire to the node that needs it; nothing here logs it, returns it
 * to a browser or keeps it. The {@code secret} flag travels with it so the node knows not
 * to put it in a log line either, rather than having to guess from the name - which is what
 * a platform does when it wants {@code DATABASE_PASSWORD} redacted and {@code DB_PW} not.
 *
 * <p>Sorted by name, and the sort is load-bearing. {@code Workload.env} is a repeated field
 * rather than a map precisely so the spec has a stable byte-for-byte form, and a list that
 * came back in insertion order would make every unrelated write look like drift to a node
 * hashing what it was given.
 */
@Component
public class LoadServiceEnvironment {

    private static final String SQL = """
            SELECT service_id, name, value, false AS is_secret
              FROM env_var
             WHERE service_id IN (:serviceIds)
             UNION ALL
            SELECT service_id, name, value, true AS is_secret
              FROM secret
             WHERE service_id IN (:serviceIds)
            """;

    private final JdbcClient jdbc;
    private final SecretCipher cipher;

    public LoadServiceEnvironment(JdbcClient jdbc, SecretCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    /** Environment entries grouped by service, each list sorted by variable name. */
    @Transactional(readOnly = true)
    public Map<UUID, List<EnvVar>> forServices(Collection<UUID> serviceIds) {
        if (serviceIds == null || serviceIds.isEmpty()) {
            return Map.of();
        }
        List<Entry> rows = jdbc.sql(SQL)
                .param("serviceIds", List.copyOf(serviceIds))
                .query(LoadServiceEnvironment::map)
                .list();

        Map<UUID, List<Entry>> byService = new LinkedHashMap<>();
        for (Entry entry : rows) {
            byService.computeIfAbsent(entry.serviceId(), key -> new ArrayList<>()).add(entry);
        }

        Map<UUID, List<EnvVar>> environment = new LinkedHashMap<>();
        for (Map.Entry<UUID, List<Entry>> service : byService.entrySet()) {
            List<Entry> entries = service.getValue();
            entries.sort(Comparator.comparing(Entry::name));
            List<EnvVar> variables = new ArrayList<>(entries.size());
            for (Entry entry : entries) {
                variables.add(EnvVar.newBuilder()
                        .setName(entry.name())
                        .setValue(entry.secret() ? cipher.decrypt(entry.value()) : entry.value())
                        .setSecret(entry.secret())
                        .build());
            }
            environment.put(service.getKey(), List.copyOf(variables));
        }
        return Map.copyOf(environment);
    }

    private static Entry map(ResultSet row, int rowNumber) throws SQLException {
        return new Entry(row.getObject("service_id", UUID.class), row.getString("name"),
                row.getString("value"), row.getBoolean("is_secret"));
    }

    /**
     * One row from either table before it is unsealed.
     *
     * <p>Deliberately not printed anywhere: {@code value} is a ciphertext envelope for a
     * secret, and a record whose generated {@code toString} carries one ends up in an
     * exception message the first time something goes wrong.
     */
    private record Entry(UUID serviceId, String name, String value, boolean secret) {

        @Override
        public String toString() {
            return "Entry[" + name + " on service " + serviceId + "]";
        }
    }
}
