package edge

import (
	"context"
	"errors"
	"fmt"
	"net/netip"
	"sync"
	"time"

	"github.com/moby/moby/api/types/container"
	"github.com/moby/moby/client"

	"github.com/furimeo/wisper/sasayaki/internal/reconcile"
)

// Turning a workload id into an address on a tenant bridge.
//
// A container joins its tenant's Docker network under an alias equal to its workload id,
// and anything else inside that network can reach it by that name because the engine runs
// a resolver there. This daemon is not inside it - it is a process on the host - so the
// name has to become an address, and the engine is the only thing that knows the mapping.
//
// One list call answers for every workload on the node at once, which is why this is a
// table with a short lifetime rather than a lookup per route: a node serving two hundred
// hostnames would otherwise ask the engine two hundred times per reconcile pass.
//
// The lifetime is short on purpose. A container the engine restarted can come back on a
// different address, and the reconcile loop has no reason to rebuild the route table for
// that - the container's id did not change and neither did the spec. Two seconds of
// staleness costs at most one failed dial; caching for a minute would cost an outage
// nobody could explain.

const addressTableLifetime = 2 * time.Second

// containerEngine is the part of the Docker Engine API this file uses, declared here so a
// test can answer for it. *client.Client satisfies it.
//
// One method. Listing is enough because the summary the engine returns already carries
// the labels and the network endpoints, so there is nothing here that needs to inspect a
// container - and an interface with one method is a statement that this file cannot
// start, stop or remove anything.
type containerEngine interface {
	ContainerList(ctx context.Context, options client.ContainerListOptions) (client.ContainerListResult, error)
}

// DockerBackends resolves app routes against the containers the engine reports.
type DockerBackends struct {
	engine containerEngine
	now    func() time.Time

	// Guards the cached table. Held only across the map read or the swap, never across
	// the engine call: a slow socket must not stop every other request on the node.
	mu        sync.Mutex
	addresses map[string]netip.Addr
	readAt    time.Time
	// inflight is the single engine call the waiting callers share, so a burst of
	// requests after the table expires produces one list rather than one each.
	inflight *sync.WaitGroup
	failure  error
}

// NewDockerBackends builds the resolver on an engine connection.
func NewDockerBackends(engine containerEngine) (*DockerBackends, error) {
	if engine == nil {
		return nil, errors.New("edge: a container address resolver needs an engine to ask")
	}
	return &DockerBackends{engine: engine, now: time.Now, addresses: make(map[string]netip.Addr)}, nil
}

// Address is where to send bytes for a workload's container.
func (d *DockerBackends) Address(ctx context.Context, workloadID string, port uint16) (netip.AddrPort, error) {
	address, err := d.lookup(ctx, workloadID)
	if err != nil {
		return netip.AddrPort{}, err
	}
	return netip.AddrPortFrom(address, port), nil
}

func (d *DockerBackends) lookup(ctx context.Context, workloadID string) (netip.Addr, error) {
	if address, fresh := d.cached(workloadID); fresh {
		if !address.IsValid() {
			return netip.Addr{}, fmt.Errorf("no container for %s is running on this node", workloadID)
		}
		return address, nil
	}

	if err := d.refresh(ctx); err != nil {
		return netip.Addr{}, err
	}

	d.mu.Lock()
	address, known := d.addresses[workloadID]
	d.mu.Unlock()
	if !known || !address.IsValid() {
		return netip.Addr{}, fmt.Errorf("no container for %s is running on this node", workloadID)
	}
	return address, nil
}

// cached reads the table, and says whether it is young enough to trust.
func (d *DockerBackends) cached(workloadID string) (netip.Addr, bool) {
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.now().Sub(d.readAt) > addressTableLifetime {
		return netip.Addr{}, false
	}
	return d.addresses[workloadID], true
}

// refresh asks the engine for every managed container and rebuilds the table.
//
// Callers that arrive while a refresh is in flight wait for it rather than starting their
// own. On a node coming back from an engine restart that is the difference between one
// list call and one per connection a browser is opening.
func (d *DockerBackends) refresh(ctx context.Context) error {
	d.mu.Lock()
	if waiting := d.inflight; waiting != nil {
		d.mu.Unlock()
		waiting.Wait()
		d.mu.Lock()
		failure := d.failure
		d.mu.Unlock()
		return failure
	}
	inflight := new(sync.WaitGroup)
	inflight.Add(1)
	d.inflight = inflight
	d.mu.Unlock()

	addresses, err := d.list(ctx)

	d.mu.Lock()
	d.failure = err
	if err == nil {
		d.addresses = addresses
		d.readAt = d.now()
	}
	d.inflight = nil
	d.mu.Unlock()

	inflight.Done()
	return err
}

// list is the engine call.
//
// Filtered to this daemon's own workload containers by the two labels the reconciler
// stamps on them. Running only: a stopped container has no address, and returning the one
// it had would send a visitor's request into a bridge that no longer answers.
func (d *DockerBackends) list(ctx context.Context) (map[string]netip.Addr, error) {
	listed, err := d.engine.ContainerList(ctx, client.ContainerListOptions{
		Filters: make(client.Filters).
			Add("label", reconcile.LabelManaged+"="+reconcile.LabelManagedValue).
			Add("label", reconcile.LabelWorkload).
			Add("status", "running"),
	})
	if err != nil {
		return nil, fmt.Errorf("ask the container engine where this node's containers are: %w", err)
	}

	addresses := make(map[string]netip.Addr, len(listed.Items))
	for _, summary := range listed.Items {
		workloadID := summary.Labels[reconcile.LabelWorkload]
		if workloadID == "" {
			continue
		}
		if address, found := endpointAddress(summary); found {
			addresses[workloadID] = address
		}
	}
	return addresses, nil
}

// endpointAddress is the container's address on the network it joined.
//
// A workload joins exactly one network - its tenant's - so there is no choice to make in
// the ordinary case. When there is more than one the lowest-sorting network name wins,
// which is arbitrary but stable: an address that changed between two requests for no
// reason would be far worse than one that is consistently the wrong endpoint on a
// container nothing on this platform creates.
func endpointAddress(summary container.Summary) (netip.Addr, bool) {
	if summary.NetworkSettings == nil {
		return netip.Addr{}, false
	}

	best := netip.Addr{}
	bestNetwork := ""
	for name, endpoint := range summary.NetworkSettings.Networks {
		if endpoint == nil || !endpoint.IPAddress.IsValid() {
			continue
		}
		if bestNetwork == "" || name < bestNetwork {
			best, bestNetwork = endpoint.IPAddress, name
		}
	}
	return best, bestNetwork != ""
}

var _ Backends = (*DockerBackends)(nil)
