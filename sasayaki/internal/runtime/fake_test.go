package runtime

import (
	"context"
	"fmt"
	"io"
	"iter"
	"log/slog"
	"strings"
	"sync"
	"testing"
	"time"

	cerrdefs "github.com/containerd/errdefs"
	"github.com/moby/moby/api/types/jsonstream"
	"github.com/moby/moby/api/types/network"
	"github.com/moby/moby/api/types/system"
	"github.com/moby/moby/client"
)

// A Docker engine that is not there.
//
// Every test in this package runs on Windows, where there is no Docker socket, no
// iptables and no xfs_quota - and it has to, because the machine this platform is
// developed on is a Windows box (design section 13.2). What that buys is not just
// convenience: the decisions worth testing here are which arguments the engine is handed,
// and a test against a real engine can only see what the engine did with them afterwards.
// Whether CPUShares stayed at zero is not observable from a running container; it is
// observable here.
//
// The fake answers with whatever the test set on it and records what it was asked. Nil
// hooks get a plausible default, so a test about mounts does not have to describe an
// image pull.
//
// Over three hundred lines and not split, because most of it is the twenty methods the
// engine interface in docker.go declares: cutting it in half would mean one interface
// implemented across two files, and adding a method to the interface would then compile
// or not depending on which half a reader happened to open.
type fakeEngine struct {
	mu sync.Mutex

	info    system.Info
	infoErr error

	created  []client.ContainerCreateOptions
	started  []string
	stopped  []stopCall
	removed  []client.ContainerRemoveOptions
	networks []networkCall
	execs    []client.ExecCreateOptions
	resizes  []client.ExecResizeOptions
	closed   bool

	onCreate      func(client.ContainerCreateOptions) (client.ContainerCreateResult, error)
	onInspect     func(string) (client.ContainerInspectResult, error)
	onList        func(client.ContainerListOptions) (client.ContainerListResult, error)
	onStart       func(string) error
	onStop        func(string) error
	onRemove      func(string) error
	onLogs        func(string, client.ContainerLogsOptions) (client.ContainerLogsResult, error)
	onStats       func(string) (client.ContainerStatsResult, error)
	onImage       func(string) (client.ImageInspectResult, error)
	onPull        func(string) (client.ImagePullResponse, error)
	onNetInspect  func(string) (client.NetworkInspectResult, error)
	onNetCreate   func(string, client.NetworkCreateOptions) (client.NetworkCreateResult, error)
	onExecCreate  func(string, client.ExecCreateOptions) (client.ExecCreateResult, error)
	onExecAttach  func(string) (client.ExecAttachResult, error)
	onExecInspect func(string) (client.ExecInspectResult, error)
}

type stopCall struct {
	id      string
	timeout int
}

type networkCall struct {
	name    string
	options client.NetworkCreateOptions
}

// newFake is an engine that looks like a healthy node: cgroups v2, seccomp on, gVisor
// registered, an init binary available.
func newFake() *fakeEngine {
	return &fakeEngine{
		info: system.Info{
			CgroupVersion: "2",
			InitBinary:    "docker-init",
			SecurityOptions: []string{
				"name=seccomp,profile=builtin",
				"name=userns",
			},
			Runtimes: map[string]system.RuntimeWithStatus{
				"runc":  {},
				"runsc": {},
			},
		},
	}
}

// withoutRunsc is the node that has no gVisor, which is the case the panel has to be told
// about rather than left to assume.
func (f *fakeEngine) withoutRunsc() *fakeEngine {
	delete(f.info.Runtimes, "runsc")
	return f
}

func (f *fakeEngine) record(apply func()) {
	f.mu.Lock()
	defer f.mu.Unlock()
	apply()
}

func (f *fakeEngine) ContainerCreate(_ context.Context, options client.ContainerCreateOptions) (client.ContainerCreateResult, error) {
	f.record(func() { f.created = append(f.created, options) })
	if f.onCreate != nil {
		return f.onCreate(options)
	}
	return client.ContainerCreateResult{ID: "container-1"}, nil
}

func (f *fakeEngine) ContainerInspect(_ context.Context, id string, _ client.ContainerInspectOptions) (client.ContainerInspectResult, error) {
	if f.onInspect != nil {
		return f.onInspect(id)
	}
	return client.ContainerInspectResult{}, cerrdefs.ErrNotFound.WithMessage("no such container " + id)
}

func (f *fakeEngine) ContainerList(_ context.Context, options client.ContainerListOptions) (client.ContainerListResult, error) {
	if f.onList != nil {
		return f.onList(options)
	}
	return client.ContainerListResult{}, nil
}

func (f *fakeEngine) ContainerStart(_ context.Context, id string, _ client.ContainerStartOptions) (client.ContainerStartResult, error) {
	f.record(func() { f.started = append(f.started, id) })
	if f.onStart != nil {
		return client.ContainerStartResult{}, f.onStart(id)
	}
	return client.ContainerStartResult{}, nil
}

func (f *fakeEngine) ContainerStop(_ context.Context, id string, options client.ContainerStopOptions) (client.ContainerStopResult, error) {
	timeout := -1
	if options.Timeout != nil {
		timeout = *options.Timeout
	}
	f.record(func() { f.stopped = append(f.stopped, stopCall{id: id, timeout: timeout}) })
	if f.onStop != nil {
		return client.ContainerStopResult{}, f.onStop(id)
	}
	return client.ContainerStopResult{}, nil
}

func (f *fakeEngine) ContainerRemove(_ context.Context, id string, options client.ContainerRemoveOptions) (client.ContainerRemoveResult, error) {
	f.record(func() { f.removed = append(f.removed, options) })
	if f.onRemove != nil {
		return client.ContainerRemoveResult{}, f.onRemove(id)
	}
	return client.ContainerRemoveResult{}, nil
}

func (f *fakeEngine) ContainerLogs(_ context.Context, id string, options client.ContainerLogsOptions) (client.ContainerLogsResult, error) {
	if f.onLogs != nil {
		return f.onLogs(id, options)
	}
	return io.NopCloser(strings.NewReader("")), nil
}

func (f *fakeEngine) ContainerStats(_ context.Context, id string, _ client.ContainerStatsOptions) (client.ContainerStatsResult, error) {
	if f.onStats != nil {
		return f.onStats(id)
	}
	return client.ContainerStatsResult{Body: io.NopCloser(strings.NewReader("{}"))}, nil
}

func (f *fakeEngine) ExecCreate(_ context.Context, id string, options client.ExecCreateOptions) (client.ExecCreateResult, error) {
	f.record(func() { f.execs = append(f.execs, options) })
	if f.onExecCreate != nil {
		return f.onExecCreate(id, options)
	}
	return client.ExecCreateResult{ID: "exec-1"}, nil
}

func (f *fakeEngine) ExecAttach(_ context.Context, execID string, _ client.ExecAttachOptions) (client.ExecAttachResult, error) {
	if f.onExecAttach != nil {
		return f.onExecAttach(execID)
	}
	return client.ExecAttachResult{}, fmt.Errorf("no attach was prepared for %s", execID)
}

func (f *fakeEngine) ExecInspect(_ context.Context, execID string, _ client.ExecInspectOptions) (client.ExecInspectResult, error) {
	if f.onExecInspect != nil {
		return f.onExecInspect(execID)
	}
	return client.ExecInspectResult{ID: execID}, nil
}

func (f *fakeEngine) ExecResize(_ context.Context, _ string, options client.ExecResizeOptions) (client.ExecResizeResult, error) {
	f.record(func() { f.resizes = append(f.resizes, options) })
	return client.ExecResizeResult{}, nil
}

func (f *fakeEngine) ImageInspect(_ context.Context, image string, _ ...client.ImageInspectOption) (client.ImageInspectResult, error) {
	if f.onImage != nil {
		return f.onImage(image)
	}
	return client.ImageInspectResult{}, cerrdefs.ErrNotFound.WithMessage("no such image " + image)
}

func (f *fakeEngine) ImagePull(_ context.Context, ref string, _ client.ImagePullOptions) (client.ImagePullResponse, error) {
	if f.onPull != nil {
		return f.onPull(ref)
	}
	return nil, fmt.Errorf("no pull was prepared for %s", ref)
}

func (f *fakeEngine) NetworkCreate(_ context.Context, name string, options client.NetworkCreateOptions) (client.NetworkCreateResult, error) {
	f.record(func() { f.networks = append(f.networks, networkCall{name: name, options: options}) })
	if f.onNetCreate != nil {
		return f.onNetCreate(name, options)
	}
	return client.NetworkCreateResult{ID: "network-000000000000"}, nil
}

func (f *fakeEngine) NetworkInspect(_ context.Context, name string, _ client.NetworkInspectOptions) (client.NetworkInspectResult, error) {
	if f.onNetInspect != nil {
		return f.onNetInspect(name)
	}
	return client.NetworkInspectResult{}, cerrdefs.ErrNotFound.WithMessage("no such network " + name)
}

func (f *fakeEngine) Info(_ context.Context, _ client.InfoOptions) (client.SystemInfoResult, error) {
	if f.infoErr != nil {
		return client.SystemInfoResult{}, f.infoErr
	}
	return client.SystemInfoResult{Info: f.info}, nil
}

func (f *fakeEngine) Ping(_ context.Context, _ client.PingOptions) (client.PingResult, error) {
	return client.PingResult{APIVersion: "1.51"}, nil
}

func (f *fakeEngine) Close() error {
	f.record(func() { f.closed = true })
	return nil
}

// lastCreate is the container the code under test asked the engine to build.
func (f *fakeEngine) lastCreate(t *testing.T) client.ContainerCreateOptions {
	t.Helper()
	f.mu.Lock()
	defer f.mu.Unlock()
	if len(f.created) == 0 {
		t.Fatal("no container was created")
	}
	return f.created[len(f.created)-1]
}

// recordedCommand is one invocation of a host tool the firewall or the quota would have
// made.
type recordedCommand struct {
	name string
	args []string
}

func (r recordedCommand) String() string {
	return r.name + " " + strings.Join(r.args, " ")
}

// fakeHost is the host tools, absent. answers keys on the joined argument vector so a
// test can make one specific invocation fail or return canned output.
type fakeHost struct {
	mu       sync.Mutex
	calls    []recordedCommand
	answers  map[string]string
	failures map[string]error
	fail     error
}

func newHost() *fakeHost {
	return &fakeHost{answers: map[string]string{}, failures: map[string]error{}}
}

func (h *fakeHost) run(_ context.Context, name string, args ...string) ([]byte, error) {
	h.mu.Lock()
	defer h.mu.Unlock()
	call := recordedCommand{name: name, args: args}
	h.calls = append(h.calls, call)
	if h.fail != nil {
		return nil, h.fail
	}
	if err, failing := h.failures[call.String()]; failing {
		return nil, err
	}
	if answer, known := h.answers[call.String()]; known {
		return []byte(answer), nil
	}
	// A machine with no rules installed yet, which is what a node looks like the first
	// time this runs. iptables answers a query about something that is not there with a
	// non-zero exit; a change it is asked to make succeeds. Defaulting both to success
	// would make the tests pass against code that never installed anything.
	for _, argument := range args {
		if argument == "-S" || argument == "-C" {
			return nil, fmt.Errorf("iptables: No chain/target/match by that name")
		}
	}
	return nil, nil
}

func (h *fakeHost) invocations() []string {
	h.mu.Lock()
	defer h.mu.Unlock()
	out := make([]string, 0, len(h.calls))
	for _, call := range h.calls {
		out = append(out, call.String())
	}
	return out
}

// newDocker wires a Docker to the fake engine and the fake host, with a clock the test
// controls and a state directory it can look inside.
//
// The logger is discarded. This package says a lot at warning level on purpose - a node
// with no gVisor, a filter that would not install - and most of the tests are about
// exactly those cases, so leaving it on would bury a failure in the output of the twenty
// tests that passed.
func newDocker(t *testing.T, api *fakeEngine, host *fakeHost) *Docker {
	t.Helper()
	docker, err := New(context.Background(), t.TempDir(),
		withEngine(api),
		WithCommandRunner(host.run),
		WithLogger(slog.New(slog.DiscardHandler)),
		WithClock(func() time.Time { return time.Date(2026, 4, 1, 12, 0, 0, 0, time.UTC) }))
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	t.Cleanup(func() { _ = docker.Close() })
	return docker
}

// fakePull is a canned progress stream from a registry.
type fakePull struct {
	messages []jsonstream.Message
	failure  error
	closed   bool
	// hold, when set, blocks the stream until the test closes it - which is how a test
	// can observe a pull that is genuinely still in flight rather than one that raced to
	// finish first.
	hold chan struct{}
}

func (p *fakePull) Read([]byte) (int, error) { return 0, io.EOF }

func (p *fakePull) Close() error {
	p.closed = true
	return nil
}

func (p *fakePull) Wait(context.Context) error { return p.failure }

func (p *fakePull) JSONMessages(context.Context) iter.Seq2[jsonstream.Message, error] {
	return func(yield func(jsonstream.Message, error) bool) {
		if p.hold != nil {
			<-p.hold
		}
		for _, message := range p.messages {
			if !yield(message, nil) {
				return
			}
		}
		if p.failure != nil {
			yield(jsonstream.Message{}, p.failure)
		}
	}
}

// networkNamed is an existing network with a known bridge, for the tests that must not
// depend on one being created.
func networkNamed(name, bridge string) client.NetworkInspectResult {
	return client.NetworkInspectResult{Network: network.Inspect{
		Network: network.Network{
			Name:    name,
			ID:      "abcdef0123456789",
			Options: map[string]string{bridgeNameOption: bridge},
		},
	}}
}
