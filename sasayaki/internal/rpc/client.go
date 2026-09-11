// Package rpc is the node's half of the panel conversation.
//
// One connection, dialled outwards, because the panel has no public IP and never will:
// it lives behind a tunnel, so every stream between the two starts here (design section
// 5.2). What that buys is an installation with no inbound firewall rule and no
// per-node certificate to issue, rotate or lose. What it costs is that the connection
// drops routinely, and this package is built around treating that as normal traffic
// rather than as an incident:
//
//   - the panel's certificate is pinned at enrolment and a change is refused (pin.go);
//   - every reconnect is jittered and there is no attempt limit anywhere (backoff.go);
//   - the reconnect handshake sends what the node has applied, and the panel answers
//     with the whole spec - never a delta, so it does not matter how long the node was
//     gone;
//   - a command whose stream dies mid-flight keeps running, and its result is delivered
//     on the next stream.
//
// Everything the panel can ask for is dispatched to the package that owns it through
// the interfaces in handlers.go. This package knows about frames and connections; it
// knows nothing about Docker, Caddy or SQLite.
package rpc

import (
	"log/slog"
	"sync"
	"sync/atomic"
	"time"

	"google.golang.org/grpc"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Defaults for the knobs Option can change. They are constants rather than settings in
// a file because none of them is something an operator should have to think about.
const (
	// Messages held for a panel that is not currently reachable. Enough for a burst of
	// command results and events during a tunnel wobble; small enough that a node
	// disconnected for a day is not holding a megabyte of stale frames.
	defaultQueueDepth = 256

	// How often a client-streaming uplink is closed and reopened. Ack - and therefore
	// the panel's only throttle - arrives when the node half-closes, so a stream that
	// stayed open forever could never be told to slow down.
	defaultUplinkRotation = 30 * time.Second
)

// Client is the node's connection to the panel: one gRPC channel, one control stream,
// and the four side streams that hang off it.
//
// It is safe for concurrent use. Everything written to the control stream goes through
// one mutex, because a gRPC stream is not safe for concurrent Send and the alternative
// is interleaved frames.
type Client struct {
	endpoint   Endpoint
	credential Credential
	handlers   Handlers
	log        *slog.Logger
	backoff    Backoff
	rotation   time.Duration
	queueDepth int

	connection *grpc.ClientConn
	panel      wisperpb.NodeServiceClient

	// Guards the write path to the control stream and the queue behind it. Held across
	// Send: two frames half-written into each other is a stream the panel has to drop.
	outbound sync.Mutex
	current  atomic.Pointer[session]
	pending  []*wisperpb.NodeMessage

	stats *pushStream[wisperpb.StatSample]
	logs  *pushStream[wisperpb.LogChunk]

	// Command ids being executed right now, so a frame the panel repeated after a
	// reconnect does not run a second backup.
	running   sync.Mutex
	runningID map[string]struct{}

	// Counts sessions that completed the handshake. Read by tests and by the log line
	// that distinguishes a first connection from a reconnection.
	connections atomic.Uint64

	// The last refused certificate, recorded by the TLS verifier and consumed by the
	// supervisor, which is the only place that can turn it into a sentence an operator
	// can act on.
	pinFailure atomic.Pointer[FingerprintMismatch]
}

// Option adjusts a Client at construction.
type Option func(*Client)

// WithLogger replaces slog.Default. The daemon passes one that knows the node id.
func WithLogger(logger *slog.Logger) Option {
	return func(c *Client) {
		if logger != nil {
			c.log = logger
		}
	}
}

// WithBackoff replaces the reconnect schedule.
func WithBackoff(policy Backoff) Option {
	return func(c *Client) { c.backoff = policy }
}

// WithUplinkRotation changes how often the stats and log streams are closed to collect
// their Ack.
func WithUplinkRotation(every time.Duration) Option {
	return func(c *Client) {
		if every > 0 {
			c.rotation = every
		}
	}
}

// WithQueueDepth changes how many frames are held for a panel that is away.
func WithQueueDepth(depth int) Option {
	return func(c *Client) {
		if depth > 0 {
			c.queueDepth = depth
		}
	}
}

// New prepares the connection. It does not open it: grpc.NewClient connects on the first
// call, which leaves the timing of every attempt to the reconnect loop in Run.
func New(credential Credential, handlers Handlers, options ...Option) (*Client, error) {
	if err := handlers.validate(); err != nil {
		return nil, err
	}
	if err := credential.Validate(); err != nil {
		return nil, err
	}
	endpoint, err := credential.Endpoint()
	if err != nil {
		return nil, err
	}
	pin, err := credential.Pin()
	if err != nil {
		return nil, err
	}

	client := &Client{
		endpoint:   endpoint,
		credential: credential,
		handlers:   handlers,
		log:        slog.Default(),
		backoff:    DefaultBackoff(),
		rotation:   defaultUplinkRotation,
		queueDepth: defaultQueueDepth,
		runningID:  make(map[string]struct{}),
	}
	for _, option := range options {
		option(client)
	}

	connection, err := dial(dialSpec{
		endpoint:     endpoint,
		pin:          pin,
		onPinFailure: func(mismatch *FingerprintMismatch) { client.pinFailure.Store(mismatch) },
		credential:   &nodeCredentials{nodeID: credential.NodeID, token: credential.Token},
	})
	if err != nil {
		return nil, err
	}
	client.connection = connection
	client.panel = wisperpb.NewNodeServiceClient(connection)
	client.stats = newStatsUplink(client)
	client.logs = newLogUplink(client)

	return client, nil
}

// Close releases the connection. There is nothing else to clean up: the daemon is
// crash-only, and being killed has to be indistinguishable from stopping.
func (c *Client) Close() error {
	return c.connection.Close()
}

// NodeID is this node's id at the panel, for log lines and for filling in the messages
// that carry it.
func (c *Client) NodeID() string { return c.credential.NodeID }

// Connected reports whether a control stream is up right now. It is a fact for the
// status the node reports about itself, not a gate to check before sending: send
// already queues what it cannot deliver.
func (c *Client) Connected() bool { return c.current.Load() != nil }
