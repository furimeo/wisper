package lhqm.furimeo.wisper.service;

/**
 * Which container runtime the node must use. Mirrors the
 * {@code service_runtime_isolation_known} CHECK and {@code ContainerRuntime} in
 * {@code workload.proto}.
 *
 * <p>The escape hatch exists because gVisor does not run everything: {@code io_uring} and
 * a handful of older binaries fail under {@code runsc}, and a platform with no way out of
 * that turns "gVisor cannot do this" into "your app is broken" (design §11.6).
 *
 * <p>It is deliberately awkward to take. Choosing {@link #RUNC} requires a written
 * reason - the {@code service_runc_needs_reason} CHECK will not accept a blank one - and
 * the panel shows that reason next to the service as a warning. A weaker sandbox that
 * nobody can see is the one that gets chosen by default six months later.
 */
public enum RuntimeIsolation {

    /** gVisor. Syscalls are filtered in userspace; this is the default and stays it. */
    RUNSC,

    /** Ordinary runc. Faster, and only kernel namespaces between the workload and the host. */
    RUNC;

    /** Whether choosing this obliges the customer to say why. */
    public boolean needsReason() {
        return this == RUNC;
    }

    /** The word next to the service. */
    public String label() {
        return this == RUNSC ? "gVisor" : "runc";
    }

    /** What the panel warns, or empty for the safe choice. */
    public String warning() {
        return this == RUNC
                ? "This service runs without gVisor, so only the kernel separates it from the host."
                : "";
    }
}
