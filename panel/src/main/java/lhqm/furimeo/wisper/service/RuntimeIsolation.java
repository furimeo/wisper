package lhqm.furimeo.wisper.service;

/**
 * Which container runtime the node must use. Mirrors the
 * {@code service_runtime_isolation_known} CHECK and {@code ContainerRuntime} in
 * {@code workload.proto}.
 *
 * <p>RUNC is the default: it runs everything (apt-get, pip, npm, all package managers)
 * without restriction, and the security layers in sasayaki's hardening.go — a custom
 * seccomp allow-list, dropped capabilities, no-new-privileges, namespace isolation,
 * egress filtering, OOM score adjustment and cgroups v2 ceilings — provide the isolation
 * gVisor offered, without breaking the syscalls package managers need.
 *
 * <p>gVisor ({@link #RUNSC}) remains available for workloads that need the stronger
 * userspace syscall filtering it provides, at the cost of breaking some package managers.
 */
public enum RuntimeIsolation {

    /** Ordinary runc with seccomp + capabilities + namespace isolation. The default. */
    RUNC,

    /** gVisor. Syscalls are filtered in userspace; breaks some package managers. */
    RUNSC;

    /** Whether choosing this obliges the customer to say why. */
    public boolean needsReason() {
        return this == RUNSC;
    }

    /** The word next to the service. */
    public String label() {
        return this == RUNSC ? "gVisor" : "runc";
    }

    /** What the panel warns, or empty for the safe choice. */
    public String warning() {
        return this == RUNSC
                ? "This service runs under gVisor, which may block some package managers (apt, pip, npm)."
                : "";
    }
}
