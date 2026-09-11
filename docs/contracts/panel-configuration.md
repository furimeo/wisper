# Panel configuration

Binding. See [README.md](README.md).

## Ports

| Port | What | Configured by |
|---|---|---|
| 8080 | Panel HTTP. People reach this, directly or through a tunnel. | `server.port` |
| 9090 | gRPC. Nodes dial in here and nothing else does. | `wisper.grpc.port` |
| 5173 | Vite dev server. Loopback only, proxied by the panel. | `frontend/vite.config.ts` |
| 5432 | PostgreSQL 17, database `wisper` (`wisper_test` for tests). | `spring.datasource.url` |
| 80 / 443 | Caddy, **on each node**. Never on the panel. | sasayaki |

Two ports rather than one because the two audiences share nothing: 8080 carries session
cookies and CSRF, 9090 carries node tokens and long-lived bidirectional streams, and
whatever sits in front of them - a tunnel, a reverse proxy, or nothing - is configured
differently for each.

## Property namespace

Everything the panel adds to Spring's own configuration lives under `wisper.`. The keys
that exist today are in `application.yml`, with a comment on each saying why.

Your package does **not** get a new key added to `application.yml` by someone else.
Declare your own settings as a record in your own package:

```java
package lhqm.furimeo.wisper.backup;

@ConfigurationProperties("wisper.backup")
public record BackupSettings(
        @DefaultValue("7") int keepDailySnapshots,
        @DefaultValue("30d") Duration retention) {
}
```

`@ConfigurationPropertiesScan` on `WisperApplication` picks it up with no registration
anywhere. **Use `@DefaultValue` on every component.** Record binding does not fall back
to a constructor default; a missing key binds to `null` or `0`, and the failure surfaces
much later as a `NullPointerException` in a scheduled job.

Keys already taken: `wisper.grpc.*`, `wisper.node.*`, `wisper.storage.*`,
`wisper.files.*`.

## Spring Data JDBC conventions

- **No JPA, no Hibernate, no lazy loading.** Repositories are interfaces extending
  `CrudRepository` / `ListCrudRepository`; anything non-trivial is an `@Query` with real
  SQL in it.
- **Aggregates are records.** `@Id Long id` for the primary key. A record with a null id
  is an insert; a non-null id is an update.
- **Table names are the class name in `snake_case`, singular.** `Deployment` maps to
  `deployment`, not `deployments`. Do not add a `@Table` annotation to say what the
  default already says.
- **Reserved words are quoted in the migration**, and only there: PostgreSQL needs
  `"user"` quoted, and Spring Data JDBC will not do it for you. Prefer a name that is
  not reserved - `account` rather than `user`.
- **Timestamps come from Java, not the database.** Spring Data JDBC writes every column
  on insert, nulls included, so a `DEFAULT now()` is overwritten with NULL. Use
  `@CreatedDate` and `@LastModifiedDate` on `Instant` fields; `@EnableJdbcAuditing` is
  already on. Keep the `DEFAULT now()` in the DDL anyway, for rows inserted by a
  migration.
- **Cross-aggregate references are ids, not object graphs.** A `Deployment` holds a
  `long serviceId`, never a `Service`. Spring Data JDBC would otherwise treat the nested
  object as owned and delete it with the parent.
- **Money and quotas are `long` in the smallest unit** (bytes, millicores, cents). Never
  `double`.

## Migrations

Files go in `panel/src/main/resources/wisper/migrations/V{n}__what_it_does.sql`.

- One file per change. Do not add tables to an existing file.
- **A migration that has run is immutable.** `SchemaMigrations` records a SHA-256 of
  each file and refuses to start if an applied one has changed - because editing it
  changes what a fresh database gets without changing any database that already ran it,
  and the two drift apart silently. Fix forward with a new file.
- Numbers are unique. Two files claiming `V7` is a startup failure, not a coin toss.
- The runner splits on semicolons but understands string literals, quoted identifiers
  and `$body$` blocks, so a trigger function is safe to write normally.
- Every statement in one file runs in one transaction. Do not put
  `CREATE INDEX CONCURRENTLY` in a migration; it cannot run inside one.

### Tables the framework requires

**`schema_version`** is created by `SchemaMigrations` itself. Do not write a migration
for it.

**`scheduled_tasks`** is db-scheduler's, and db-scheduler does not create it. It must be
in an early migration, exactly as below - the column names and types are read by the
library, not by us. `priority` is required because `db-scheduler.priority-enabled` is
`true`.

```sql
CREATE TABLE scheduled_tasks (
    task_name             text                     NOT NULL,
    task_instance         text                     NOT NULL,
    task_data             bytea,
    execution_time        timestamp with time zone NOT NULL,
    picked                boolean                  NOT NULL,
    picked_by             text,
    last_success          timestamp with time zone,
    last_failure          timestamp with time zone,
    consecutive_failures  int,
    last_heartbeat        timestamp with time zone,
    version               bigint                   NOT NULL,
    priority              smallint,
    PRIMARY KEY (task_name, task_instance)
);

CREATE INDEX execution_time_idx ON scheduled_tasks (execution_time);
CREATE INDEX last_heartbeat_idx ON scheduled_tasks (last_heartbeat);
CREATE INDEX priority_execution_time_idx ON scheduled_tasks (priority DESC, execution_time ASC);
```

## Startup order

1. `SchemaMigrations` runs during bean initialization, holding a PostgreSQL advisory
   lock so two panels starting together cannot both apply the same file.
2. Every other bean is created.
3. `ContextRefreshedEvent` - db-scheduler starts polling.

If a bean of yours reads the database while it is being constructed, annotate it
`@DependsOnDatabaseInitialization`. `SchemaMigrationsDetector` (registered in
`META-INF/spring.factories`) is what makes that annotation wait for the migrations.

Do not use a `CommandLineRunner` for anything the scheduler depends on: runners execute
*after* `ContextRefreshedEvent`, which is after the first poll.

## Jobs

Define one `Task` bean per job, in the package that owns the work:

```java
@Bean
Task<DeploymentJob> runDeploymentTask(StartDeployment startDeployment) {
    return Tasks.oneTime("run-deployment", DeploymentJob.class)
            .execute((instance, context) -> startDeployment.run(instance.getData()));
}
```

Task names are global and permanent - a renamed task orphans every row already queued
under the old name, and db-scheduler logs those as unresolved for
`delete-unresolved-after` before dropping them. Prefix with your domain:
`deploy-`, `backup-`, `node-`, `database-`.

Enqueue inside the same transaction as the write that justifies the job. That is the
entire reason the queue is PostgreSQL and not Redis; a job that exists for a row that
was rolled back is the bug this design removes.

## Local setup

```bash
createdb -U postgres wisper
createdb -U postgres wisper_test
psql -U postgres -c "CREATE USER wisper PASSWORD 'wisper'"
psql -U postgres -c "GRANT ALL ON DATABASE wisper TO wisper"
psql -U postgres -c "GRANT ALL ON DATABASE wisper_test TO wisper"
```

Override with `WISPER_DB_USER` / `WISPER_DB_PASSWORD`, and
`WISPER_TEST_DB_USER` / `WISPER_TEST_DB_PASSWORD` for the test database.
