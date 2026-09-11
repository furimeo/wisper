package rpc

import (
	"fmt"
	"net"
	"net/url"
	"strings"
)

// Endpoint is the panel, resolved from whatever an operator typed after --panel.
//
// It exists because that flag is written by a person once, at install time, and every
// forgiving thing this parser does - defaulting the port, accepting a bare host - is a
// support ticket that never happens. Every unforgiving thing it does is a mistake that
// fails at enrolment instead of at three in the morning.
type Endpoint struct {
	// Target is what gRPC dials: "host:port", always with the port filled in.
	Target string

	// ServerName is the host on its own, used for SNI. Go omits SNI when this is an IP
	// literal, which is correct: an address cannot appear in a certificate's SNI.
	ServerName string

	// Plaintext means the operator asked for http:// - no TLS, and therefore no
	// certificate to pin. Only the panel's own gRPC port speaks this, and only when it
	// is reached over something already private: a development machine, a Tailscale
	// address, a tunnel that terminates TLS in front of it.
	Plaintext bool
}

// Ports. 443 for TLS because the panel is behind a tunnel and that is where a tunnel
// listens; 9090 for plaintext because that is wisper.grpc.port, which is the only thing
// a plaintext endpoint can sensibly be pointing at.
const (
	defaultTLSPort       = "443"
	defaultPlaintextPort = "9090"
)

// ParseEndpoint turns the --panel value into somewhere to dial.
//
// Accepted:
//
//	https://panel.example            -> panel.example:443, TLS
//	https://panel.example:9443       -> panel.example:9443, TLS
//	panel.example                    -> panel.example:443, TLS (https is assumed)
//	http://10.1.0.4:9090             -> 10.1.0.4:9090, no TLS
//
// Refused: any other scheme, a path, a query, credentials in the URL. gRPC has no use
// for them, and silently ignoring the "/api" somebody pasted would send a node
// somewhere it was not told to go.
func ParseEndpoint(raw string) (Endpoint, error) {
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" {
		return Endpoint{}, fmt.Errorf("panel endpoint is empty")
	}

	// A bare "panel.example" is what people type, and https is the only safe reading of
	// it: assuming plaintext would silently drop the pin.
	if !strings.Contains(trimmed, "://") {
		trimmed = "https://" + trimmed
	}

	parsed, err := url.Parse(trimmed)
	if err != nil {
		return Endpoint{}, fmt.Errorf("panel endpoint %q is not a URL: %w", raw, err)
	}

	var plaintext bool
	switch parsed.Scheme {
	case "https", "grpcs":
		plaintext = false
	case "http", "grpc":
		plaintext = true
	default:
		return Endpoint{}, fmt.Errorf("panel endpoint %q: scheme %q is not one of https, http", raw, parsed.Scheme)
	}

	if parsed.Path != "" && parsed.Path != "/" {
		return Endpoint{}, fmt.Errorf("panel endpoint %q: a path is not part of a gRPC endpoint", raw)
	}
	if parsed.RawQuery != "" || parsed.Fragment != "" {
		return Endpoint{}, fmt.Errorf("panel endpoint %q: a query string is not part of a gRPC endpoint", raw)
	}
	if parsed.User != nil {
		return Endpoint{}, fmt.Errorf("panel endpoint %q: credentials belong in the node token, not the URL", raw)
	}

	host := parsed.Hostname()
	if host == "" {
		return Endpoint{}, fmt.Errorf("panel endpoint %q has no host", raw)
	}

	port := parsed.Port()
	if port == "" {
		if plaintext {
			port = defaultPlaintextPort
		} else {
			port = defaultTLSPort
		}
	}

	return Endpoint{
		Target:     net.JoinHostPort(host, port),
		ServerName: host,
		Plaintext:  plaintext,
	}, nil
}

// String is what goes in a log line and in node.json, and it parses back to the same
// endpoint.
func (e Endpoint) String() string {
	scheme := "https"
	if e.Plaintext {
		scheme = "http"
	}
	return scheme + "://" + e.Target
}
