package lhqm.furimeo.wisper;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jdbc.repository.config.EnableJdbcAuditing;

/**
 * The whole panel: web server, job scheduler and workers in one process, one jar.
 *
 * <p>There is no second deployable. A microservice split would buy nothing here - the
 * scheduler and the web layer share the same PostgreSQL and the same transactions, and
 * that sharing is the point: enqueueing a job and writing the row it acts on either
 * both happen or neither does.
 *
 * <p>{@code @ConfigurationPropertiesScan} means a domain package can declare its own
 * settings as a record annotated {@code @ConfigurationProperties} and have it bound
 * without anyone editing a central configuration class. Use {@code @DefaultValue} on
 * each component for anything that is not spelled out in application.yml.
 *
 * <p>{@code @EnableJdbcAuditing} makes {@code @CreatedDate} and {@code @LastModifiedDate}
 * work on aggregate roots. Spring Data JDBC writes every column on insert, including
 * nulls, so a database-side {@code DEFAULT now()} would be overwritten with NULL -
 * timestamps have to be set on the Java side.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableJdbcAuditing
public class WisperApplication {

    public static void main(String[] args) {
        SpringApplication.run(WisperApplication.class, args);
    }
}
