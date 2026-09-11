package build

import (
	"github.com/moby/moby/api/types/container"

	"github.com/furimeo/wisper/sasayaki/internal/reconcile"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// What every build container gets, whether or not the plan asks for it.
//
// A build is not a lesser workload. `npm install` runs install scripts the repository
// chose, from packages it did not write, with a network connection - which is the same
// threat model as a customer's running container and arrives through a door people think
// about less. So the ceilings and the containment here mirror runtime/hardening.go rather
// than being a relaxed version of it, and the two deliberate differences are named below.

// buildCapabilities is what survives dropping ALL.
//
// Shorter than the workload set, because a build has no entrypoint stepping down from root
// to a service user and no port to bind: it needs to own the files it writes into the
// bind-mounted workspace and nothing more. NET_BIND_SERVICE, KILL, SETUID and SETGID are
// absent precisely because nothing a compiler does requires them.
var buildCapabilities = []string{
	"CHOWN",
	"DAC_OVERRIDE",
	"FOWNER",
	"FSETID",
}

const (
	// labelBuild carries the build id on the ephemeral container.
	//
	// Note what it is not: reconcile.LabelWorkload. The reconcile loop lists containers
	// carrying both wisper.managed and wisper.workload, so a build in flight is invisible
	// to it and cannot be removed as an orphan halfway through a customer's deployment -
	// which is exactly the split runtime/list.go describes.
	labelBuild = "wisper.build"

	// cpuPeriod is the cgroups v2 scheduling window a CPU ceiling is expressed against
	// when the Engine API takes a quota rather than nano-CPUs, which is the case for
	// `docker build`. 100ms is the kernel's own default.
	cpuPeriod = 100_000
)

// buildLabels mark a container as this daemon's without making it the reconciler's.
func buildLabels(buildID string) map[string]string {
	return map[string]string{
		reconcile.LabelManaged: reconcile.LabelManagedValue,
		labelBuild:             buildID,
	}
}

// buildResources are the cgroups v2 ceilings a build runs under.
//
// NanoCPUs is nano-CPUs: a hard ceiling in billionths of a core, not CPUShares multiplied
// by a thousand. The predecessor conflated the two and every workload got a relative
// scheduling weight where a limit was intended (design section 9); a webpack build with a
// scheduling preference instead of a ceiling takes the node down and it is the neighbours
// who notice.
func buildResources(limits *wisperpb.ResourceLimits) container.Resources {
	resources := container.Resources{
		NanoCPUs: limits.GetNanoCpus(),
		Memory:   limits.GetMemoryBytes(),
	}

	// Swap is only meaningful alongside a memory limit and the engine rejects the pair
	// otherwise. Equal to Memory disables swap: a build that swaps makes the whole machine
	// grind, while one that is killed fails a single deployment.
	if memory := limits.GetMemoryBytes(); memory > 0 {
		resources.MemorySwap = limits.GetMemorySwapBytes()
		if resources.MemorySwap == 0 || (resources.MemorySwap > 0 && resources.MemorySwap < memory) {
			resources.MemorySwap = memory
		}
	}

	// A build system that forks per file is the ordinary case, so this is generous where
	// it is set - but unset is not unlimited by accident: the panel sends a figure and a
	// zero here means it deliberately did not.
	if pids := limits.GetPidsLimit(); pids > 0 {
		value := pids
		resources.PidsLimit = &value
	}

	if nofile := limits.GetNofileLimit(); nofile > 0 {
		resources.Ulimits = []*container.Ulimit{
			{Name: "nofile", Soft: nofile, Hard: nofile},
		}
	}
	return resources
}

// hardenBuild fills in the parts of a build container's HostConfig that never vary.
func hardenBuild(host *container.HostConfig, engineRuntime string) {
	host.Runtime = engineRuntime

	host.Privileged = false
	host.PidMode = ""
	host.UTSMode = ""
	host.UsernsMode = ""

	host.CapDrop = []string{"ALL"}
	host.CapAdd = append([]string(nil), buildCapabilities...)
	host.SecurityOpt = []string{"no-new-privileges:true"}
	host.CgroupnsMode = container.CgroupnsModePrivate
	host.IpcMode = container.IPCModePrivate

	// Never restarted. A build that died is a build that failed, and an engine that
	// brought it back would run a customer's install scripts a second time behind
	// everybody's back.
	host.RestartPolicy = container.RestartPolicy{Name: container.RestartPolicyDisabled}

	// Not AutoRemove: the exit code is the entire point of running this, and a container
	// the engine has already deleted has no exit code to ask for. It is removed by hand in
	// runcontainer.go, on every path including the one where the deadline passed.
	host.AutoRemove = false

	// The default bridge, because a build without the internet cannot install anything.
	// This is the second deliberate difference from a workload: a customer's container
	// gets a per-tenant network whose egress to private ranges and metadata endpoints is
	// dropped (runtime/egress.go), and that filter is installed per tenant bridge by the
	// package that creates those bridges. A build reaches the public internet and the
	// host's own subnet, and the mitigation for the difference is time: the container
	// exists for the length of one build and holds nothing but a checkout.
	host.NetworkMode = "bridge"

	// MaskedPaths and ReadonlyPaths stay nil so the engine applies its own list, and no
	// seccomp option appears here so its own profile stays on. `seccomp=unconfined` is
	// written nowhere in wisper.
}

// buildEnvironment is the plan's build-time variables as the engine takes them.
//
// The secret flag is carried but not acted on here: nothing in this package writes an
// environment to a log or to a status, so there is no redaction step to get wrong. The
// flag exists so that whoever does show one knows which values must not appear.
func buildEnvironment(variables []*wisperpb.EnvVar) []string {
	out := make([]string, 0, len(variables))
	for _, variable := range variables {
		if variable.GetName() == "" {
			continue
		}
		out = append(out, variable.GetName()+"="+variable.GetValue())
	}
	return out
}

// buildArguments is the same list in the shape `docker build` takes.
func buildArguments(variables []*wisperpb.EnvVar) map[string]*string {
	if len(variables) == 0 {
		return nil
	}
	out := make(map[string]*string, len(variables))
	for _, variable := range variables {
		if variable.GetName() == "" {
			continue
		}
		value := variable.GetValue()
		out[variable.GetName()] = &value
	}
	return out
}

// cpuQuotaFor converts a nano-CPU ceiling into the quota-and-period pair the build
// endpoint takes. 500000000 nano-CPUs over a 100ms period is a 50ms quota: half a core,
// enforced, which is the same thing NanoCPUs means everywhere else in this daemon.
func cpuQuotaFor(nanoCPUs int64) (quota, period int64) {
	if nanoCPUs <= 0 {
		return 0, 0
	}
	quota = nanoCPUs * cpuPeriod / 1_000_000_000
	if quota < 1000 {
		// The kernel's floor. Below it the engine refuses the value outright, and a build
		// that will not start is worse than one that is barely throttled.
		quota = 1000
	}
	return quota, cpuPeriod
}
