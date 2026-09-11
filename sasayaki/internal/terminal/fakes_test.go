package terminal

import (
	"bytes"
	"context"
	"io"
	"sync"
	"sync/atomic"

	"github.com/furimeo/wisper/sasayaki/internal/reconcile"
	"github.com/furimeo/wisper/sasayaki/internal/runtime"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// The three things a session talks to, none of which is real here.
//
// A pty that can be told to produce exactly these bytes and then break, a stream that can
// be told to stop draining, and an engine whose container can be made to disappear between
// one call and the next. Every one of those is a behaviour a live Docker daemon will not
// perform on request, and every one of them is a case this package has to get right: there
// is no way to ask a real socket to stop draining, and killing a container from inside a
// unit test to see what the terminal does is not a test, it is an incident rehearsal.
//
// The frames are not faked. They are generated from terminal.proto and they are the
// contract with the panel.
//
// Three fakes in one file, slightly past the length this repository likes, because they
// are one apparatus rather than three: no test uses any of them alone, and a reader
// following a session through them would otherwise be opening three files to see one
// story.

// fakePty is a scripted pseudo-terminal.
type fakePty struct {
	mu          sync.Mutex
	containerID string
	cols, rows  uint32
	typed       bytes.Buffer
	resizes     []window
	resizeErr   error
	writeErr    error
	closed      bool
	exitCode    int
	exitErr     error
	waits       int

	// scripted output, and the bytes still to be handed out from the last item. Only the
	// reading goroutine touches pending, so it needs no lock.
	scripted chan scriptedRead
	pending  []byte
	// served counts the bytes handed to the reader, which is how the backpressure test
	// proves a blocked stream stops the pty being drained.
	served atomic.Int64

	closing sync.Once
	done    chan struct{}
}

type window struct{ cols, rows uint32 }

type scriptedRead struct {
	data []byte
	err  error
}

func newFakePty(container string, cols, rows uint32) *fakePty {
	return &fakePty{
		containerID: container,
		cols:        cols,
		rows:        rows,
		scripted:    make(chan scriptedRead, 64),
		done:        make(chan struct{}),
	}
}

func (p *fakePty) ContainerID() string       { return p.containerID }
func (p *fakePty) Size() (cols, rows uint32) { return p.cols, p.rows }

func (p *fakePty) Read(b []byte) (int, error) {
	if len(p.pending) > 0 {
		read := copy(b, p.pending)
		p.pending = p.pending[read:]
		p.served.Add(int64(read))
		return read, nil
	}
	select {
	case next, ok := <-p.scripted:
		if !ok {
			return 0, io.EOF
		}
		if next.err != nil {
			return 0, next.err
		}
		p.pending = next.data
		read := copy(b, p.pending)
		p.pending = p.pending[read:]
		p.served.Add(int64(read))
		return read, nil
	case <-p.done:
		return 0, io.EOF
	}
}

func (p *fakePty) Write(data []byte) (int, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.writeErr != nil {
		return 0, p.writeErr
	}
	return p.typed.Write(data)
}

func (p *fakePty) Resize(_ context.Context, cols, rows uint32) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.resizeErr != nil {
		return p.resizeErr
	}
	p.resizes = append(p.resizes, window{cols: cols, rows: rows})
	return nil
}

func (p *fakePty) Wait(context.Context) (int, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.waits++
	return p.exitCode, p.exitErr
}

// Close is called by the session and again by the test's cleanup, so it is idempotent.
func (p *fakePty) Close() {
	p.closing.Do(func() {
		p.mu.Lock()
		p.closed = true
		p.mu.Unlock()
		close(p.done)
	})
}

// say queues output for the session to read.
func (p *fakePty) say(data []byte) { p.scripted <- scriptedRead{data: data} }

// stop ends the output, which is what the process exiting looks like from here.
func (p *fakePty) stop() { close(p.scripted) }

// breaks ends the output with a failure rather than cleanly - a connection reset, which is
// what a container being killed underneath a session produces.
func (p *fakePty) breaks(err error) { p.scripted <- scriptedRead{err: err} }

func (p *fakePty) keystrokes() string {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.typed.String()
}

func (p *fakePty) windows() []window {
	p.mu.Lock()
	defer p.mu.Unlock()
	return append([]window(nil), p.resizes...)
}

func (p *fakePty) isClosed() bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.closed
}

// fakeStream is the panel's end of the terminal stream.
type fakeStream struct {
	mu         sync.Mutex
	sent       []*wisperpb.TerminalFrame
	sendErr    error
	closedSend bool

	// blockAfter and gate are the slow reader: the first blockAfter sends go through and
	// everything after them waits until the gate opens.
	blockAfter int
	gate       chan struct{}
	sends      atomic.Int64

	inbound   chan *wisperpb.TerminalFrame
	recvErr   error
	hangingUp sync.Once
}

func newFakeStream() *fakeStream {
	return &fakeStream{inbound: make(chan *wisperpb.TerminalFrame, 16)}
}

func (s *fakeStream) Send(frame *wisperpb.TerminalFrame) error {
	if s.blockAfter > 0 && s.sends.Add(1) > int64(s.blockAfter) {
		<-s.gate
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.sendErr != nil {
		return s.sendErr
	}
	s.sent = append(s.sent, frame)
	return nil
}

func (s *fakeStream) Recv() (*wisperpb.TerminalFrame, error) {
	frame, ok := <-s.inbound
	if !ok {
		s.mu.Lock()
		defer s.mu.Unlock()
		if s.recvErr != nil {
			return nil, s.recvErr
		}
		return nil, io.EOF
	}
	return frame, nil
}

func (s *fakeStream) CloseSend() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.closedSend = true
	return nil
}

// panelSends puts a frame on the stream as the panel would.
func (s *fakeStream) panelSends(frame *wisperpb.TerminalFrame) { s.inbound <- frame }

// hangUp is the browser going away. Idempotent: the test cleanup calls it too.
func (s *fakeStream) hangUp() { s.hangingUp.Do(func() { close(s.inbound) }) }

func (s *fakeStream) frames() []*wisperpb.TerminalFrame {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]*wisperpb.TerminalFrame(nil), s.sent...)
}

func (s *fakeStream) sendClosed() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.closedSend
}

// fakeEngine answers the three questions this package asks of a container runtime.
type fakeEngine struct {
	mu sync.Mutex

	container reconcile.Container
	exists    bool
	lookupErr error
	lookups   int
	// afterAttach replaces the answer once the session has attached, which is how a test
	// makes a container die underneath a live terminal.
	afterAttach func() (reconcile.Container, bool, error)

	pty      *fakePty
	openErr  error
	opened   []runtime.ExecOptions
	attached bool

	executable map[string]bool
	probeErr   error
	probes     [][]string
}

func (e *fakeEngine) Container(context.Context, string) (reconcile.Container, bool, error) {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.lookups++
	if e.attached && e.afterAttach != nil {
		return e.afterAttach()
	}
	if e.lookupErr != nil {
		return reconcile.Container{}, false, e.lookupErr
	}
	return e.container, e.exists, nil
}

func (e *fakeEngine) Open(_ context.Context, _ string, options runtime.ExecOptions) (Pty, error) {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.openErr != nil {
		return nil, e.openErr
	}
	e.opened = append(e.opened, options)
	e.attached = true
	return e.pty, nil
}

func (e *fakeEngine) CanExecute(_ context.Context, _ string, command []string) (bool, error) {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.probes = append(e.probes, command)
	if e.probeErr != nil {
		return false, e.probeErr
	}
	if len(command) == 0 {
		return false, nil
	}
	return e.executable[command[0]], nil
}

func (e *fakeEngine) attachments() []runtime.ExecOptions {
	e.mu.Lock()
	defer e.mu.Unlock()
	return append([]runtime.ExecOptions(nil), e.opened...)
}

func (e *fakeEngine) probed() [][]string {
	e.mu.Lock()
	defer e.mu.Unlock()
	return append([][]string(nil), e.probes...)
}
