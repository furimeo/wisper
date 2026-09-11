package edge

import (
	"context"
	"errors"
	"net/netip"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/moby/moby/api/types/container"
	"github.com/moby/moby/api/types/network"
	"github.com/moby/moby/client"

	"github.com/furimeo/wisper/sasayaki/internal/reconcile"
)

// Resolving a workload id to an address on a tenant bridge.

// fakeEngine answers container listings.
type fakeEngine struct {
	mu      sync.Mutex
	items   []container.Summary
	failure error
	calls   int
	// filters records what was asked for, so the test can prove the query is narrow
	// enough not to pick up somebody else's containers.
	filters client.Filters
	// block, when set, holds every call until it is closed. Used to prove that a burst
	// of callers produces one engine call rather than one each.
	block chan struct{}
}

func (f *fakeEngine) ContainerList(_ context.Context, options client.ContainerListOptions) (client.ContainerListResult, error) {
	f.mu.Lock()
	blocking := f.block
	f.calls++
	f.filters = options.Filters
	f.mu.Unlock()

	if blocking != nil {
		<-blocking
	}

	f.mu.Lock()
	defer f.mu.Unlock()
	if f.failure != nil {
		return client.ContainerListResult{}, f.failure
	}
	return client.ContainerListResult{Items: append([]container.Summary(nil), f.items...)}, nil
}

func managedContainer(workloadID, tenantNetwork, address string) container.Summary {
	return container.Summary{
		ID:     "container-" + workloadID,
		Labels: map[string]string{reconcile.LabelManaged: "true", reconcile.LabelWorkload: workloadID},
		NetworkSettings: &container.NetworkSettingsSummary{
			Networks: map[string]*network.EndpointSettings{
				tenantNetwork: {IPAddress: netip.MustParseAddr(address)},
			},
		},
	}
}

func TestDockerBackendsResolvesAWorkload(t *testing.T) {
	engine := &fakeEngine{items: []container.Summary{
		managedContainer("42", "wisper-tenant-7", "172.20.0.5"),
	}}
	backends, err := NewDockerBackends(engine)
	if err != nil {
		t.Fatalf("building the resolver: %v", err)
	}

	address, err := backends.Address(context.Background(), "42", 3000)
	if err != nil {
		t.Fatalf("resolving a running container: %v", err)
	}
	if address.String() != "172.20.0.5:3000" {
		t.Fatalf("resolved to %s, want the container's address on its tenant network", address)
	}

	labels := engine.filters["label"]
	if !labels[reconcile.LabelWorkload] {
		t.Fatalf("the engine was asked without a workload label filter (%v), which would "+
			"pick up the shared database engines and anything else on the machine", labels)
	}
	if !labels[reconcile.LabelManaged+"="+reconcile.LabelManagedValue] {
		t.Fatalf("the engine was asked without the managed label filter (%v), so a "+
			"container somebody else put on the node could be proxied to", labels)
	}
	if !engine.filters["status"]["running"] {
		t.Fatal("stopped containers were included, and the address one had before it " +
			"stopped points into a bridge that no longer answers")
	}
}

func TestDockerBackendsSaysWhichWorkloadIsMissing(t *testing.T) {
	backends, err := NewDockerBackends(&fakeEngine{})
	if err != nil {
		t.Fatalf("building the resolver: %v", err)
	}

	_, err = backends.Address(context.Background(), "42", 3000)
	if err == nil {
		t.Fatal("a workload with no container resolved to something")
	}
	if !strings.Contains(err.Error(), "42") {
		t.Fatalf("the error does not name the workload, so a 502 page cannot either: %v", err)
	}
}

func TestDockerBackendsPassesTheEnginesFailureOn(t *testing.T) {
	// "Cannot ask" is not "not there". The message has to reach the page a visitor sees,
	// because a Docker daemon that is restarting looks nothing like a container that was
	// never deployed.
	engine := &fakeEngine{failure: errors.New("cannot connect to the Docker daemon")}
	backends, _ := NewDockerBackends(engine)

	_, err := backends.Address(context.Background(), "42", 3000)
	if err == nil || !strings.Contains(err.Error(), "cannot connect to the Docker daemon") {
		t.Fatalf("the engine's own failure did not survive: %v", err)
	}
}

func TestDockerBackendsCachesForOnlyAMoment(t *testing.T) {
	engine := &fakeEngine{items: []container.Summary{
		managedContainer("42", "wisper-tenant-7", "172.20.0.5"),
	}}
	backends, _ := NewDockerBackends(engine)

	now := time.Now()
	backends.now = func() time.Time { return now }

	for range 5 {
		if _, err := backends.Address(context.Background(), "42", 3000); err != nil {
			t.Fatalf("resolving: %v", err)
		}
	}
	if engine.calls != 1 {
		t.Fatalf("the engine was asked %d times for five dials in the same moment", engine.calls)
	}

	// A container that came back on a different address after a restart. The reconcile
	// loop has no reason to have noticed - same container id, same spec - so the only
	// thing that fixes it is the table expiring.
	engine.mu.Lock()
	engine.items = []container.Summary{managedContainer("42", "wisper-tenant-7", "172.20.0.9")}
	engine.mu.Unlock()
	now = now.Add(addressTableLifetime + time.Second)

	address, err := backends.Address(context.Background(), "42", 3000)
	if err != nil {
		t.Fatalf("resolving after the table expired: %v", err)
	}
	if address.Addr().String() != "172.20.0.9" {
		t.Fatalf("still resolving to %s after the container moved", address)
	}
}

func TestDockerBackendsAsksTheEngineOnceForABurst(t *testing.T) {
	engine := &fakeEngine{
		items: []container.Summary{managedContainer("42", "wisper-tenant-7", "172.20.0.5")},
		block: make(chan struct{}),
	}
	backends, _ := NewDockerBackends(engine)

	const callers = 12
	var waiting sync.WaitGroup
	results := make(chan error, callers)
	for range callers {
		waiting.Add(1)
		go func() {
			defer waiting.Done()
			_, err := backends.Address(context.Background(), "42", 3000)
			results <- err
		}()
	}

	// Let them all pile up behind the one engine call, then release it.
	time.Sleep(20 * time.Millisecond)
	close(engine.block)
	waiting.Wait()
	close(results)

	for err := range results {
		if err != nil {
			t.Fatalf("a caller in the burst failed: %v", err)
		}
	}
	engine.mu.Lock()
	calls := engine.calls
	engine.mu.Unlock()
	if calls > 2 {
		t.Fatalf("the engine was asked %d times by %d simultaneous callers", calls, callers)
	}
}

func TestDockerBackendsIgnoresAContainerWithNoAddress(t *testing.T) {
	// A container that exists and has not been attached to its network yet. Reporting
	// nowhere to send bytes is right; inventing somewhere is not.
	summary := managedContainer("42", "wisper-tenant-7", "172.20.0.5")
	summary.NetworkSettings = nil
	backends, _ := NewDockerBackends(&fakeEngine{items: []container.Summary{summary}})

	if _, err := backends.Address(context.Background(), "42", 3000); err == nil {
		t.Fatal("a container with no network endpoint resolved to an address")
	}
}

func TestNewDockerBackendsRefusesNoEngine(t *testing.T) {
	if _, err := NewDockerBackends(nil); err == nil {
		t.Fatal("a resolver with nothing to ask was built")
	}
}
