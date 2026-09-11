package rpc

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"errors"
	"math/big"
	"net"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials"
	"google.golang.org/grpc/keepalive"
	"google.golang.org/grpc/metadata"

	"github.com/furimeo/wisper/sasayaki/internal/version"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// A panel, in process, over a real TCP socket and a real TLS handshake.
//
// Real rather than a bufconn pipe because half of what this package does is TLS
// pinning, and a test that stubs out the handshake proves nothing about the code that
// runs during one. The certificate is generated per test, so a test can start a second
// panel with a different one and watch the node refuse it.
//
// Past three hundred lines and not split, because it is one thing: the other end of
// node.proto. Splitting it by RPC would put the stream a test drives in one file and
// the handler that produced it in another, which is exactly the seam that makes a test
// harness hard to read.

// howLong is the ceiling on anything a test waits for. Generous, because it is only
// ever reached when something is broken.
//
// Thirty seconds rather than five. Five was generous on a developer's machine and not on a
// two-core CI runner running this package's tests alongside each other: a cancellation
// round trip through gRPC and three goroutines came in at 5.01s and failed a test that was
// working perfectly. Waiting longer costs nothing when everything passes, and a genuine
// hang still fails - it just takes twenty-five seconds longer to say so. A flaky test is
// worse than a slow one, because it teaches people to re-run CI instead of reading it.
const howLong = 30 * time.Second

type panelStub struct {
	wisperpb.UnimplementedNodeServiceServer

	address     string
	fingerprint string

	// Behaviour, set before the node connects.
	protocol         uint32
	connectStatus    error
	heartbeatSeconds int64
	enroll           func(context.Context, *wisperpb.EnrollRequest) (*wisperpb.EnrollResponse, error)

	sessions  chan *panelSession
	fileOps   chan *panelFileStream
	terminals chan *panelTerminal

	// Observations.
	connectAttempts atomic.Int64
	statusBatches   chan *wisperpb.StatusBatch
	samples         chan *wisperpb.StatSample
	logChunks       chan *wisperpb.LogChunk

	mu       sync.Mutex
	metadata []metadata.MD
}

// panelSession is one accepted control stream, from the server's side, handed to the
// test so it can drive the conversation.
type panelSession struct {
	hello    *wisperpb.NodeHello
	received chan *wisperpb.NodeMessage
	outbound chan *wisperpb.PanelMessage
	stopOnce sync.Once
	stopped  chan struct{}
	finished chan struct{}
}

// send pushes a frame down to the node.
func (s *panelSession) send(t *testing.T, message *wisperpb.PanelMessage) {
	t.Helper()
	select {
	case s.outbound <- message:
	case <-time.After(howLong):
		t.Fatal("the node never read the frame the panel sent")
	}
}

// stop ends the stream from the panel's side, which is what a tunnel dropping looks
// like to the node.
func (s *panelSession) stop() {
	s.stopOnce.Do(func() { close(s.stopped) })
}

// expect waits for a frame matching want, ignoring anything else that arrives first.
func (s *panelSession) expect(t *testing.T, what string, want func(*wisperpb.NodeMessage) bool) *wisperpb.NodeMessage {
	t.Helper()
	deadline := time.After(howLong)
	for {
		select {
		case message := <-s.received:
			if want(message) {
				return message
			}
		case <-deadline:
			t.Fatalf("the node never sent %s", what)
			return nil
		}
	}
}

// panelTerminal is a terminal stream the node dialled back with.
type panelTerminal struct {
	stream   grpc.BidiStreamingServer[wisperpb.TerminalFrame, wisperpb.TerminalFrame]
	received chan *wisperpb.TerminalFrame
}

func (p *panelTerminal) expect(t *testing.T, what string, want func(*wisperpb.TerminalFrame) bool) *wisperpb.TerminalFrame {
	t.Helper()
	deadline := time.After(howLong)
	for {
		select {
		case frame := <-p.received:
			if want(frame) {
				return frame
			}
		case <-deadline:
			t.Fatalf("the node never sent %s", what)
			return nil
		}
	}
}

type panelFileStream struct {
	stream   grpc.BidiStreamingServer[wisperpb.FileEvent, wisperpb.FileRequest]
	received chan *wisperpb.FileEvent
}

func (f *panelFileStream) send(t *testing.T, request *wisperpb.FileRequest) {
	t.Helper()
	if err := f.stream.Send(request); err != nil {
		t.Fatalf("send file request: %v", err)
	}
}

func (f *panelFileStream) expect(t *testing.T, what string, want func(*wisperpb.FileEvent) bool) *wisperpb.FileEvent {
	t.Helper()
	deadline := time.After(howLong)
	for {
		select {
		case event := <-f.received:
			if want(event) {
				return event
			}
		case <-deadline:
			t.Fatalf("the node never sent %s", what)
			return nil
		}
	}
}

// startPanel brings up the stub and stops it when the test ends.
func startPanel(t *testing.T) *panelStub {
	t.Helper()

	certificate, fingerprint := selfSignedCertificate(t)
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}

	panel := &panelStub{
		address:       "https://" + listener.Addr().String(),
		fingerprint:   fingerprint,
		sessions:      make(chan *panelSession, 8),
		fileOps:       make(chan *panelFileStream, 8),
		terminals:     make(chan *panelTerminal, 8),
		statusBatches: make(chan *wisperpb.StatusBatch, 8),
		samples:       make(chan *wisperpb.StatSample, 64),
		logChunks:     make(chan *wisperpb.LogChunk, 64),
	}

	server := grpc.NewServer(
		grpc.Creds(credentials.NewTLS(&tls.Config{
			Certificates: []tls.Certificate{certificate},
			MinVersion:   tls.VersionTLS12,
		})),
		// The node pings every 20 seconds with no call in flight; without this the
		// server answers with GOAWAY and the test watches a healthy client be hung up
		// on for being polite.
		grpc.KeepaliveEnforcementPolicy(keepalive.EnforcementPolicy{
			MinTime:             10 * time.Second,
			PermitWithoutStream: true,
		}),
	)
	wisperpb.RegisterNodeServiceServer(server, panel)

	go func() { _ = server.Serve(listener) }()
	t.Cleanup(server.Stop)

	return panel
}

// credential is a node enrolled against this panel.
func (p *panelStub) credential() Credential {
	return Credential{
		NodeID:                 "11111111-2222-3333-4444-555555555555",
		NodeName:               "test-node",
		Token:                  "a-long-lived-credential",
		Panel:                  p.address,
		PanelCertificateSHA256: p.fingerprint,
		EnrolledAt:             time.Now().UTC(),
	}
}

// session waits for the node to complete a handshake.
func (p *panelStub) session(t *testing.T) *panelSession {
	t.Helper()
	select {
	case live := <-p.sessions:
		return live
	case <-time.After(howLong):
		t.Fatal("the node never completed a handshake")
		return nil
	}
}

func (p *panelStub) lastMetadata() metadata.MD {
	p.mu.Lock()
	defer p.mu.Unlock()
	if len(p.metadata) == 0 {
		return nil
	}
	return p.metadata[len(p.metadata)-1]
}

func (p *panelStub) Enroll(ctx context.Context, request *wisperpb.EnrollRequest) (*wisperpb.EnrollResponse, error) {
	p.record(ctx)
	if p.enroll != nil {
		return p.enroll(ctx, request)
	}
	return &wisperpb.EnrollResponse{
		NodeId:                 "11111111-2222-3333-4444-555555555555",
		Credential:             "a-long-lived-credential",
		PanelCertificateSha256: p.fingerprint,
		NodeName:               "test-node",
		ProtocolVersion:        uint32(version.Protocol),
	}, nil
}

func (p *panelStub) Connect(stream grpc.BidiStreamingServer[wisperpb.NodeMessage, wisperpb.PanelMessage]) error {
	p.connectAttempts.Add(1)
	p.record(stream.Context())

	if p.connectStatus != nil {
		return p.connectStatus
	}

	first, err := stream.Recv()
	if err != nil {
		return err
	}
	hello := first.GetHello()
	if hello == nil {
		return errors.New("the node's first frame was not a hello")
	}

	protocol := p.protocol
	if protocol == 0 {
		protocol = uint32(version.Protocol)
	}
	if err := stream.Send(&wisperpb.PanelMessage{
		Payload: &wisperpb.PanelMessage_Hello{Hello: &wisperpb.PanelHello{
			ProtocolVersion:          protocol,
			NodeId:                   "11111111-2222-3333-4444-555555555555",
			PanelVersion:             "test-panel",
			HeartbeatIntervalSeconds: p.heartbeatSeconds,
		}},
	}); err != nil {
		return err
	}

	live := &panelSession{
		hello:    hello,
		received: make(chan *wisperpb.NodeMessage, 256),
		outbound: make(chan *wisperpb.PanelMessage),
		stopped:  make(chan struct{}),
		finished: make(chan struct{}),
	}
	defer close(live.finished)
	// Non-blocking: a test that reconnects more often than it inspects sessions must
	// not leave this handler parked on a full channel.
	select {
	case p.sessions <- live:
	default:
	}

	reading := make(chan error, 1)
	go func() {
		for {
			message, err := stream.Recv()
			if err != nil {
				reading <- err
				return
			}
			live.received <- message
		}
	}()

	for {
		select {
		case <-live.stopped:
			return nil
		case <-stream.Context().Done():
			return stream.Context().Err()
		case err := <-reading:
			return err
		case message := <-live.outbound:
			if err := stream.Send(message); err != nil {
				return err
			}
		}
	}
}

func (p *panelStub) ReportStatus(ctx context.Context, batch *wisperpb.StatusBatch) (*wisperpb.Ack, error) {
	p.record(ctx)
	select {
	case p.statusBatches <- batch:
	default:
	}
	return &wisperpb.Ack{Accepted: 1}, nil
}

func (p *panelStub) PushStats(stream grpc.ClientStreamingServer[wisperpb.StatSample, wisperpb.Ack]) error {
	var accepted uint64
	for {
		sample, err := stream.Recv()
		if err != nil {
			return stream.SendAndClose(&wisperpb.Ack{Accepted: accepted})
		}
		accepted++
		select {
		case p.samples <- sample:
		default:
		}
	}
}

func (p *panelStub) LogStream(stream grpc.ClientStreamingServer[wisperpb.LogChunk, wisperpb.Ack]) error {
	var accepted uint64
	for {
		chunk, err := stream.Recv()
		if err != nil {
			return stream.SendAndClose(&wisperpb.Ack{Accepted: accepted})
		}
		accepted++
		select {
		case p.logChunks <- chunk:
		default:
		}
	}
}

func (p *panelStub) Terminal(stream grpc.BidiStreamingServer[wisperpb.TerminalFrame, wisperpb.TerminalFrame]) error {
	p.record(stream.Context())

	session := &panelTerminal{stream: stream, received: make(chan *wisperpb.TerminalFrame, 32)}
	select {
	case p.terminals <- session:
	default:
	}

	for {
		frame, err := stream.Recv()
		if err != nil {
			return err
		}
		session.received <- frame
	}
}

func (p *panelStub) FileOp(stream grpc.BidiStreamingServer[wisperpb.FileEvent, wisperpb.FileRequest]) error {
	p.record(stream.Context())

	transfers := &panelFileStream{stream: stream, received: make(chan *wisperpb.FileEvent, 64)}
	select {
	case p.fileOps <- transfers:
	default:
	}

	for {
		event, err := stream.Recv()
		if err != nil {
			return err
		}
		transfers.received <- event
	}
}

func (p *panelStub) record(ctx context.Context) {
	incoming, ok := metadata.FromIncomingContext(ctx)
	if !ok {
		return
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	p.metadata = append(p.metadata, incoming)
}

// selfSignedCertificate is a panel's certificate. Self-signed on purpose: a node pins
// what it saw, so a chain would be ceremony with no effect on the outcome.
func selfSignedCertificate(t *testing.T) (tls.Certificate, string) {
	t.Helper()

	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatalf("generate key: %v", err)
	}
	template := x509.Certificate{
		SerialNumber:          big.NewInt(time.Now().UnixNano()),
		Subject:               pkix.Name{CommonName: "panel.test"},
		NotBefore:             time.Now().Add(-time.Hour),
		NotAfter:              time.Now().Add(time.Hour),
		KeyUsage:              x509.KeyUsageDigitalSignature | x509.KeyUsageCertSign,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		BasicConstraintsValid: true,
		DNSNames:              []string{"panel.test"},
		IPAddresses:           []net.IP{net.ParseIP("127.0.0.1")},
	}
	der, err := x509.CreateCertificate(rand.Reader, &template, &template, &key.PublicKey, key)
	if err != nil {
		t.Fatalf("create certificate: %v", err)
	}
	return tls.Certificate{Certificate: [][]byte{der}, PrivateKey: key}, Fingerprint(der)
}
