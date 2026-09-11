package runtime

import (
	"context"
	"fmt"
	"log/slog"
	"sync/atomic"
)

// Where a customer's container may not send a packet.
//
// The internet stays open - an application has to be able to install packages and call
// APIs - and everything private is closed. The list is short and every entry is on it for
// a reason somebody has been burned by:
//
//	169.254.0.0/16   the cloud metadata endpoint. 169.254.169.254 is AWS, GCP, Azure,
//	                 DigitalOcean, Oracle and Hetzner; 169.254.170.2 is ECS task
//	                 credentials. A single unauthenticated HTTP GET from inside any
//	                 container returns the host's cloud credentials, and it is reachable
//	                 from every language's default HTTP client with no library needed.
//	                 This is the entry that matters most.
//	100.64.0.0/10    carrier-grade NAT, which is also Alibaba's metadata endpoint
//	                 (100.100.100.200) and the range Tailscale hands out - so a node on
//	                 the operator's tailnet does not become a route into it.
//	10/8, 172.16/12, 192.168/16
//	                 RFC 1918. The operator's own network: the database that is not
//	                 supposed to be on the internet, the other nodes, the panel, the
//	                 hypervisor's management interface. Also the Docker bridge subnets,
//	                 which is how one tenant would otherwise reach another's containers
//	                 even with a network of their own.
//	127.0.0.0/8      the host's loopback, where an unauthenticated admin port lives on
//	                 approximately every machine.
//	0.0.0.0/8        "this network", which some stacks route to the local host.
//	192.0.0.0/24     IETF protocol assignments, including the NAT64 discovery prefix.
//	198.18.0.0/15    the benchmarking range, widely used for internal addressing.
//	224.0.0.0/4      multicast: service discovery on the operator's LAN.
//	240.0.0.0/4      reserved, and the broadcast address at the end of it.
//
// IPv6 is absent from this list, and that is a property of network.go rather than an
// omission: tenant networks are created IPv4-only, so a container has no global IPv6
// address and no route to leave by. A firewall that is half installed - v4 filtered, v6
// open because ip6tables was missing - is worse than one whose scope is written down.
var blockedDestinations = []string{
	"0.0.0.0/8",
	"10.0.0.0/8",
	"100.64.0.0/10",
	"127.0.0.0/8",
	"169.254.0.0/16",
	"172.16.0.0/12",
	"192.0.0.0/24",
	"192.168.0.0/16",
	"198.18.0.0/15",
	"224.0.0.0/4",
	"240.0.0.0/4",
}

// egressFiltered records whether every tenant network this daemon has touched really got
// its rules. Reported to the panel through Isolation, because a node that could not
// install its packet filter is a node an operator has to be told about rather than one
// that quietly hands out unfiltered containers.
type egressFiltered struct{ atomic.Bool }

// filterEgress installs the rules for one tenant bridge.
//
// The chain is per bridge, and its first two rules are what keep the platform working
// while the rest of it keeps the platform safe:
//
//  1. RELATED,ESTABLISHED returns immediately. Without it the reply from a container to
//     the embedded Caddy - which lives on the host, at the bridge's own gateway address,
//     inside 172.16/12 - would be dropped by rule three, and every site on the node would
//     time out. The same rule is what lets a published port answer a client on the
//     operator's LAN.
//  2. Traffic leaving on the same bridge returns. Two containers of one tenant are meant
//     to be able to talk to each other; that is what a tenant network is for, and their
//     addresses are inside the very ranges the drops below cover.
//
// Everything after that is a new connection from a container to somewhere private, which
// is the thing being prevented.
func (d *Docker) filterEgress(ctx context.Context, bridge string) error {
	chain := egressChain(bridge)
	err := d.syncChain(ctx, chain, egressRules(bridge))
	if err == nil {
		// DOCKER-USER is the chain the engine jumps to first from FORWARD, and the one
		// place a rule survives the engine rewriting its own. INPUT is here as well
		// because DOCKER-USER never sees a packet addressed to the host itself, and the
		// host is the most interesting private address a container can reach.
		err = d.jumpTo(ctx, "DOCKER-USER", bridge, chain)
	}
	if err == nil {
		err = d.jumpTo(ctx, "INPUT", bridge, chain)
	}

	if err != nil {
		if !d.dev {
			d.egress.Store(false)
			return fmt.Errorf("runtime: egress from the tenant bridge %s could not be filtered, "+
				"so a container there could reach this network's private addresses and the "+
				"cloud metadata endpoint; refusing to start it: %w", bridge, err)
		}
		d.egress.Store(false)
		d.log.Warn("egress filtering could not be installed and --dev was given, so containers "+
			"on this bridge can reach private addresses and the cloud metadata endpoint",
			slog.String("bridge", bridge),
			slog.String("error", err.Error()))
		return nil
	}

	d.log.Debug("egress filtered", slog.String("bridge", bridge), slog.String("chain", chain))
	return nil
}

// egressRules is the chain's contents, in the order they have to be in.
//
// A pure function of the bridge name so the whole policy can be read in one place and
// asserted on in a test, on a machine with no iptables. The order is the substance: the
// two RETURNs come first or the platform does not work, and the DROPs come after or it is
// not safe.
func egressRules(bridge string) [][]string {
	rules := make([][]string, 0, len(blockedDestinations)+2)
	rules = append(rules,
		[]string{"-m", "conntrack", "--ctstate", "RELATED,ESTABLISHED", "-j", "RETURN"},
		[]string{"-o", bridge, "-j", "RETURN"})
	for _, destination := range blockedDestinations {
		rules = append(rules, []string{"-d", destination, "-j", "DROP"})
	}
	return rules
}

// egressChain is the iptables chain holding one bridge's rules. The bridge name is
// already a twelve-character hash, so the chain name is nineteen and inside iptables'
// own twenty-eight-character limit.
func egressChain(bridge string) string {
	return "WISPER-" + bridge
}
