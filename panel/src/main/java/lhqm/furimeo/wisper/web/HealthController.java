package lhqm.furimeo.wisper.web;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness for whatever supervises the jar.
 *
 * <p>It checks the database, because a panel that cannot reach PostgreSQL cannot do
 * anything a caller would want from it - answering "up" in that state is the kind of
 * health check that keeps a broken process in a load balancer. Actuator is not a
 * dependency for one endpoint that fits on a screen.
 */
@RestController
public class HealthController {

    /** Long enough for a busy pool, short enough that a hung check still answers. */
    private static final int VALIDATION_TIMEOUT_SECONDS = 2;

    private final DataSource dataSource;

    public HealthController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        String database = databaseStatus();
        boolean up = "up".equals(database);

        body.put("status", up ? "up" : "down");
        body.put("database", database);

        return ResponseEntity.status(up ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    private String databaseStatus() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(VALIDATION_TIMEOUT_SECONDS) ? "up" : "unreachable";
        } catch (SQLException e) {
            // The reason is for the log, not for an unauthenticated caller: a JDBC error
            // names the host, the port and often the user.
            return "unreachable";
        }
    }
}
