package lhqm.furimeo.wisper.jobs;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Everything under {@code wisper.jobs}.
 *
 * <p>Only what the admin screen needs. Every knob that shapes the queue itself - threads,
 * polling, heartbeats, retention of unresolved tasks - belongs to db-scheduler and lives
 * under {@code db-scheduler.*} in {@code application.yml}. Adding a second place to
 * configure the same thing is how two settings end up disagreeing.
 *
 * <p>Declared in the package that reads it (panel-configuration.md), with
 * {@link DefaultValue} on every component: record binding has no constructor fallback, and
 * a missing key otherwise binds to zero and produces a page with no rows on it.
 *
 * @param failedPageSize rows per page on {@code /admin/jobs}
 */
@ConfigurationProperties("wisper.jobs")
public record JobsSettings(@DefaultValue("25") int failedPageSize) {

    /** Ceiling on the page size, so a hand-edited query string cannot ask for everything. */
    public static final int MAX_PAGE_SIZE = 200;

    public JobsSettings {
        if (failedPageSize <= 0 || failedPageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("wisper.jobs.failed-page-size must be between 1 "
                    + "and " + MAX_PAGE_SIZE + ", not " + failedPageSize);
        }
    }
}
