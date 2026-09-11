package runtime

import (
	"github.com/moby/moby/api/types/container"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
)

// What every customer container gets whether or not the spec asks for it.
//
// These are not options. A workload runs somebody else's code, chosen by somebody the
// operator has never met, next to other customers on the same kernel, and the panel has
// no field with which to weaken any of it. The one thing a workload may choose is runsc
// versus runc, because gVisor genuinely breaks io_uring and a handful of older binaries
// and a platform with no escape hatch turns that into "your app is broken" (design
// section 11.6) - and even then the choice is reported, so a node running on runc says so
// rather than implying containment it does not have.

// retainedCapabilities is what survives dropping ALL.
//
// Dropping every capability and adding nothing back is the version that reads best in a
// security document and breaks the first container anybody deploys: the official images
// for nginx, postgres, node and almost everything else start as root, fix up ownership on
// a data directory and then step down to a service user. Without CHOWN and SETUID that
// entrypoint fails, and the platform's answer to a customer would be "use a different
// image".
//
// So the set is Docker's own default minus the six that actually buy an attacker
// something, each named with why it is gone:
//
//	NET_RAW      raw sockets: ARP and DNS spoofing against the other containers that
//	             share a tenant network, and the single most valuable one to remove
//	SYS_CHROOT   chroot(2), a step on several namespace-escape paths
//	MKNOD        creating device nodes, which is how a mount of a block device begins
//	SETFCAP      writing file capabilities, which is how a dropped capability comes back
//	SETPCAP      moving capabilities between a process's own sets
//	AUDIT_WRITE  writing to the host's kernel audit log, which nothing in a web
//	             application needs and which can be used to bury evidence
//
// Everything left is a capability over the container's own filesystem and its own
// processes, inside a mount namespace that contains nothing but the image and the
// customer's volumes.
var retainedCapabilities = []string{
	// An entrypoint taking ownership of its data directory before dropping privileges.
	"CHOWN",
	"FOWNER",
	"FSETID",
	// The same entrypoint reading and writing files it does not own. Root inside the
	// container effectively has this over the image already; withholding it only breaks
	// the ordinary case.
	"DAC_OVERRIDE",
	// gosu, su-exec, and the USER switch every official image performs.
	"SETGID",
	"SETUID",
	// A supervisor signalling the processes it started.
	"KILL",
	// Listening on port 80 or 443 inside the container's own network namespace. Not a
	// host port: publishing is a separate decision made in ports.go.
	"NET_BIND_SERVICE",
}

// harden fills in the parts of a HostConfig that are the same for every workload.
func harden(host *container.HostConfig, workload spec.Workload, engineRuntime spec.Runtime, hasInit bool) {
	host.Runtime = runtimeName(engineRuntime)

	// Explicit rather than left at the zero value. These are the four fields that turn a
	// container into a process with a funny hat, and a reader of this file should be able
	// to see that none of them is set without cross-referencing the engine's defaults.
	host.Privileged = false
	host.PidMode = ""    // the container gets its own PID namespace
	host.UTSMode = ""    // and its own hostname
	host.UsernsMode = "" // and the daemon's user-namespace remap, when it has one

	host.CapDrop = []string{"ALL"}
	host.CapAdd = append([]string(nil), retainedCapabilities...)

	// The one that stops a setuid binary inside the image from being a privilege
	// escalation: execve can no longer grant the process anything it does not already
	// have. Note the absence of a seccomp entry - the engine's own profile applies
	// unless it is overridden, and this package never overrides it. `seccomp=unconfined`
	// appears nowhere in wisper.
	host.SecurityOpt = []string{"no-new-privileges:true"}

	// Its own cgroup namespace, so /sys/fs/cgroup inside the container shows the
	// container's own limits rather than the host's whole hierarchy.
	host.CgroupnsMode = container.CgroupnsModePrivate
	// Its own SysV IPC namespace. "shareable" would let a second container attach to
	// this one's shared memory segments.
	host.IpcMode = container.IPCModePrivate

	// MaskedPaths and ReadonlyPaths are deliberately left nil. The engine then applies
	// its own list - /proc/kcore, /proc/keys, /sys/firmware and the rest - and pinning a
	// copy of that list here would mean this file silently going out of date every time
	// upstream adds an entry, which is the direction that loses.

	host.ReadonlyRootfs = workload.ReadOnlyRootfs

	// tini as pid 1, so an application that spawns children and does not reap them does
	// not accumulate zombies until the pids ceiling kills the container. Gated on the
	// engine reporting an init binary: asking for one the daemon does not have makes
	// every container on the node fail to create.
	if hasInit {
		useInit := true
		host.Init = &useInit
	}
}
