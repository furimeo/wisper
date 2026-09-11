package runtime

import (
	"context"
	"fmt"
	"log/slog"

	"github.com/moby/moby/client"

	"github.com/furimeo/wisper/sasayaki/internal/reconcile"
	"github.com/furimeo/wisper/sasayaki/internal/spec"
)

// Create builds the container for a workload and leaves it stopped.
//
// Everything the container needs comes into existence here and in this order: the image
// has to already be local (EnsureImage is what puts it there), the tenant network and its
// packet filter are made before anything can join them, and the volume directories and
// their quotas are made before the engine is asked to bind them. Starting it is a
// separate decision, taken by the reconcile loop from the workload's desired state, so
// that a container the panel wants stopped is still created, inspectable and ready.
//
// The fingerprint is stamped on as a label and is how the next pass knows whether this
// container still matches the spec. Nothing is read back and compared field by field: the
// engine normalises what it is given - rounding a memory limit, filling in the image's
// entrypoint - and a naive comparison would find a difference every fifteen seconds and
// rebuild a perfectly good container forever (reconcile/fingerprint.go).
func (d *Docker) Create(ctx context.Context, workload spec.Workload, fingerprint string) (reconcile.Container, error) {
	if !workload.IsApp() {
		return reconcile.Container{}, fmt.Errorf("runtime: workload %s is a %s, which has no "+
			"container: a static site is a directory and a symlink", workload.ID, workload.Kind)
	}
	name, err := containerName(workload.ID, workload.Name)
	if err != nil {
		return reconcile.Container{}, err
	}

	ref, err := imageRef(workload)
	if err != nil {
		return reconcile.Container{}, err
	}
	digest, present, err := d.localDigest(ctx, ref)
	if err != nil {
		return reconcile.Container{}, err
	}
	if !present {
		// EnsureImage is what pulls, and the reconcile loop calls it first. Reaching here
		// means the image was removed between the two, so say which one and let the next
		// pass pull it again rather than starting a container from whatever the engine
		// would resolve the tag to now.
		return reconcile.Container{}, fmt.Errorf("runtime: the image %s for workload %s is not "+
			"on this node", ref, workload.ID)
	}

	engineRuntime := d.runtimeFor(ctx, workload.Runtime)
	if engineRuntime != workload.Runtime {
		d.log.Warn("a workload asked for a runtime this node cannot give it",
			slog.String("workload", workload.ID),
			slog.String("requested", string(workload.Runtime)),
			slog.String("using", string(engineRuntime)))
	}

	if err := d.ensureNetwork(ctx, workload.TenantNetwork); err != nil {
		return reconcile.Container{}, err
	}

	exposed, published, err := portsFor(workload)
	if err != nil {
		return reconcile.Container{}, err
	}
	host, err := d.hostConfigFor(ctx, workload, engineRuntime, published)
	if err != nil {
		return reconcile.Container{}, err
	}

	created, err := d.api.ContainerCreate(ctx, client.ContainerCreateOptions{
		Name:             name,
		Config:           configFor(workload, ref, digest, fingerprint, exposed),
		HostConfig:       host,
		NetworkingConfig: networkingFor(workload),
	})
	if err != nil {
		return reconcile.Container{}, fmt.Errorf("runtime: create the container %s for workload %s: %w",
			name, workload.ID, err)
	}
	for _, warning := range created.Warnings {
		// The engine warns about things it accepted and could not honour - a swap limit
		// with no kernel support, an unsupported ulimit. Silence here is how a node ends
		// up applying less than the panel asked for while reporting success.
		d.log.Warn("the engine accepted the container with a warning",
			slog.String("workload", workload.ID),
			slog.String("container", created.ID),
			slog.String("warning", warning))
	}

	d.log.Info("created a container",
		slog.String("workload", workload.ID),
		slog.String("container", created.ID),
		slog.String("image", ref),
		slog.String("runtime", string(engineRuntime)),
		slog.String("network", workload.TenantNetwork))

	return d.describe(ctx, created.ID)
}
