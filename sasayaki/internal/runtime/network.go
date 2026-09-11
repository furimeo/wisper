package runtime

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"log/slog"

	"github.com/moby/moby/client"

	"github.com/furimeo/wisper/sasayaki/internal/reconcile"
)

// One Docker network per tenant, and the reason is the whole point of multi-tenancy.
//
// Containers on Docker's default bridge can all reach each other by address. On a node
// holding forty customers that means any one of them can port-scan the other thirty-nine
// and talk to their Redis, their unauthenticated admin port and their database. A
// user-defined bridge per tenant is what makes that impossible, and it is also what gives
// the edge a name to proxy to: on a user-defined network the engine runs a resolver, so
// `http://<workload-id>:3000` works without anybody tracking IP addresses.
//
// The bridge is IPv4 only, deliberately. Every rule in egress.go is an iptables rule, and
// a network with an IPv6 subnet would need the ip6tables copy of all of them to be in
// place before the first packet - on a host where ip6tables may not exist. Rather than
// have a firewall that is silently half-installed, the tenant networks have no IPv6
// address to leave from. When wisper grows IPv6 for customers it grows the second rule
// set in the same commit.
const (
	bridgeNameOption       = "com.docker.network.bridge.name"
	bridgeICCOption        = "com.docker.network.bridge.enable_icc"
	bridgeMasqueradeOption = "com.docker.network.bridge.enable_ip_masquerade"
	labelTenantNetwork     = "wisper.tenant"
	bridgeNamePrefix       = "wsp"
	bridgeNameHexDigits    = 9 // "wsp" + 9 = 12 characters, inside the kernel's 15-byte limit
	bridgeFallbackPrefix   = "br-"
)

// ensureNetwork makes sure a tenant's network exists and its egress is filtered, then
// remembers that it did so.
//
// Serialised across the whole daemon by one mutex. Two workloads of the same tenant
// converging at once would otherwise both create the network - one of them failing with
// a conflict - and both install firewall rules into the same chain at the same time.
// Creating a network is rare enough that a lock costs nothing.
func (d *Docker) ensureNetwork(ctx context.Context, name string) error {
	d.networks.Lock()
	defer d.networks.Unlock()

	if _, ready := d.networks.ready[name]; ready {
		return nil
	}

	bridge, err := d.findOrCreateNetwork(ctx, name)
	if err != nil {
		return err
	}
	if err := d.filterEgress(ctx, bridge); err != nil {
		return err
	}

	d.networks.ready[name] = struct{}{}
	return nil
}

// findOrCreateNetwork returns the host bridge interface backing a tenant's network,
// creating the network when it is not there.
func (d *Docker) findOrCreateNetwork(ctx context.Context, name string) (string, error) {
	existing, err := d.api.NetworkInspect(ctx, name, client.NetworkInspectOptions{})
	switch {
	case err == nil:
		return bridgeOf(existing.Network.ID, existing.Network.Options), nil
	case !notFound(err):
		return "", fmt.Errorf("runtime: look for the tenant network %s: %w", name, err)
	}

	enabled, disabled := true, false
	created, err := d.api.NetworkCreate(ctx, name, client.NetworkCreateOptions{
		Driver:     "bridge",
		EnableIPv4: &enabled,
		EnableIPv6: &disabled,
		// Not Internal: a customer's application has to be able to reach the internet to
		// install packages and call APIs. What it may not reach is anything private, and
		// that is a packet filter's job rather than a flag's (egress.go).
		Internal: false,
		Labels: map[string]string{
			reconcile.LabelManaged: reconcile.LabelManagedValue,
			labelTenantNetwork:     name,
		},
		Options: map[string]string{
			// Named rather than left to the engine, because the firewall rules are
			// written against this interface and deriving the name from a network id
			// this daemon may not have seen created is one indirection too many between
			// "the rule is installed" and "the rule is on the right interface".
			bridgeNameOption: bridgeName(name),
			// Containers of one tenant may talk to each other. That is what a tenant
			// network is for.
			bridgeICCOption: "true",
			// NAT out, so the internet works.
			bridgeMasqueradeOption: "true",
		},
	})
	if err != nil {
		// Another daemon - or another goroutine that got here first on a previous
		// version of this code - may have created it between the inspect and now.
		if second, retry := d.api.NetworkInspect(ctx, name, client.NetworkInspectOptions{}); retry == nil {
			return bridgeOf(second.Network.ID, second.Network.Options), nil
		}
		return "", fmt.Errorf("runtime: create the tenant network %s: %w", name, err)
	}

	d.log.Info("created a tenant network",
		slog.String("network", name),
		slog.String("bridge", bridgeName(name)),
		slog.String("id", created.ID))
	return bridgeOf(created.ID, nil), nil
}

// bridgeOf is the host interface a network's traffic crosses.
//
// The option is read first because a network created by an older version of this daemon,
// or by an operator by hand, may not carry the name this one would have chosen. Falling
// back to the engine's own convention - br- followed by the first twelve characters of
// the network id - is what makes an existing installation keep working rather than having
// its firewall rules attached to an interface that does not exist.
func bridgeOf(networkID string, options map[string]string) string {
	if name := options[bridgeNameOption]; name != "" {
		return name
	}
	if len(networkID) >= 12 {
		return bridgeFallbackPrefix + networkID[:12]
	}
	return bridgeFallbackPrefix + networkID
}

// bridgeName is the interface name for a tenant network.
//
// Hashed rather than derived from the tenant name because the kernel allows fifteen bytes
// for an interface name and a tenant network is called something like
// "wisper-tenant-1042" already. The hash is deterministic, so the same tenant gets the
// same interface on every node and in every log line.
func bridgeName(network string) string {
	digest := sha256.Sum256([]byte(network))
	return bridgeNamePrefix + hex.EncodeToString(digest[:])[:bridgeNameHexDigits]
}
