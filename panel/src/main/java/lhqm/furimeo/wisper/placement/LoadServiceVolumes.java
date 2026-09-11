package lhqm.furimeo.wisper.placement;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The storage of every service on one node, in one statement.
 *
 * <p>Two builders need the same rows - {@link BuildWorkloads} turns them into mounts and
 * {@link BuildFileRoots} turns them into the directories the file manager may touch - so
 * they are loaded once and handed to both. Reading them twice would be two queries and,
 * worse, two chances for the mount list and the file-root list to disagree about what a
 * customer has.
 *
 * <p>Ordered by mount path rather than by name. The mount path is what makes a container's
 * filesystem what it is, it is unique per service ({@code volume_service_mount_path_key}),
 * and ordering the spec by it means the same storage always produces the same document.
 */
@Component
public class LoadServiceVolumes {

    private static final String SQL = """
            SELECT id, service_id, name, mount_path, size_bytes, read_only
              FROM volume
             WHERE service_id IN (:serviceIds)
             ORDER BY service_id, mount_path
            """;

    private final JdbcClient jdbc;

    public LoadServiceVolumes(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Volumes grouped by service. A service with no storage is simply absent. */
    @Transactional(readOnly = true)
    public Map<UUID, List<MountedVolume>> forServices(Collection<UUID> serviceIds) {
        if (serviceIds == null || serviceIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<MountedVolume>> byService = new LinkedHashMap<>();
        List<MountedVolume> rows = jdbc.sql(SQL)
                .param("serviceIds", List.copyOf(serviceIds))
                .query(LoadServiceVolumes::map)
                .list();
        for (MountedVolume volume : rows) {
            byService.computeIfAbsent(volume.serviceId(), key -> new ArrayList<>()).add(volume);
        }
        return Map.copyOf(byService);
    }

    private static MountedVolume map(ResultSet row, int rowNumber) throws SQLException {
        return new MountedVolume(
                row.getObject("id", UUID.class),
                row.getObject("service_id", UUID.class),
                row.getString("name"),
                row.getString("mount_path"),
                row.getLong("size_bytes"),
                row.getBoolean("read_only"));
    }
}
