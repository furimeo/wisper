package dbengine

import (
	"context"
	"errors"
	"fmt"
	"io"
	"iter"
	"strconv"
	"sync"

	cerrdefs "github.com/containerd/errdefs"
	"github.com/moby/moby/api/types/container"
	"github.com/moby/moby/api/types/image"
	"github.com/moby/moby/api/types/jsonstream"
	"github.com/moby/moby/api/types/network"
	"github.com/moby/moby/client"
)

// A Docker engine that never talks to Docker.
//
// One file for one fake, and it is long because the interface it stands in for is: create,
// list, inspect, start, stop, remove, pull and connect. Splitting it would put half of one
// object's methods in another file, which is the arrangement that makes a fake drift out of
// step with the interface it implements.

// fakeContainer is one container this engine is pretending to run.
type fakeContainer struct {
	ID       string
	Name     string
	Labels   map[string]string
	Config   *container.Config
	Host     *container.HostConfig
	Running  bool
	Status   string
	Networks map[string]bool
}

type fakeDocker struct {
	mutex sync.Mutex

	// Created is every container this engine was asked to make, in order.
	Created []client.ContainerCreateOptions
	// Removed is every container id it was asked to delete, in order.
	Removed []string
	// Pulled is every image reference it was asked to fetch.
	Pulled []string
	// Present is the set of image references already on the node.
	Present map[string]bool
	// Networks is the set of tenant networks that exist. Connecting to one that is not here
	// answers "no such thing", which is what a node whose first workload has not been created
	// yet really looks like.
	Networks map[string]bool
	// Joined records every (network, container) pair that was connected.
	Joined []string

	// FailCreate makes ContainerCreate refuse, for the pass that has to carry on regardless.
	FailCreate error
	// FailList makes ContainerList refuse, which must never lead to anything being deleted.
	FailList error
	// StartsStopped leaves a created container not running, as a server that crashed on
	// startup would be.
	StartsStopped bool

	containers map[string]*fakeContainer
	sequence   int

	// onCreate is called after a container is made, so the fake servers can register one for
	// it. Set by the harness.
	onCreate func(*fakeContainer)
}

func newFakeDocker() *fakeDocker {
	return &fakeDocker{
		Present:    make(map[string]bool),
		Networks:   make(map[string]bool),
		containers: make(map[string]*fakeContainer),
	}
}

func (d *fakeDocker) ContainerCreate(_ context.Context, options client.ContainerCreateOptions) (client.ContainerCreateResult, error) {
	d.mutex.Lock()
	if d.FailCreate != nil {
		err := d.FailCreate
		d.mutex.Unlock()
		return client.ContainerCreateResult{}, err
	}

	d.sequence++
	id := "container-" + strconv.Itoa(d.sequence)
	d.Created = append(d.Created, options)

	made := &fakeContainer{
		ID:       id,
		Name:     options.Name,
		Config:   options.Config,
		Host:     options.HostConfig,
		Labels:   map[string]string{},
		Status:   "Created",
		Networks: make(map[string]bool),
	}
	if options.Config != nil && options.Config.Labels != nil {
		made.Labels = options.Config.Labels
	}
	d.containers[id] = made
	hook := d.onCreate
	d.mutex.Unlock()

	if hook != nil {
		hook(made)
	}
	return client.ContainerCreateResult{ID: id}, nil
}

func (d *fakeDocker) ContainerList(_ context.Context, _ client.ContainerListOptions) (client.ContainerListResult, error) {
	d.mutex.Lock()
	defer d.mutex.Unlock()
	if d.FailList != nil {
		return client.ContainerListResult{}, d.FailList
	}

	items := make([]container.Summary, 0, len(d.containers))
	for _, made := range d.containers {
		items = append(items, container.Summary{
			ID:     made.ID,
			Names:  []string{"/" + made.Name},
			Labels: made.Labels,
			Status: made.Status,
		})
	}
	return client.ContainerListResult{Items: items}, nil
}

func (d *fakeDocker) ContainerInspect(_ context.Context, id string, _ client.ContainerInspectOptions) (client.ContainerInspectResult, error) {
	d.mutex.Lock()
	defer d.mutex.Unlock()

	made, present := d.containers[id]
	if !present {
		return client.ContainerInspectResult{}, fmt.Errorf("no such container %s: %w", id, cerrdefs.ErrNotFound)
	}

	networks := make(map[string]*network.EndpointSettings, len(made.Networks))
	for name := range made.Networks {
		networks[name] = &network.EndpointSettings{}
	}
	state := container.StateExited
	if made.Running {
		state = container.StateRunning
	}
	return client.ContainerInspectResult{Container: container.InspectResponse{
		ID:              made.ID,
		Name:            "/" + made.Name,
		Config:          made.Config,
		HostConfig:      made.Host,
		State:           &container.State{Running: made.Running, Status: state},
		NetworkSettings: &container.NetworkSettings{Networks: networks},
	}}, nil
}

func (d *fakeDocker) ContainerStart(_ context.Context, id string, _ client.ContainerStartOptions) (client.ContainerStartResult, error) {
	d.mutex.Lock()
	defer d.mutex.Unlock()

	made, present := d.containers[id]
	if !present {
		return client.ContainerStartResult{}, fmt.Errorf("no such container %s: %w", id, cerrdefs.ErrNotFound)
	}
	if d.StartsStopped {
		made.Status = "Exited (1) 1 second ago"
		return client.ContainerStartResult{}, nil
	}
	made.Running = true
	made.Status = "Up 1 second"
	return client.ContainerStartResult{}, nil
}

func (d *fakeDocker) ContainerStop(_ context.Context, id string, _ client.ContainerStopOptions) (client.ContainerStopResult, error) {
	d.mutex.Lock()
	defer d.mutex.Unlock()
	if made, present := d.containers[id]; present {
		made.Running = false
		made.Status = "Exited (0) 1 second ago"
	}
	return client.ContainerStopResult{}, nil
}

func (d *fakeDocker) ContainerRemove(_ context.Context, id string, _ client.ContainerRemoveOptions) (client.ContainerRemoveResult, error) {
	d.mutex.Lock()
	defer d.mutex.Unlock()
	d.Removed = append(d.Removed, id)
	delete(d.containers, id)
	return client.ContainerRemoveResult{}, nil
}

func (d *fakeDocker) ImageInspect(_ context.Context, ref string, _ ...client.ImageInspectOption) (client.ImageInspectResult, error) {
	d.mutex.Lock()
	defer d.mutex.Unlock()
	if !d.Present[ref] {
		return client.ImageInspectResult{}, fmt.Errorf("no such image %s: %w", ref, cerrdefs.ErrNotFound)
	}
	return client.ImageInspectResult{InspectResponse: image.InspectResponse{ID: "sha256:" + ref}}, nil
}

func (d *fakeDocker) ImagePull(_ context.Context, ref string, _ client.ImagePullOptions) (client.ImagePullResponse, error) {
	d.mutex.Lock()
	defer d.mutex.Unlock()
	d.Pulled = append(d.Pulled, ref)
	d.Present[ref] = true
	return fakePull{}, nil
}

func (d *fakeDocker) NetworkConnect(_ context.Context, name string, options client.NetworkConnectOptions) (client.NetworkConnectResult, error) {
	d.mutex.Lock()
	defer d.mutex.Unlock()

	if !d.Networks[name] {
		return client.NetworkConnectResult{}, fmt.Errorf("no such network %s: %w", name, cerrdefs.ErrNotFound)
	}
	made, present := d.containers[options.Container]
	if !present {
		return client.NetworkConnectResult{}, fmt.Errorf("no such container: %w", cerrdefs.ErrNotFound)
	}
	made.Networks[name] = true
	d.Joined = append(d.Joined, name+"/"+made.Name)
	return client.NetworkConnectResult{}, nil
}

func (d *fakeDocker) Close() error { return nil }

// containerNamed is the container created with a given name, for a test that wants to assert
// on how it was configured.
func (d *fakeDocker) containerNamed(name string) (*fakeContainer, bool) {
	d.mutex.Lock()
	defer d.mutex.Unlock()
	for _, made := range d.containers {
		if made.Name == name {
			return made, true
		}
	}
	return nil, false
}

// running reports how many containers this engine is pretending to run.
func (d *fakeDocker) running() int {
	d.mutex.Lock()
	defer d.mutex.Unlock()
	count := 0
	for _, made := range d.containers {
		if made.Running {
			count++
		}
	}
	return count
}

// fakePull is a pull that has already finished.
type fakePull struct{}

func (fakePull) Read([]byte) (int, error)   { return 0, io.EOF }
func (fakePull) Close() error               { return nil }
func (fakePull) Wait(context.Context) error { return nil }
func (fakePull) JSONMessages(context.Context) iter.Seq2[jsonstream.Message, error] {
	return func(func(jsonstream.Message, error) bool) {}
}

// errEngineUnreachable is what a test uses to make Docker go away.
var errEngineUnreachable = errors.New("the Docker daemon is not answering")
