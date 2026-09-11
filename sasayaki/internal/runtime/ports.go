package runtime

import (
	"fmt"
	"net/netip"
	"strconv"

	"github.com/moby/moby/api/types/network"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
)

// Publishing a port on the host is the exception here, not the rule.
//
// HTTP arrives through the Caddy embedded in this daemon, which reaches a container over
// the tenant network by name and needs no host port at all. A published port is a hole
// in that arrangement: it is reachable from the whole internet, it collides with the
// other tenants on the machine, and it bypasses the edge's TLS. It exists for the
// workload that genuinely wants a raw socket - a game server, a Minecraft instance - and
// the panel is the one that decides.
//
// Host port zero means "do not publish", and that is the value the panel sends for
// everything ordinary. The container port is still declared, because a declared port is
// what `docker inspect` and the edge's own health check read.
func portsFor(workload spec.Workload) (network.PortSet, network.PortMap, error) {
	exposed := make(network.PortSet, len(workload.Ports))
	published := make(network.PortMap)

	for _, wanted := range workload.Ports {
		port, err := enginePort(wanted)
		if err != nil {
			return nil, nil, fmt.Errorf("runtime: workload %s: %w", workload.ID, err)
		}
		exposed[port] = struct{}{}

		if !wanted.IsPublished() {
			continue
		}
		if wanted.Host > 65535 {
			return nil, nil, fmt.Errorf("runtime: workload %s asks to publish on host port %d, "+
				"which is not a port number", workload.ID, wanted.Host)
		}

		binding := network.PortBinding{HostPort: strconv.FormatUint(uint64(wanted.Host), 10)}
		if wanted.HostIP != "" {
			address, err := netip.ParseAddr(wanted.HostIP)
			if err != nil {
				return nil, nil, fmt.Errorf("runtime: workload %s asks to publish on %q, which "+
					"is not an IP address: %w", workload.ID, wanted.HostIP, err)
			}
			binding.HostIP = address
		}
		published[port] = append(published[port], binding)
	}

	if len(exposed) == 0 {
		exposed = nil
	}
	if len(published) == 0 {
		published = nil
	}
	return exposed, published, nil
}

// enginePort turns a spec port into the engine's own port type.
func enginePort(wanted spec.Port) (network.Port, error) {
	if wanted.Container == 0 || wanted.Container > 65535 {
		return network.Port{}, fmt.Errorf("container port %d is not a port number", wanted.Container)
	}
	protocol := network.TCP
	if wanted.Protocol == spec.ProtocolUDP {
		protocol = network.UDP
	}
	port, ok := network.PortFrom(uint16(wanted.Container), protocol)
	if !ok {
		return network.Port{}, fmt.Errorf("container port %d/%s could not be represented",
			wanted.Container, wanted.Protocol)
	}
	return port, nil
}
