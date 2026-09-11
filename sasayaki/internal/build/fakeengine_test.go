package build

import (
	"context"
	"encoding/binary"
	"fmt"
	"io"
	"iter"
	"strconv"
	"sync"
	"testing"

	cerrdefs "github.com/containerd/errdefs"
	"github.com/moby/moby/api/types/container"
	"github.com/moby/moby/api/types/image"
	"github.com/moby/moby/api/types/jsonstream"
	"github.com/moby/moby/api/types/system"
	"github.com/moby/moby/client"
)

// A Docker engine that never talks to Docker.
//
// One file for one fake, and it is long because the interface it stands in for is: create,
// list, start, follow, wait, remove, inspect, pull, build, tag and info. Splitting it would
// put half of one object's methods in another file, which is the arrangement that makes a
// fake drift out of step with the interface it implements.

// fakeRun is what a scripted container does when it is started.
type fakeRun struct {
	Stdout string
	Stderr string
	Exit   int64
	// Hang keeps the container running until the caller's context ends, which is how a
	// build that outlives its deadline is tested without waiting for one.
	Hang bool
	// Do runs before the output is produced, with the host path that is bind-mounted into
	// the container, so a scripted build can write the files it is pretending to compile.
	Do func(hostPath string) error
}

type fakeEngine struct {
	mutex sync.Mutex

	// Created is every container this engine was asked to make, in order.
	Created []client.ContainerCreateOptions
	// Removed is every container id it was asked to delete.
	Removed []string
	// Pulled is every image reference it was asked to fetch.
	Pulled []string
	// Builds is every image build it was asked to run.
	Builds []client.ImageBuildOptions
	// Present is the set of image references already on the node.
	Present map[string]bool
	// Existing is what ContainerList reports, for the abandoned-container sweep.
	Existing []container.Summary

	// Script decides what each container does. Nil means "exit 0 saying nothing".
	Script func(options client.ContainerCreateOptions) fakeRun
	// BuildScript decides what an image build emits and whether it worked.
	BuildScript func(options client.ImageBuildOptions, source io.Reader) (stream, failure string)

	// Runsc makes Info report a node with gVisor registered.
	Runsc bool

	sequence int
	runs     map[string]fakeRun
	binds    map[string]string
}

func newFakeEngine() *fakeEngine {
	return &fakeEngine{
		Present: make(map[string]bool),
		runs:    make(map[string]fakeRun),
		binds:   make(map[string]string),
	}
}

func (e *fakeEngine) ContainerCreate(_ context.Context, options client.ContainerCreateOptions) (client.ContainerCreateResult, error) {
	e.mutex.Lock()
	defer e.mutex.Unlock()

	e.sequence++
	id := "container-" + strconv.Itoa(e.sequence)
	e.Created = append(e.Created, options)

	run := fakeRun{}
	if e.Script != nil {
		run = e.Script(options)
	}
	e.runs[id] = run
	if options.HostConfig != nil && len(options.HostConfig.Binds) > 0 {
		e.binds[id] = options.HostConfig.Binds[0]
	}
	return client.ContainerCreateResult{ID: id}, nil
}

func (e *fakeEngine) ContainerList(_ context.Context, _ client.ContainerListOptions) (client.ContainerListResult, error) {
	e.mutex.Lock()
	defer e.mutex.Unlock()
	return client.ContainerListResult{Items: e.Existing}, nil
}

func (e *fakeEngine) ContainerStart(_ context.Context, id string, _ client.ContainerStartOptions) (client.ContainerStartResult, error) {
	e.mutex.Lock()
	run := e.runs[id]
	bind := e.binds[id]
	e.mutex.Unlock()

	if run.Do != nil {
		host, _, _ := cutBind(bind)
		if err := run.Do(host); err != nil {
			return client.ContainerStartResult{}, err
		}
	}
	return client.ContainerStartResult{}, nil
}

func (e *fakeEngine) ContainerLogs(ctx context.Context, id string, _ client.ContainerLogsOptions) (client.ContainerLogsResult, error) {
	e.mutex.Lock()
	run := e.runs[id]
	e.mutex.Unlock()

	body := framed(1, run.Stdout)
	body = append(body, framed(stderrStream, run.Stderr)...)
	return &fakeLogs{payload: body, hang: run.Hang, done: ctx.Done()}, nil
}

func (e *fakeEngine) ContainerWait(ctx context.Context, id string, _ client.ContainerWaitOptions) client.ContainerWaitResult {
	e.mutex.Lock()
	run := e.runs[id]
	e.mutex.Unlock()

	result := make(chan container.WaitResponse, 1)
	failure := make(chan error, 1)
	if run.Hang {
		go func() {
			<-ctx.Done()
			failure <- ctx.Err()
		}()
		return client.ContainerWaitResult{Result: result, Error: failure}
	}
	result <- container.WaitResponse{StatusCode: run.Exit}
	return client.ContainerWaitResult{Result: result, Error: failure}
}

func (e *fakeEngine) ContainerRemove(_ context.Context, id string, _ client.ContainerRemoveOptions) (client.ContainerRemoveResult, error) {
	e.mutex.Lock()
	defer e.mutex.Unlock()
	e.Removed = append(e.Removed, id)
	return client.ContainerRemoveResult{}, nil
}

func (e *fakeEngine) ImageInspect(_ context.Context, ref string, _ ...client.ImageInspectOption) (client.ImageInspectResult, error) {
	e.mutex.Lock()
	defer e.mutex.Unlock()
	if !e.Present[ref] {
		return client.ImageInspectResult{}, fmt.Errorf("no such image %s: %w", ref, cerrdefs.ErrNotFound)
	}
	return client.ImageInspectResult{InspectResponse: image.InspectResponse{
		ID:   "sha256:" + ref,
		Size: 4096,
	}}, nil
}

func (e *fakeEngine) ImagePull(_ context.Context, ref string, _ client.ImagePullOptions) (client.ImagePullResponse, error) {
	e.mutex.Lock()
	defer e.mutex.Unlock()
	e.Pulled = append(e.Pulled, ref)
	e.Present[ref] = true
	return fakePull{}, nil
}

func (e *fakeEngine) ImageBuild(_ context.Context, source io.Reader, options client.ImageBuildOptions) (client.ImageBuildResult, error) {
	stream, failure := "Step 1/1 : FROM scratch\n", ""
	if e.BuildScript != nil {
		stream, failure = e.BuildScript(options, source)
	} else {
		// Drain the context the way the engine would, so the tar writer is not left
		// blocked on a pipe nobody reads.
		_, _ = io.Copy(io.Discard, source)
	}

	e.mutex.Lock()
	e.Builds = append(e.Builds, options)
	if failure == "" {
		for _, tag := range options.Tags {
			e.Present[tag] = true
		}
	}
	e.mutex.Unlock()

	body := `{"stream":` + strconv.Quote(stream) + "}\n"
	if failure != "" {
		body += `{"errorDetail":{"code":1,"message":` + strconv.Quote(failure) + `},"error":` +
			strconv.Quote(failure) + "}\n"
	}
	return client.ImageBuildResult{Body: io.NopCloser(stringReader(body))}, nil
}

func (e *fakeEngine) ImageTag(_ context.Context, _ client.ImageTagOptions) (client.ImageTagResult, error) {
	return client.ImageTagResult{}, nil
}

func (e *fakeEngine) Info(_ context.Context, _ client.InfoOptions) (client.SystemInfoResult, error) {
	runtimes := map[string]system.RuntimeWithStatus{"runc": {}}
	if e.Runsc {
		runtimes["runsc"] = system.RuntimeWithStatus{}
	}
	return client.SystemInfoResult{Info: system.Info{Runtimes: runtimes}}, nil
}

// createdNamed is the container created for one stage, for a test that wants to assert on
// how it was configured.
func (e *fakeEngine) createdNamed(t *testing.T, suffix string) client.ContainerCreateOptions {
	t.Helper()
	e.mutex.Lock()
	defer e.mutex.Unlock()
	for _, options := range e.Created {
		if len(options.Name) >= len(suffix) && options.Name[len(options.Name)-len(suffix):] == suffix {
			return options
		}
	}
	t.Fatalf("no container was created for %q; got %v", suffix, e.names())
	return client.ContainerCreateOptions{}
}

func (e *fakeEngine) names() []string {
	out := make([]string, 0, len(e.Created))
	for _, options := range e.Created {
		out = append(out, options.Name)
	}
	return out
}

// cutBind splits "host:container" the way the engine reads it.
func cutBind(bind string) (host, target string, ok bool) {
	for index := len(bind) - 1; index > 0; index-- {
		if bind[index] == ':' {
			return bind[:index], bind[index+1:], true
		}
	}
	return bind, "", false
}

// framed wraps text in the engine's multiplexing header.
func framed(stream byte, text string) []byte {
	if text == "" {
		return nil
	}
	header := make([]byte, frameHeaderBytes)
	header[0] = stream
	binary.BigEndian.PutUint32(header[4:], uint32(len(text)))
	return append(header, []byte(text)...)
}

// fakeLogs is a container's output: the scripted bytes, then either the end of the stream
// or - for a container that hangs - a read that blocks until the context ends.
type fakeLogs struct {
	payload []byte
	offset  int
	hang    bool
	done    <-chan struct{}
	closed  chan struct{}
	once    sync.Once
}

func (l *fakeLogs) Read(p []byte) (int, error) {
	if l.offset < len(l.payload) {
		read := copy(p, l.payload[l.offset:])
		l.offset += read
		return read, nil
	}
	if !l.hang {
		return 0, io.EOF
	}
	l.once.Do(func() { l.closed = make(chan struct{}) })
	select {
	case <-l.done:
	case <-l.closed:
	}
	return 0, io.EOF
}

func (l *fakeLogs) Close() error {
	l.once.Do(func() { l.closed = make(chan struct{}) })
	select {
	case <-l.closed:
	default:
		close(l.closed)
	}
	return nil
}

type fakePull struct{}

func (fakePull) Read([]byte) (int, error) { return 0, io.EOF }
func (fakePull) Close() error             { return nil }
func (fakePull) Wait(context.Context) error {
	return nil
}
func (fakePull) JSONMessages(context.Context) iter.Seq2[jsonstream.Message, error] {
	return func(func(jsonstream.Message, error) bool) {}
}

type stringReaderType struct {
	text   string
	offset int
}

func stringReader(text string) io.Reader { return &stringReaderType{text: text} }

func (r *stringReaderType) Read(p []byte) (int, error) {
	if r.offset >= len(r.text) {
		return 0, io.EOF
	}
	read := copy(p, r.text[r.offset:])
	r.offset += read
	return read, nil
}
