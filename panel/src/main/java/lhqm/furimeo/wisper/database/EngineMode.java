package lhqm.furimeo.wisper.database;

/**
 * Whether an engine container is shared between tenants or belongs to one of them.
 *
 * <p>Shared is the default and the reason the platform can host hundreds of customers on
 * a machine: an idle PostgreSQL costs 30-50 MB of resident memory, so a few hundred
 * private instances is the whole node (design §8.1). One shared instance per engine per
 * node, enforced by {@code database_engine_shared_per_node_key}.
 *
 * <p>{@link #DEDICATED} is the paid escape hatch. It is a row in the same table with an
 * {@code organization_id}, and {@code database_engine_dedicated_has_owner} makes the mode
 * and the owner one fact rather than two that can disagree.
 */
public enum EngineMode {

    /** Every tenant on the node gets a database and a role inside this one container. */
    SHARED,

    /** One organization's own container, isolated from everybody else's queries. */
    DEDICATED;

    /** Whether a row in this mode must carry an owning organization. */
    public boolean needsOwner() {
        return this == DEDICATED;
    }
}
