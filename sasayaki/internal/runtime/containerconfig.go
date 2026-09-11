package runtime

import (
	"github.com/moby/moby/api/types/container"
	"github.com/moby/moby/api/types/network"

	"github.com/furimeo/wisper/sasayaki/internal/reconcile"
	"github.com/furimeo/wisper/sasayaki/internal/spec"
)

// labelImageDigest records what a container was really created from.
//
// Read back by list.go so the panel can be told what is running without an image inspect
// per container on every pass, and so a tag that moved under a workload is visible as a
// difference rather than as a mystery restart. The three labels next to it -
// wisper.managed, wisper.workload and wisper.fingerprint - belong to the reconcile loop
// that reads them and are declared there.
const labelImageDigest = "wisper.image.digest"

// configFor is the portable half of a container: what runs, with what environment, under
// what name. Nothing host-specific belongs here - that is hostconfig.go - and nothing in
// this function touches the network or the disk, which is what makes it a pure function
// the tests can hold still.
func configFor(workload spec.Workload, imageRef, imageDigest, fingerprint string, exposed network.PortSet) *container.Config {
	config := &container.Config{
		Image:      imageRef,
		Env:        workload.Environ(),
		WorkingDir: workload.WorkingDir,
		User:       workload.User,
		Labels: map[string]string{
			reconcile.LabelManaged:     reconcile.LabelManagedValue,
			reconcile.LabelWorkload:    workload.ID,
			reconcile.LabelFingerprint: fingerprint,
			labelImageDigest:           imageDigest,
		},
		ExposedPorts: exposed,
		Healthcheck:  healthFor(workload.Health),

		// No tty and no stdin. A workload is a service, not a session: with a tty the
		// engine stops multiplexing stdout and stderr and the log feed loses the
		// distinction between the two, which is the difference between a customer seeing
		// their crash and seeing their access log. The terminal gets its own pty through
		// exec.go, where the framing is explicit.
		Tty:       false,
		OpenStdin: false,
	}

	// Only when the panel sent one. An empty entrypoint or command must stay empty so
	// the image's own ENTRYPOINT and CMD apply; sending an empty slice would clear them,
	// and the container would start and immediately exit with nothing to run.
	if len(workload.Entrypoint) > 0 {
		config.Entrypoint = append([]string(nil), workload.Entrypoint...)
	}
	if len(workload.Command) > 0 {
		config.Cmd = append([]string(nil), workload.Command...)
	}

	// The hostname a process sees when it asks. The container id is what the engine uses
	// otherwise, and an application that logs its own hostname is much easier to read
	// when that hostname is the service's name.
	if hostname := slug(workload.Name); hostname != "" {
		config.Hostname = hostname
	}

	// How long SIGTERM gets before SIGKILL, recorded on the container as well as passed
	// to each stop. It matters when the engine stops the container without going through
	// this daemon - a host reboot - where a database-backed application killed at the
	// default loses whatever it had in flight.
	if seconds := int(workload.StopGrace.Seconds()); seconds > 0 {
		config.StopTimeout = &seconds
	}

	return config
}

// healthFor is Docker's health check, as the panel configured it.
//
// Two things are deliberate. The CMD marker is added here rather than by the panel, so
// the wire carries an argument vector and not a Docker-shaped one. And an empty test
// produces an explicit "NONE" rather than nil: nil inherits whatever HEALTHCHECK the
// image happens to declare, and a customer whose service is shown as unhealthy by a check
// they did not configure, cannot see and cannot switch off has been failed by the
// platform rather than helped by it. The panel decides whether there is a check at all.
func healthFor(health spec.Health) *container.HealthConfig {
	if !health.IsSet() {
		return &container.HealthConfig{Test: []string{"NONE"}}
	}
	return &container.HealthConfig{
		Test:        append([]string{"CMD"}, health.Test...),
		Interval:    health.Interval,
		Timeout:     health.Timeout,
		StartPeriod: health.StartPeriod,
		Retries:     int(health.Retries),
	}
}
