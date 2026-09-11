package lhqm.furimeo.wisper.database;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Everything about managed databases an operator might reasonably want to change.
 *
 * <p>Bound by {@code @ConfigurationPropertiesScan} on {@code WisperApplication}, so
 * nothing central had to be edited to add it (panel-configuration.md). Every component
 * carries a {@code @DefaultValue}, because record binding does not fall back to a
 * constructor default and a missing key would otherwise arrive as a zero - a provision
 * timeout of zero fails every database the instant it is asked for.
 *
 * <p>Sizing of the engine container itself - CPU, memory, {@code max_connections} - is
 * deliberately <strong>not</strong> here. It lives in {@code wisper.placement}, because
 * the same numbers are what the scheduler subtracts from a node's capacity, and two
 * copies would let the ceiling the node applies drift from the space the scheduler thinks
 * is gone.
 *
 * @param postgresImage    the container image a PostgreSQL engine runs. Pinned by the
 *                         panel and never chosen by a node: two nodes quietly running
 *                         different major versions is a backup that restores everywhere
 *                         except the machine it is needed on
 * @param postgresVersion  what goes in {@code database_engine.engine_version}, the
 *                         version a dump has to be readable by. Written next to the image
 *                         so the pair is changed together
 * @param mysqlImage       the same for MySQL
 * @param mysqlVersion     the same for MySQL
 * @param defaultQuota     the storage ceiling a new database gets when the customer does
 *                         not pick one
 * @param maxQuota         the largest ceiling a customer may set on one database. Beyond
 *                         this the answer is a dedicated instance, not a bigger number
 * @param firstEnginePort  the low end of the port range extra engines are bound to on a
 *                         node. Above the registered range and clear of both engines'
 *                         defaults, which the first instance on a node still gets
 * @param lastEnginePort   the high end, inclusive
 * @param provisionTimeout how long a node gets to create a database and a role before the
 *                         panel calls it failed. Generous: the very first database on a
 *                         node waits for the engine image to be pulled
 * @param commandTimeout   how long a node gets for a rotation or a drop, both of which
 *                         are a person waiting on a screen for a single statement
 */
@ConfigurationProperties("wisper.database")
public record DatabaseSettings(
        @DefaultValue("postgres:17-alpine") String postgresImage,
        @DefaultValue("17") String postgresVersion,
        @DefaultValue("mysql:8.4") String mysqlImage,
        @DefaultValue("8.4") String mysqlVersion,
        @DefaultValue("1GB") DataSize defaultQuota,
        @DefaultValue("100GB") DataSize maxQuota,
        @DefaultValue("15432") int firstEnginePort,
        @DefaultValue("15999") int lastEnginePort,
        @DefaultValue("5m") Duration provisionTimeout,
        @DefaultValue("45s") Duration commandTimeout) {

    /** A quota below this is a typo; no engine's own catalogue fits in it. */
    public static final long MIN_QUOTA_BYTES = 16L * 1024 * 1024;

    public DatabaseSettings {
        if (postgresImage.isBlank() || mysqlImage.isBlank()) {
            throw new IllegalArgumentException("wisper.database engine images cannot be blank; "
                    + "a node never picks a version for itself");
        }
        if (postgresVersion.isBlank() || mysqlVersion.isBlank()) {
            throw new IllegalArgumentException("wisper.database engine versions cannot be blank; "
                    + "a restore needs to know what wrote the dump");
        }
        if (defaultQuota.toBytes() < MIN_QUOTA_BYTES) {
            throw new IllegalArgumentException("wisper.database.default-quota of " + defaultQuota
                    + " is smaller than an empty database");
        }
        if (maxQuota.toBytes() < defaultQuota.toBytes()) {
            throw new IllegalArgumentException("wisper.database.max-quota must be at least the "
                    + "default quota, or every new database would be refused on creation");
        }
        if (firstEnginePort < 1024 || lastEnginePort > 65535 || firstEnginePort > lastEnginePort) {
            throw new IllegalArgumentException("wisper.database engine port range "
                    + firstEnginePort + "-" + lastEnginePort + " is not a usable range above the "
                    + "privileged ports");
        }
        if (provisionTimeout.isZero() || provisionTimeout.isNegative()
                || commandTimeout.isZero() || commandTimeout.isNegative()) {
            throw new IllegalArgumentException("wisper.database timeouts must be positive; zero "
                    + "would fail every command the moment it was sent");
        }
    }

    /** The image to run for this engine. */
    public String imageFor(EngineKind kind) {
        return kind == EngineKind.POSTGRES ? postgresImage : mysqlImage;
    }

    /** The version recorded on the row, so a backup knows what wrote it. */
    public String versionFor(EngineKind kind) {
        return kind == EngineKind.POSTGRES ? postgresVersion : mysqlVersion;
    }

    /** The default storage ceiling, in bytes. */
    public long defaultQuotaBytes() {
        return defaultQuota.toBytes();
    }

    /** The largest ceiling one database may be given, in bytes. */
    public long maxQuotaBytes() {
        return maxQuota.toBytes();
    }
}
