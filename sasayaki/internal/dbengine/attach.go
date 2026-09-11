package dbengine

import (
	"context"
	"fmt"
	"log/slog"
	"sort"
	"strings"

	"github.com/moby/moby/api/types/network"
	"github.com/moby/moby/client"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
)

// Making the server reachable from the containers that have to use it.
//
// This is the part that is easy to get wrong by assuming Docker's defaults help. They do not:
//
//   - A customer's container is on a tenant bridge of its own, and every tenant bridge drops
//     new connections to 10/8, 172.16/12, 192.168/16 and 127/8 (runtime/egress.go). So a port
//     published on the host is not reachable from a workload even though it is reachable from
//     the operator's laptop, and a connection string pointing at the host's address would
//     time out for every customer while looking perfectly correct.
//   - The one destination those rules do let through is the tenant's own bridge. So the
//     server joins each tenant network that has a workload on this node, gets an address on
//     it, and is resolvable there by its container name - which is what the panel puts in the
//     connection string.
//
// Nothing is ever disconnected. A tenant whose last workload left the node may have a spec
// that brings one back in a minute, and a database connection dropped in between is an outage
// for the sake of tidiness. The endpoints go away with the container.
//
// # What the wire does not say
//
// DatabaseGrant carries no tenant, so the node cannot tell which customer a grant belongs to
// and therefore cannot restrict a server to one tenant's network. Every server is joined to
// every tenant network on the node. That is safe and is what a shared server means anyway -
// isolation between two customers on one PostgreSQL is the SQL privileges in postgresgrant.go,
// not the reachability of the port - but it does mean a dedicated instance is network-reachable
// by tenants who have no login on it. Adding `engine_id` to DatabaseGrant, or a tenant to it,
// is what would let this be narrowed.

// attachTenants joins every server to every tenant network on this node.
//
// A network that does not exist yet is not an error. The runtime package creates a tenant
// bridge when it creates the first container on it, so on a node whose spec has just arrived
// the workload and the server are being created in the same fifteen seconds and the order is
// not fixed. The next pass joins it.
func (e *Engines) attachTenants(ctx context.Context, desired spec.Spec, wanted []instance) error {
	networks := tenantNetworks(desired)
	if len(networks) == 0 || len(wanted) == 0 {
		return nil
	}

	// Re-read rather than reusing the map from the start of the pass: containers created a
	// moment ago are not in it, and they are exactly the ones with no networks yet.
	existing, err := e.containers(ctx)
	if err != nil {
		return err
	}

	problems := make([]string, 0, 2)
	for _, built := range wanted {
		server, present := existing[built.ID]
		if !present {
			continue
		}
		for _, name := range networks {
			if server.Networks[name] {
				continue
			}
			if err := e.join(ctx, built, server.ID, name); err != nil {
				problems = append(problems, err.Error())
			}
		}
	}
	if len(problems) > 0 {
		return fmt.Errorf("dbengine: %s", strings.Join(problems, "; "))
	}
	return nil
}

// join connects one server to one tenant network.
func (e *Engines) join(ctx context.Context, built instance, containerID, tenantNetwork string) error {
	_, err := e.engine.NetworkConnect(ctx, tenantNetwork, client.NetworkConnectOptions{
		Container: containerID,
		EndpointConfig: &network.EndpointSettings{
			// The container name is already resolvable on a user-defined network; the alias
			// makes it resolvable under the same name even if the container is ever renamed,
			// which matters because the panel has handed that name to the customer inside a
			// connection string.
			Aliases: []string{built.Name},
		},
	})
	if err != nil {
		if isNotFound(err) {
			// The tenant's first workload has not been created yet, so its bridge does not
			// exist. Not a failure: the next pass will find it.
			e.log.Debug("a tenant network the database server should join does not exist yet",
				slog.String("instance", built.ID),
				slog.String("network", tenantNetwork))
			return nil
		}
		return fmt.Errorf("join the %s server %s to the tenant network %s: %w",
			built.Kind, built.ID, tenantNetwork, err)
	}

	e.log.Info("joined a database server to a tenant network",
		slog.String("instance", built.ID),
		slog.String("kind", string(built.Kind)),
		slog.String("network", tenantNetwork))
	return nil
}

// tenantNetworks is every distinct tenant network named by a workload in the spec, sorted.
//
// Taken from the workloads rather than from Docker, because the spec is the statement of which
// tenants belong on this node - a bridge left behind by a tenant whose last workload moved
// away is not one a server should still be joined to when it is recreated.
func tenantNetworks(desired spec.Spec) []string {
	seen := make(map[string]bool, len(desired.Workloads))
	for _, workload := range desired.Workloads {
		if workload.TenantNetwork == "" {
			continue
		}
		seen[workload.TenantNetwork] = true
	}

	names := make([]string, 0, len(seen))
	for name := range seen {
		names = append(names, name)
	}
	sort.Strings(names)
	return names
}
