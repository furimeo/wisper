package bootstrap

import (
	"context"
	"fmt"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// What "can this machine reach the internet" is asked by reaching.
//
// The address is not arbitrary and is not a ping target of convenience: it is the ACME
// directory Caddy will fetch every certificate from. Testing the thing the node actually
// needs means a pass says something true, and a failure names the feature that will not
// work rather than a general feeling about the network.
const (
	acmeDirectoryHost = "acme-v02.api.letsencrypt.org:443"
	outboundTimeout   = 6 * time.Second
)

// checkOutbound asks whether certificates will be obtainable.
//
// Advisory, because an air-gapped node is a supported installation: design section 7.1
// describes downloading the binary by hand and enrolling offline, and a node serving only
// internal names over a certificate somebody else provides never talks to Let's Encrypt.
// What is not supported is finding out at the first customer domain.
func checkOutbound(ctx context.Context, m *machine, _ *wisperpb.MachineFacts) []*wisperpb.DoctorCheck {
	ctx, cancel := context.WithTimeout(ctx, outboundTimeout)
	defer cancel()

	connection, err := m.dial(ctx, "tcp", acmeDirectoryHost)
	if err != nil {
		return []*wisperpb.DoctorCheck{check("network.outbound", "Outbound internet",
			severityAdvisory, outcomeWarn,
			fmt.Sprintf("%s could not be reached: %v. Automatic certificates will not be "+
				"issued on this node", acmeDirectoryHost, err),
			"Allow outbound TCP 443 and check DNS resolution. An offline node is a "+
				"supported installation, but it cannot obtain certificates and every "+
				"domain on it needs one supplied by hand.")}
	}
	connection.Close()

	return []*wisperpb.DoctorCheck{passed("network.outbound", "Outbound internet",
		severityAdvisory, acmeDirectoryHost+" is reachable")}
}
