package lhqm.furimeo.wisper.org;

/**
 * The things a plan puts a number on. Mirrors the {@code quota.resource} and
 * {@code quota_override.resource} CHECK constraints exactly, name for name.
 *
 * <p>Two shapes live here and the distinction matters when reading a limit:
 *
 * <ul>
 * <li><strong>Counts</strong> - {@code PROJECT} through {@code API_TOKEN}, plus
 *     {@code RESTORE_POINT} and {@code DEPLOYMENTS_PER_DAY}. The limit is how many rows
 *     may exist; the amount asked for is normally one.</li>
 * <li><strong>Amounts</strong> - {@code VOLUME_BYTES}, {@code MEMORY_BYTES},
 *     {@code CPU_MILLICORES}, {@code BACKUP_BYTES}. The limit is a total across the
 *     organization and the amount asked for is the size of the thing being created, or
 *     the difference when one is being resized.</li>
 * </ul>
 *
 * <p>{@code DEPLOYMENTS_PER_DAY} is the one windowed resource: its usage is counted over
 * a rolling twenty-four hours rather than as rows that exist.
 */
public enum QuotaResource {

    /** Projects in the organization. */
    PROJECT,

    /** Services across every project, archived ones excluded. */
    SERVICE,

    /** Hostnames pointed at any of those services. */
    DOMAIN,

    /** Customer databases on the shared or dedicated engines. */
    MANAGED_DATABASE,

    /** Customer cron entries inside services. */
    CRON_TASK,

    /** Accepted members. An outstanding invitation does not count. */
    MEMBER,

    /** Live API tokens belonging to members of this organization. */
    API_TOKEN,

    /** Sum of {@code volume.size_bytes}, the space promised rather than the space used. */
    VOLUME_BYTES,

    /** Sum of {@code service.memory_bytes} over services that are not archived. */
    MEMORY_BYTES,

    /** Sum of {@code service.cpu_millicores} over services that are not archived. */
    CPU_MILLICORES,

    /** Sum of {@code restore_point.size_bytes} for snapshots still available. */
    BACKUP_BYTES,

    /** Snapshots kept, however large. */
    RESTORE_POINT,

    /** Deployments started in the last twenty-four hours, rolling. */
    DEPLOYMENTS_PER_DAY
}
