package rpc

import (
	"crypto/tls"
	"fmt"
	"time"

	"google.golang.org/grpc"
	grpcbackoff "google.golang.org/grpc/backoff"
	"google.golang.org/grpc/credentials"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/keepalive"

	"github.com/furimeo/wisper/sasayaki/internal/version"
)

// Transport settings, all of them paired with something on the panel side.
const (
	// Nodes ping every 20 seconds and the panel's permit-keep-alive-time is 10, so this
	// is comfortably inside what the server tolerates. It has to be the application
	// pinging rather than TCP: the stream runs through a tunnel, and a tunnel that has
	// quietly stopped forwarding looks exactly like a connection with nothing to say.
	keepAliveInterval = 20 * time.Second

	// No answer to a ping within this and the connection is dead. Matches the panel's
	// keep-alive-timeout.
	keepAliveTimeout = 10 * time.Second

	// wisper.grpc.max-inbound-message-size on the panel. Set on both directions here so
	// an oversized frame fails at the sender, where the message says which frame it was,
	// rather than as a stream reset at the far end.
	maxMessageBytes = 16 << 20
)

// dialSpec is what one connection needs. A struct rather than five parameters because
// two of them are alternatives - a pin or an observer, never both - and a struct makes
// that checkable in one place.
type dialSpec struct {
	endpoint Endpoint

	// pin is the fingerprint this connection insists on. Empty during enrolment, and
	// empty for a plaintext endpoint.
	pin string

	// observer records the certificate that turned up, for the one connection that does
	// not have a pin yet.
	observer *certificateObserver

	// onPinFailure is told when a handshake was refused because the certificate
	// changed, since gRPC will only report that as a generic connection failure.
	onPinFailure func(*FingerprintMismatch)

	// credential is nil for Enroll, the one RPC with nothing to authenticate with.
	credential *nodeCredentials
}

// dial builds the connection. It does not wait for it: grpc.NewClient connects lazily,
// which is what lets the reconnect loop own the timing of every attempt instead of
// having half of it hidden inside a blocking dial.
func dial(spec dialSpec) (*grpc.ClientConn, error) {
	transport, err := transportCredentials(spec)
	if err != nil {
		return nil, err
	}

	options := []grpc.DialOption{
		grpc.WithTransportCredentials(transport),
		grpc.WithKeepaliveParams(keepalive.ClientParameters{
			Time:    keepAliveInterval,
			Timeout: keepAliveTimeout,
			// The control stream is idle for most of its life and still has to be known
			// to be alive; the panel permits pings without calls for exactly this.
			PermitWithoutStream: true,
		}),
		// gRPC's own reconnection of the underlying connection. The stream-level loop in
		// reconnect.go is the one that matters, but leaving this at its default means a
		// channel that has given up sitting behind a loop that has not.
		grpc.WithConnectParams(grpc.ConnectParams{
			Backoff: grpcbackoff.Config{
				BaseDelay:  time.Second,
				Multiplier: 1.6,
				Jitter:     0.2,
				MaxDelay:   30 * time.Second,
			},
			MinConnectTimeout: 10 * time.Second,
		}),
		grpc.WithDefaultCallOptions(
			grpc.MaxCallRecvMsgSize(maxMessageBytes),
			grpc.MaxCallSendMsgSize(maxMessageBytes),
		),
		grpc.WithUserAgent("sasayaki/" + version.Number),
	}
	if spec.credential != nil {
		options = append(options, grpc.WithPerRPCCredentials(*spec.credential))
	}

	connection, err := grpc.NewClient(spec.endpoint.Target, options...)
	if err != nil {
		return nil, fmt.Errorf("prepare connection to %s: %w", spec.endpoint, err)
	}
	return connection, nil
}

func transportCredentials(spec dialSpec) (credentials.TransportCredentials, error) {
	if spec.endpoint.Plaintext {
		if spec.pin != "" {
			return nil, fmt.Errorf("endpoint %s is plaintext but a certificate is pinned: "+
				"one of the two is wrong, and guessing which would either drop the pin or "+
				"refuse to connect for no reason", spec.endpoint)
		}
		return insecure.NewCredentials(), nil
	}

	configuration := &tls.Config{
		ServerName: spec.endpoint.ServerName,
		MinVersion: tls.VersionTLS12,
		// The pin below is the trust anchor, so the chain is not walked. This is not a
		// weakened check - it is a different and, for a panel behind a tunnel, stronger
		// one: see the commentary at the top of pin.go. It is also why the branch below
		// refuses to build a TLS connection with neither a pin nor an observer.
		InsecureSkipVerify: true,
	}

	switch {
	case spec.observer != nil && spec.pin != "":
		return nil, fmt.Errorf("internal: a connection cannot both pin and discover a certificate")
	case spec.observer != nil:
		configuration.VerifyPeerCertificate = spec.observer.verify
	case spec.pin != "":
		configuration.VerifyPeerCertificate = verifyAgainstPin(spec.pin, spec.onPinFailure)
	default:
		return nil, fmt.Errorf("refusing to dial %s over TLS with no pinned certificate: "+
			"this node has no fingerprint for the panel, so re-enrol it", spec.endpoint)
	}

	return credentials.NewTLS(configuration), nil
}
