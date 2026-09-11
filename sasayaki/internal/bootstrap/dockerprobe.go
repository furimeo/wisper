package bootstrap

import (
	"context"
	"fmt"
	"sort"
	"time"

	"github.com/moby/moby/client"
)

// How long the engine gets to answer. A Docker daemon that takes longer than this to
// describe itself is a Docker daemon that cannot start a container in time either, and
// doctor is run interactively.
const dockerProbeTimeout = 10 * time.Second

// dockerReport is what the engine says about itself, reduced to the parts a preflight
// judges. It is a plain struct rather than the API types so a test can build one without
// constructing half of the Docker API.
type dockerReport struct {
	// Host is the socket that answered, for the detail line.
	Host string

	// Version is the engine release, "27.3.1".
	Version string

	// APIVersion is the highest the engine speaks, "1.47".
	APIVersion string

	// CgroupVersion is "1" or "2" as the engine sees it, which is what actually decides
	// whether a limit is enforced - the engine's view can differ from the kernel's when
	// it is running in a container of its own.
	CgroupVersion string

	// Runtimes is every OCI runtime registered with the engine, sorted. "runsc" being
	// present here - not merely on $PATH - is what makes gVisor usable.
	Runtimes []string

	// SecurityOptions is the engine's own list: "name=seccomp,profile=builtin",
	// "name=userns", "name=rootless".
	SecurityOptions []string

	// StorageDriver is "overlay2" on anything modern.
	StorageDriver string

	// RootDirectory is where the engine keeps images and container filesystems, which is
	// usually on a different filesystem from the state directory and fills up first.
	RootDirectory string
}

// inspectDocker asks the engine two questions in one connection.
//
// /version proves the socket is callable and says what the engine is; /info says how it
// is configured. Both, because a socket that accepts a connection and then refuses every
// request is a Docker installation that looks healthy from the outside and cannot start
// anything.
func inspectDocker(ctx context.Context, host string) (dockerReport, error) {
	ctx, cancel := context.WithTimeout(ctx, dockerProbeTimeout)
	defer cancel()

	options := []client.Opt{client.FromEnv, client.WithAPIVersionNegotiation()}
	if host != "" {
		options = append(options, client.WithHost(host))
	}

	engine, err := client.NewClientWithOpts(options...)
	if err != nil {
		return dockerReport{Host: host}, fmt.Errorf("prepare a Docker client: %w", err)
	}
	defer engine.Close()

	report := dockerReport{Host: engine.DaemonHost()}

	version, err := engine.ServerVersion(ctx, client.ServerVersionOptions{})
	if err != nil {
		return report, fmt.Errorf("ask %s for its version: %w", report.Host, err)
	}
	report.Version = version.Version
	report.APIVersion = version.APIVersion

	info, err := engine.Info(ctx, client.InfoOptions{})
	if err != nil {
		// The engine answered /version, so this is not "Docker is missing". Say so, and
		// keep what was learned: the version check can still pass on what it has.
		return report, fmt.Errorf("ask %s for its configuration: %w", report.Host, err)
	}
	report.CgroupVersion = info.Info.CgroupVersion
	report.StorageDriver = info.Info.Driver
	report.RootDirectory = info.Info.DockerRootDir
	report.SecurityOptions = info.Info.SecurityOptions

	runtimes := make([]string, 0, len(info.Info.Runtimes))
	for name := range info.Info.Runtimes {
		runtimes = append(runtimes, name)
	}
	sort.Strings(runtimes)
	report.Runtimes = runtimes

	return report, nil
}

// hasRuntime reports whether the engine will accept --runtime=name.
func (d dockerReport) hasRuntime(name string) bool {
	return contains(d.Runtimes, name)
}
