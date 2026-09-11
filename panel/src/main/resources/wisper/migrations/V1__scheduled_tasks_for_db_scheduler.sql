-- db-scheduler's own table. The library reads these column names and types; it does not
-- create the table itself, and it does not tolerate a different shape.
--
-- This is V1 because db-scheduler starts polling on ContextRefreshedEvent, which is the
-- first thing to touch the database after the migration runner finishes. It is the one
-- table in this schema that is not ours to design: see
-- docs/contracts/panel-configuration.md.
--
-- `priority` exists because db-scheduler.priority-enabled is true in application.yml.
-- Removing it is a startup failure, not a slow query.

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
