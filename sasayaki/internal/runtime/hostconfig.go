package runtime

import (
	"context"
	"fmt"
	"net/netip"
	"strconv"
	"sync"

	"github.com/moby/moby/api/types/container"
	"github.com/moby/moby/api/types/network"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
)

// Container log rotation, if nobody has said otherwise.
//
// These are the same numbers the panel's own defaults use
// (docs/contracts/node-spec.md section 3.11). They are here as well because the spec's
// RetentionPolicy travels on the whole document while a container is created from one
// workload, and a node that had not been told yet must still not let a chatty container
// fill its disk overnight - which is the classic way a hosting node dies at 4am.
const (
	defaultLogMaxBytes int64 = 64 << 20
	defaultLogMaxFiles int32 = 3
)

// retentionState is the spec's log rotation policy, once the node has been told it.
type retentionState struct {
	sync.Mutex
	maxBytes int64
	maxFiles int32
}

// UseRetention records the log rotation policy from the current spec.
//
// Called by whoever holds the spec, once per generation. It is a separate call rather
// than an argument to Create because rotation is a property of the whole node and Create
// is handed one workload; the reconcile loop is the only thing that sees both.
//
// It applies to containers created after it. An existing container keeps the policy it
// was created with, because changing it means recreating the container and taking the
// customer's logs with it - a far worse outcome than a container that rotates at the old
// size until the next time its own configuration changes.
func (d *Docker) UseRetention(retention spec.Retention) {
	d.retention.Lock()
	defer d.retention.Unlock()
	if retention.ContainerLogMaxBytes > 0 {
		d.retention.maxBytes = retention.ContainerLogMaxBytes
	}
	if retention.ContainerLogMaxFiles > 0 {
		d.retention.maxFiles = retention.ContainerLogMaxFiles
	}
}

// logConfig is the json-file driver with rotation turned on.
//
// json-file rather than journald or a remote driver because the log feed reads back
// through the Engine API (logs.go), and only the file drivers support `docker logs` with
// history and following at the same time. Rotation is not optional: the default is an
// unbounded file, and an unrotated container log is the classic way a node fills its
// disk overnight.
func (d *Docker) logConfig() container.LogConfig {
	d.retention.Lock()
	defer d.retention.Unlock()

	maxBytes := d.retention.maxBytes
	if maxBytes <= 0 {
		maxBytes = defaultLogMaxBytes
	}
	maxFiles := d.retention.maxFiles
	if maxFiles <= 0 {
		maxFiles = defaultLogMaxFiles
	}
	return container.LogConfig{
		Type: "json-file",
		Config: map[string]string{
			"max-size": strconv.FormatInt(maxBytes, 10),
			"max-file": strconv.FormatInt(int64(maxFiles), 10),
		},
	}
}

// hostConfigFor is the non-portable half: the ceilings, the isolation and the machine
// this container is actually going to run on.
func (d *Docker) hostConfigFor(
	ctx context.Context,
	workload spec.Workload,
	engineRuntime spec.Runtime,
	published network.PortMap,
) (*container.HostConfig, error) {
	if workload.TenantNetwork == "" {
		return nil, fmt.Errorf("runtime: workload %s has no tenant network, and putting it on "+
			"the default bridge would let every other customer on this node reach it by address",
			workload.ID)
	}
	if err := checkIdentifier("tenant network", workload.TenantNetwork); err != nil {
		return nil, err
	}

	mounts, err := d.mountsFor(ctx, workload)
	if err != nil {
		return nil, err
	}

	capability, err := d.capabilities(ctx)
	if err != nil {
		return nil, err
	}

	host := &container.HostConfig{
		Resources:     resourcesFor(workload.Limits),
		RestartPolicy: restartFor(workload.Restart),
		LogConfig:     d.logConfig(),
		NetworkMode:   container.NetworkMode(workload.TenantNetwork),
		PortBindings:  published,
		Mounts:        mounts,

		// Explicit DNS rather than relying on Docker's embedded resolver at 127.0.0.11.
		// The egress filter blocks 127.0.0.0/8 (the host's loopback, where an
		// unauthenticated admin port lives), and Docker's embedded resolver sits inside
		// that range — so without explicit DNS, a container's DNS queries are dropped
		// and apt-get / pip / npm can resolve nothing.
		DNS: mustParseDNS("8.8.8.8", "1.1.1.1"),

		// Removal is this daemon's decision, taken by the reconcile loop when a workload
		// leaves the spec. A container the engine deleted on exit would take its logs
		// and its exit code with it, and the customer asking why their job stopped would
		// be told nothing at all.
		AutoRemove: false,
	}

	harden(host, workload, engineRuntime, capability.initBinary)
	return host, nil
}

// networkingFor attaches the container to its tenant network with names the edge can
// reach it by.
//
// The workload id is the important one: it is stable for the life of the service, so the
// route table can point at `http://<id>:<port>` and keep working across a rename. The
// slug is added because an operator debugging from another container in the same tenant
// would rather type the name.
func networkingFor(workload spec.Workload) *network.NetworkingConfig {
	aliases := []string{workload.ID}
	if readable := slug(workload.Name); readable != "" && readable != workload.ID {
		aliases = append(aliases, readable)
	}
	return &network.NetworkingConfig{
		EndpointsConfig: map[string]*network.EndpointSettings{
			workload.TenantNetwork: {Aliases: aliases},
		},
	}
}

// mustParseDNS converts IP strings to netip.Addr for the HostConfig.DNS field.
// Panics on invalid IPs — these are hardcoded constants, not user input.
func mustParseDNS(ips ...string) []netip.Addr {
	result := make([]netip.Addr, len(ips))
	for i, ip := range ips {
		addr, err := netip.ParseAddr(ip)
		if err != nil {
			panic(fmt.Sprintf("runtime: invalid DNS server %q: %v", ip, err))
		}
		result[i] = addr
	}
	return result
}
