package dbengine

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"strconv"
	"strings"

	"github.com/moby/moby/api/types/container"
	"github.com/moby/moby/api/types/mount"

	"github.com/furimeo/wisper/sasayaki/internal/reconcile"
	"github.com/furimeo/wisper/sasayaki/internal/spec"
)

// One database server, as a container.
//
// Pure functions over a spec.Engine: nothing here touches the engine API or the disk, so
// every decision below can be asserted on in a test with no Docker anywhere. The convergence
// that uses them is in converge.go.

const (
	// namePrefix marks the container as this platform's in `docker ps`. An operator looking at
	// a node should be able to tell wisper's containers from whatever else the machine runs
	// without consulting labels.
	namePrefix = "wisper-db"

	// logMaxBytes and logMaxFiles bound the container's log. Not optional: the default is an
	// unbounded file, and a server logging a connection error every second is a node whose
	// disk fills overnight.
	logMaxBytes int64 = 32 << 20
	logMaxFiles int64 = 3

	// pidsLimit is a ceiling on the server's process table. Generous - a database forks per
	// connection on some configurations - and still a ceiling, because a fork bomb in a
	// pinned image is unlikely and a machine taken down by one is not recoverable remotely.
	pidsLimit int64 = 512

	// nofileLimit is how many file descriptors the server may hold. A database with a few
	// hundred connections and a few thousand tables goes past a distribution default of 1024
	// and fails in a way that looks like corruption rather than like a limit.
	nofileLimit int64 = 65535

	// minimumShm and maximumShm bound the shared memory segment. PostgreSQL puts its buffers
	// in /dev/shm, and Docker's 64 MB default makes a server with more than a handful of
	// connections fail with "could not resize shared memory segment" under load - which reads
	// like a database bug and is a container setting.
	minimumShm int64 = 256 << 20
	maximumShm int64 = 1 << 30
)

// instance is one database server this node must run.
type instance struct {
	// ID is DatabaseEngineSpec.data_volume_id, which is the panel's row id for this server.
	ID string
	// Name is the container's name, stable for the life of the server. It is also the host a
	// customer's connection string points at: on a user-defined network the engine resolves
	// container names, so the name is the address.
	Name  string
	Spec  spec.Engine
	Kind  spec.EngineKind
	Talk  dialect
	Plan  containerPlan
	Paths instancePaths
	// Fingerprint is the hash of everything about this server that a container has to be
	// recreated to change.
	Fingerprint string
}

// instanceFor resolves everything that can be worked out about a server without asking Docker.
func instanceFor(stateDir string, engine spec.Engine) (instance, error) {
	talk, err := dialectFor(engine.Kind)
	if err != nil {
		return instance{}, err
	}
	if err := checkInstanceID(engine.DataVolumeID); err != nil {
		return instance{}, err
	}
	if strings.TrimSpace(engine.Image) == "" {
		return instance{}, fmt.Errorf("dbengine: the %s server %s has no image, and this node "+
			"never picks a version for itself", engine.Kind, engine.DataVolumeID)
	}
	if strings.TrimSpace(engine.AdminUsername) == "" || engine.AdminPassword == "" {
		return instance{}, fmt.Errorf("dbengine: the %s server %s arrived without administrative "+
			"credentials, so nothing could be created on it", engine.Kind, engine.DataVolumeID)
	}
	paths, err := pathsFor(stateDir, engine.DataVolumeID)
	if err != nil {
		return instance{}, err
	}

	plan := talk.container(engine)
	built := instance{
		ID:    engine.DataVolumeID,
		Name:  containerName(engine),
		Spec:  engine,
		Kind:  engine.Kind,
		Talk:  talk,
		Plan:  plan,
		Paths: paths,
	}
	built.Fingerprint = fingerprintOf(built)
	return built, nil
}

// containerName is what the server is called, in `docker ps` and on every tenant network it
// joins. Derived from the instance id so it survives a recreation, which matters: the panel
// hands the customer a connection string containing it.
func containerName(engine spec.Engine) string {
	return namePrefix + "-" + strings.ToLower(string(engine.Kind)) + "-" + engine.DataVolumeID
}

// fingerprintOf is the hash of everything a container has to be recreated to change.
//
// The administrative password is deliberately absent. Both official images apply it at
// initialisation only - it is written into the data directory and a new container over the
// same data keeps the old one - so recreating a container because the password changed would
// destroy nothing, fix nothing and repeat every fifteen seconds forever. A panel that changes
// it therefore makes the node unable to authenticate, which is reported as an error on every
// grant rather than hidden behind a container that keeps restarting.
func fingerprintOf(built instance) string {
	digest := sha256.New()
	write := func(parts ...string) {
		for _, part := range parts {
			digest.Write([]byte(part))
			digest.Write([]byte{0})
		}
	}
	write("kind", string(built.Kind))
	write("image", built.Spec.Image)
	write("admin", built.Spec.AdminUsername)
	write("data", built.Spec.DataVolumeID)
	write("port", strconv.FormatUint(uint64(built.Plan.Port), 10))
	write("cpus", strconv.FormatInt(built.Spec.NanoCPUs, 10))
	write("memory", strconv.FormatInt(built.Spec.MemoryBytes, 10))
	write("connections", strconv.FormatInt(int64(maxConnections(built.Spec)), 10))
	write("cmd")
	write(built.Plan.Cmd...)
	return hex.EncodeToString(digest.Sum(nil))
}

// configFor is the portable half of the container: the image, what it runs and how it is
// labelled.
func configFor(built instance) *container.Config {
	return &container.Config{
		Image: built.Spec.Image,
		Env:   append([]string(nil), built.Plan.Env...),
		Cmd:   append([]string(nil), built.Plan.Cmd...),
		Labels: map[string]string{
			reconcile.LabelManaged: reconcile.LabelManagedValue,
			labelInstance:          built.ID,
			labelKind:              string(built.Kind),
			labelFingerprint:       built.Fingerprint,
		},
		// The hostname a customer sees in the server's own logs, and the same string their
		// connection string points at.
		Hostname: built.Name,
		// A server is not a session. With a tty the engine stops multiplexing stdout and
		// stderr, and the difference between a customer's crash and a routine line is lost.
		Tty:       false,
		OpenStdin: false,
	}
}

// hostConfigFor is the non-portable half: the ceilings, the containment and the two
// directories the server is allowed to see.
//
// # Why there is no gVisor here
//
// Every other container this daemon creates runs under runsc, because it runs somebody else's
// code. A database server does not: the image is pinned by the panel, the process is
// PostgreSQL or MySQL, and the only thing a customer can send it is SQL over a socket - which
// runsc does not filter. What runsc would cost is the one thing a database is made of, since
// every read and every fsync crosses the sentry. So the server runs under the engine's own
// runtime, and this comment is the record of that being a decision rather than an oversight.
//
// What it does get instead is a container with no network of its own (converge.go joins it
// only to the tenant bridges whose workloads have to reach it, so a database server cannot
// call out to the internet at all), every capability dropped but the six the official
// entrypoints need to step down from root, and no way to gain a new one.
func hostConfigFor(built instance) *container.HostConfig {
	host := &container.HostConfig{
		Mounts: []mount.Mount{
			{
				Type:        mount.TypeBind,
				Source:      built.Paths.Data,
				Target:      built.Plan.DataMount,
				BindOptions: &mount.BindOptions{CreateMountpoint: true},
			},
			{
				Type:        mount.TypeBind,
				Source:      built.Paths.Transfer,
				Target:      transferMount,
				BindOptions: &mount.BindOptions{CreateMountpoint: true},
			},
		},
		Resources: resourcesFor(built.Spec),
		// unless-stopped rather than always, so a server an operator stopped by hand for
		// maintenance stays stopped across a reboot. A database that comes back on its own
		// after the Docker daemon restarts is the difference between a node rebooting and a
		// node rebooting into an outage nobody notices until the next reconcile pass.
		RestartPolicy: container.RestartPolicy{Name: container.RestartPolicyUnlessStopped},
		LogConfig: container.LogConfig{
			Type: "json-file",
			Config: map[string]string{
				"max-size": strconv.FormatInt(logMaxBytes, 10),
				"max-file": strconv.FormatInt(logMaxFiles, 10),
			},
		},
		// No network at creation. The tenant bridges are joined afterwards, one per tenant
		// with a workload on this node, and nothing else - so the server has no route to the
		// internet and no address on the default bridge where every other container could
		// reach it.
		NetworkMode: container.NetworkMode("none"),
		// Removal is this daemon's decision. A container the engine deleted on exit would
		// take its logs with it, and the reason a customer's database stopped would be gone.
		AutoRemove:   false,
		CapDrop:      []string{"ALL"},
		CapAdd:       append([]string(nil), serverCapabilities...),
		SecurityOpt:  []string{"no-new-privileges:true"},
		CgroupnsMode: container.CgroupnsModePrivate,
		IpcMode:      container.IPCModePrivate,
		ShmSize:      shmSizeFor(built.Spec),
	}
	return host
}

// serverCapabilities is what survives dropping ALL.
//
// Both official entrypoints start as root, fix the ownership of the data directory and then
// step down to the server's own user. That needs exactly these six and nothing else: no
// NET_BIND_SERVICE, because the port is above 1024 and would be inside the container anyway;
// no SYS_ADMIN, no SYS_RESOURCE, no MKNOD.
var serverCapabilities = []string{
	"CHOWN",
	"DAC_OVERRIDE",
	"FOWNER",
	"FSETID",
	"SETGID",
	"SETUID",
}

// resourcesFor is the cgroups v2 ceiling the server runs under.
//
// NanoCPUs is nano-CPUs - a hard ceiling in billionths of a core - and not CPUShares
// multiplied by a thousand. The predecessor conflated the two and every container on every
// node got a relative scheduling weight where a limit was intended (design section 9); a
// shared database server with a scheduling preference instead of a ceiling is the one process
// on the machine best placed to take the whole thing down.
func resourcesFor(engine spec.Engine) container.Resources {
	resources := container.Resources{
		NanoCPUs: engine.NanoCPUs,
		Memory:   engine.MemoryBytes,
	}
	if engine.MemoryBytes > 0 {
		// Equal to Memory disables swap. A database that swaps makes the whole machine
		// grind, and every customer on it is slow at once; one that is killed is one
		// service the reconcile pass brings back fifteen seconds later.
		resources.MemorySwap = engine.MemoryBytes
	}
	pids := pidsLimit
	resources.PidsLimit = &pids
	resources.Ulimits = []*container.Ulimit{
		{Name: "nofile", Soft: nofileLimit, Hard: nofileLimit},
	}
	return resources
}

// shmSizeFor is how much shared memory the server gets: a quarter of its memory ceiling,
// between 256 MB and a gigabyte. A server with no ceiling gets the floor, which is still four
// times Docker's default.
func shmSizeFor(engine spec.Engine) int64 {
	size := engine.MemoryBytes / 4
	if size < minimumShm {
		return minimumShm
	}
	if size > maximumShm {
		return maximumShm
	}
	return size
}
