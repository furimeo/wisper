package spec

import (
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Kind is what a workload is.
//
// An app runs a container. A site is a directory of files the embedded Caddy serves with no
// process at all, which is what makes an idle static site cost nothing (design section 5.5).
type Kind string

const (
	// KindUnknown is a kind this binary does not recognise. The reconciler must not guess:
	// starting a container for something the panel meant as a directory of files, or the
	// other way round, is worse than reporting that this workload cannot be handled and
	// leaving it alone.
	KindUnknown Kind = "UNKNOWN"
	KindApp     Kind = "APP"
	KindSite    Kind = "SITE"
)

// Desired is whether a workload should be up.
type Desired string

const (
	// DesiredUnspecified means the panel did not say, which is a panel bug. The reconciler
	// neither starts nor stops nor removes such a workload; it reports it. Guessing
	// "running" starts something a customer may have paid to have stopped, and guessing
	// "stopped" takes down something that is serving.
	DesiredUnspecified Desired = "UNSPECIFIED"
	DesiredRunning     Desired = "RUNNING"
	// DesiredStopped keeps the container, its volumes and its logs. Removal is done by
	// leaving the workload out of the spec entirely.
	DesiredStopped Desired = "STOPPED"
)

// Runtime is which container runtime a workload gets.
type Runtime string

const (
	// RuntimeRunsc is gVisor, and the default for anything unspecified. Defaulting the
	// other way would hand a workload less isolation than the panel asked for and say
	// nothing about it, which is the one kind of silence this platform cannot afford.
	RuntimeRunsc Runtime = "RUNSC"
	// RuntimeRunc exists because io_uring and some older binaries do not work under runsc
	// (design section 11.6). The panel shows which one is really in use, so a node with no
	// runsc installed reports runc rather than implying isolation it does not have.
	RuntimeRunc Runtime = "RUNC"
)

// Workload is one deployable unit a customer owns.
type Workload struct {
	// Stable for the life of the service: container names, volume directories and log
	// streams all derive from it.
	ID   string
	Kind Kind
	// What the customer called it. Used for the container name, so `docker ps` is readable
	// without resolving ids by hand.
	Name string
	// App only.
	Image string
	// The digest the panel resolved, when it resolved one. Pinning means two nodes told to
	// run the same tag run the same bytes. Empty means resolve the tag and report back what
	// was got.
	ImageDigest string
	// Docker's ENTRYPOINT and CMD as argument vectors. Never a shell string: the panel
	// builds argv and the node passes argv, so a value with a semicolon in it cannot become
	// a second command.
	Entrypoint []string
	Command    []string
	WorkingDir string
	// In the order the panel sent, which is by name. A slice and not a map because the spec
	// is hashed to detect drift and protobuf map ordering is not deterministic.
	Env     []EnvVar
	Limits  Limits
	Mounts  []Mount
	Ports   []Port
	Restart Restart
	Runtime Runtime
	Desired Desired
	// Zero value when the panel sent none; Health.IsSet reports which.
	Health Health
	// The Docker network this workload joins, one per tenant. Two customers on one node
	// must not be able to reach each other's containers by address.
	TenantNetwork  string
	ReadOnlyRootfs bool
	// uid[:gid] inside the container. Empty means the image's own user.
	User string
	// How long SIGTERM gets before SIGKILL.
	StopGrace time.Duration
	// Site only: which directory under releases/ the `current` symlink must point at.
	// Publishing and rolling back are both a new generation naming a different release, so
	// there is no publish command that could disagree with the spec.
	ReleaseID string
	Site      SiteOptions
}

// IsApp reports whether this workload is a container the node has to keep alive.
func (w Workload) IsApp() bool { return w.Kind == KindApp }

// IsSite reports whether this workload is a directory of files with no process.
func (w Workload) IsSite() bool { return w.Kind == KindSite }

// Environ renders the environment as Docker wants it, `NAME=value`, in spec order.
//
// Here rather than in each caller because two packages need the same rendering - the
// runtime for a container and the builder for a build - and two renderings of one
// environment is how a workload and its build end up disagreeing about a variable.
//
// Secret values are included: the container needs them. What must not happen is a secret
// reaching a log line, and EnvVar.Secret is the flag that says which entries those are.
func (w Workload) Environ() []string {
	environ := make([]string, 0, len(w.Env))
	for _, entry := range w.Env {
		environ = append(environ, entry.Name+"="+entry.Value)
	}
	return environ
}

// EnvVar is one environment variable.
type EnvVar struct {
	Name  string
	Value string
	// The node must not put this value in a log line, an error message or any status it
	// sends back. Marked on the wire so neither side has to guess from the name, which is
	// what a platform does when it redacts DATABASE_PASSWORD and misses DB_PW.
	Secret bool
}

func workloadFromProto(message *wisperpb.Workload) Workload {
	workload := Workload{
		ID:             message.GetId(),
		Kind:           kindFromProto(message.GetKind()),
		Name:           message.GetName(),
		Image:          message.GetImage(),
		ImageDigest:    message.GetImageDigest(),
		Entrypoint:     append([]string(nil), message.GetEntrypoint()...),
		Command:        append([]string(nil), message.GetCommand()...),
		WorkingDir:     message.GetWorkingDir(),
		Limits:         limitsFromProto(message.GetLimits()),
		Restart:        restartFromProto(message.GetRestart()),
		Runtime:        runtimeFromProto(message.GetRuntime()),
		Desired:        desiredFromProto(message.GetDesiredState()),
		Health:         healthFromProto(message.GetHealthCheck()),
		TenantNetwork:  message.GetTenantNetwork(),
		ReadOnlyRootfs: message.GetReadOnlyRootfs(),
		User:           message.GetUser(),
		StopGrace:      seconds(message.GetStopGraceSeconds()),
		ReleaseID:      message.GetReleaseId(),
		Site:           siteOptionsFromProto(message.GetSite()),
	}

	workload.Env = make([]EnvVar, 0, len(message.GetEnv()))
	for _, entry := range message.GetEnv() {
		workload.Env = append(workload.Env, EnvVar{
			Name:   entry.GetName(),
			Value:  entry.GetValue(),
			Secret: entry.GetSecret(),
		})
	}
	workload.Mounts = make([]Mount, 0, len(message.GetMounts()))
	for _, mount := range message.GetMounts() {
		workload.Mounts = append(workload.Mounts, mountFromProto(mount))
	}
	workload.Ports = make([]Port, 0, len(message.GetPorts()))
	for _, port := range message.GetPorts() {
		workload.Ports = append(workload.Ports, portFromProto(port))
	}
	return workload
}

func kindFromProto(value wisperpb.WorkloadKind) Kind {
	switch value {
	case wisperpb.WorkloadKind_WORKLOAD_KIND_APP:
		return KindApp
	case wisperpb.WorkloadKind_WORKLOAD_KIND_SITE:
		return KindSite
	default:
		return KindUnknown
	}
}

func desiredFromProto(value wisperpb.DesiredState) Desired {
	switch value {
	case wisperpb.DesiredState_DESIRED_STATE_RUNNING:
		return DesiredRunning
	case wisperpb.DesiredState_DESIRED_STATE_STOPPED:
		return DesiredStopped
	default:
		return DesiredUnspecified
	}
}

func runtimeFromProto(value wisperpb.ContainerRuntime) Runtime {
	if value == wisperpb.ContainerRuntime_CONTAINER_RUNTIME_RUNC {
		return RuntimeRunc
	}
	// Unspecified included: the secure default is the one that costs nothing to be wrong
	// about, because a node without runsc reports the fallback rather than hiding it.
	return RuntimeRunsc
}

func runtimeToProto(value Runtime) wisperpb.ContainerRuntime {
	if value == RuntimeRunc {
		return wisperpb.ContainerRuntime_CONTAINER_RUNTIME_RUNC
	}
	return wisperpb.ContainerRuntime_CONTAINER_RUNTIME_RUNSC
}
