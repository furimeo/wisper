package lhqm.furimeo.wisper.database;

import java.util.Locale;

/**
 * Which database engine: PostgreSQL or MySQL.
 *
 * <p>The two are never interchangeable. The URI scheme a customer pastes into their
 * application, the port the container listens on, the encoding name the engine accepts
 * and the dump format a backup produces all differ, so every message and every row that
 * touches a database carries the engine rather than inferring it from an image name.
 *
 * <p>Mirrors the {@code database_engine_engine_known} CHECK, value for value, and the
 * {@code DatabaseEngine} enum in {@code database.proto}. The proto type is referenced by
 * its fully-qualified name below rather than imported: the aggregate in this package is
 * also called {@link DatabaseEngine}, and an import that shadowed it inside one file
 * would be a trap for whoever reads that file next.
 *
 * @see DatabaseEngine the row, which holds one of these
 */
public enum EngineKind {

    /** PostgreSQL. Encoding names are upper-case, {@code UTF8} without the hyphen. */
    POSTGRES("PostgreSQL", "postgresql", 5432, "UTF8"),

    /** MySQL. {@code utf8mb4} rather than {@code utf8}, which is only three bytes wide. */
    MYSQL("MySQL", "mysql", 3306, "utf8mb4");

    private final String label;
    private final String uriScheme;
    private final int defaultPort;
    private final String defaultEncoding;

    EngineKind(String label, String uriScheme, int defaultPort, String defaultEncoding) {
        this.label = label;
        this.uriScheme = uriScheme;
        this.defaultPort = defaultPort;
        this.defaultEncoding = defaultEncoding;
    }

    /** What a person is shown: "PostgreSQL", not "POSTGRES". */
    public String label() {
        return label;
    }

    /** The scheme of the connection URI, which is what most client libraries parse. */
    public String uriScheme() {
        return uriScheme;
    }

    /**
     * The port the engine listens on by default.
     *
     * <p>Used for the first instance on a node, so the obvious case reads normally in
     * {@code docker ps}. Anything after that is allocated by {@link AllocateEnginePort},
     * because {@code database_engine_node_port_key} lets one port be bound once per node.
     */
    public int defaultPort() {
        return defaultPort;
    }

    /** The character encoding a new database gets when the customer expresses no view. */
    public String defaultEncoding() {
        return defaultEncoding;
    }

    /**
     * The superuser the node uses to create customer databases and roles.
     *
     * <p>Not {@code postgres} or {@code root}: the platform's administrative login is
     * distinguishable in an engine's own logs from whatever the base image ships with.
     */
    public String adminUsername() {
        return "wisper_admin";
    }

    /** The value stored in {@code database_engine.engine}. */
    public String stored() {
        return name();
    }

    /** The wire value, for a spec or a command. */
    public lhqm.furimeo.wisper.proto.v1.DatabaseEngine toWire() {
        return switch (this) {
            case POSTGRES -> lhqm.furimeo.wisper.proto.v1.DatabaseEngine.DATABASE_ENGINE_POSTGRES;
            case MYSQL -> lhqm.furimeo.wisper.proto.v1.DatabaseEngine.DATABASE_ENGINE_MYSQL;
        };
    }

    /**
     * The engine a node named in a report.
     *
     * @throws IllegalArgumentException for {@code UNSPECIFIED} or a value added to the
     *         proto and not to this enum. No shrugging default: a report about an engine
     *         the panel cannot name is a report it must not act on.
     */
    public static EngineKind ofWire(lhqm.furimeo.wisper.proto.v1.DatabaseEngine wire) {
        return switch (wire) {
            case DATABASE_ENGINE_POSTGRES -> POSTGRES;
            case DATABASE_ENGINE_MYSQL -> MYSQL;
            default -> throw new IllegalArgumentException(
                    "No database engine for wire value " + wire);
        };
    }

    /**
     * Parses what a customer or an operator typed, case-insensitively.
     *
     * <p>Accepts the two names people actually use for PostgreSQL, because a form that
     * refuses {@code postgresql} is a form that gets filed as a bug.
     */
    public static EngineKind parse(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.strip().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "POSTGRES", "POSTGRESQL", "PGSQL" -> POSTGRES;
            case "MYSQL", "MARIADB" -> MYSQL;
            default -> null;
        };
    }
}
