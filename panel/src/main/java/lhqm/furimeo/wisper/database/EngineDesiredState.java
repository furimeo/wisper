package lhqm.furimeo.wisper.database;

/**
 * What an operator wants an engine container to be doing.
 *
 * <p>Panel intent, and only that. What the container is <em>actually</em> doing arrives
 * from the node and lands in {@code database_engine.reported_state}, which is a separate
 * column with a separate owner (schema.md §2). Writing one from the other is the mistake
 * that split exists to prevent: "stopped by an operator" and "crashed" have to stay
 * different facts, because the answer to them is different.
 *
 * <p>Mirrors {@code database_engine_desired_state_known}.
 */
public enum EngineDesiredState {

    /** The node must keep this container running, and restart it when it exits. */
    RUNNING,

    /**
     * The node must stop the container and leave the data where it is.
     *
     * <p>Stopping an engine takes every database on it offline, which is why
     * {@link SetEngineDesiredState} refuses to do it quietly for a shared instance that
     * still holds customer databases.
     */
    STOPPED
}
