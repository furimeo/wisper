package runtime

import (
	"context"
	"errors"
	"testing"
	"time"

	cerrdefs "github.com/containerd/errdefs"
	"github.com/moby/moby/api/types/container"
	"github.com/moby/moby/client"

	"github.com/furimeo/wisper/sasayaki/internal/reconcile"
	"github.com/furimeo/wisper/sasayaki/internal/spec"
)

func managedContainer(id, workloadID string) container.InspectResponse {
	return container.InspectResponse{
		ID:           id,
		Name:         "/wisper-blog-api-" + workloadID,
		RestartCount: 3,
		HostConfig:   &container.HostConfig{Runtime: "runsc"},
		Config: &container.Config{
			Image: "nginx:1.27",
			Labels: map[string]string{
				reconcile.LabelManaged:     reconcile.LabelManagedValue,
				reconcile.LabelWorkload:    workloadID,
				reconcile.LabelFingerprint: "wf1:deadbeef",
				labelImageDigest:           "sha256:bbbb",
			},
		},
		State: &container.State{
			Status:     container.StateRunning,
			Running:    true,
			StartedAt:  "2026-04-01T11:00:00.5Z",
			FinishedAt: "0001-01-01T00:00:00Z",
			Health:     &container.Health{Status: container.Healthy},
		},
	}
}

func TestContainersOnlyLooksAtThisLoopsOwnContainers(t *testing.T) {
	api := newFake()
	var asked client.ContainerListOptions
	api.onList = func(options client.ContainerListOptions) (client.ContainerListResult, error) {
		asked = options
		return client.ContainerListResult{Items: []container.Summary{
			{ID: "c1", Status: "Up 4 minutes"},
		}}, nil
	}
	api.onInspect = func(id string) (client.ContainerInspectResult, error) {
		return client.ContainerInspectResult{Container: managedContainer(id, "42")}, nil
	}
	docker := newDocker(t, api, newHost())

	found, err := docker.Containers(context.Background())
	if err != nil {
		t.Fatalf("Containers: %v", err)
	}

	if !asked.All {
		t.Error("stopped containers were left out, and a crashed one has an exit code the " +
			"customer is waiting to be shown")
	}
	if !asked.Filters["label"][reconcile.LabelManaged+"="+reconcile.LabelManagedValue] {
		t.Error("containers somebody else put on this machine are not excluded")
	}
	if !asked.Filters["label"][reconcile.LabelWorkload] {
		t.Error("this daemon's own database engines and builds are not excluded, and a loop " +
			"that removed what it did not recognise would take out the shared PostgreSQL")
	}

	if len(found) != 1 {
		t.Fatalf("got %d containers, want 1", len(found))
	}
	got := found[0]
	if got.WorkloadID != "42" || got.Fingerprint != "wf1:deadbeef" || got.ImageDigest != "sha256:bbbb" {
		t.Errorf("container = %+v, want the labels read back", got)
	}
	if got.Name != "wisper-blog-api-42" {
		t.Errorf("name = %q, want the engine's leading slash gone", got.Name)
	}
	if !got.Running || got.Health != reconcile.HealthPassing || got.RestartCount != 3 {
		t.Errorf("container = %+v, want it running, healthy and restarted three times", got)
	}
	if got.Runtime != spec.RuntimeRunsc {
		t.Errorf("runtime = %q, want what the container is really running under", got.Runtime)
	}
	if got.Status != "Up 4 minutes" {
		t.Errorf("status = %q, want the engine's own sentence", got.Status)
	}
	if !got.StartedAt.Equal(time.Date(2026, 4, 1, 11, 0, 0, 500_000_000, time.UTC)) {
		t.Errorf("startedAt = %v, want the parsed start time", got.StartedAt)
	}
	if !got.FinishedAt.IsZero() {
		t.Errorf("finishedAt = %v, want zero: the engine writes year 1 for 'has not happened' "+
			"and a customer must not be shown it", got.FinishedAt)
	}
}

func TestAContainerRemovedMidListIsSkippedRatherThanFatal(t *testing.T) {
	api := newFake()
	api.onList = func(client.ContainerListOptions) (client.ContainerListResult, error) {
		return client.ContainerListResult{Items: []container.Summary{{ID: "gone"}, {ID: "here"}}}, nil
	}
	api.onInspect = func(id string) (client.ContainerInspectResult, error) {
		if id == "gone" {
			return client.ContainerInspectResult{}, cerrdefs.ErrNotFound.WithMessage("no such container")
		}
		return client.ContainerInspectResult{Container: managedContainer(id, "42")}, nil
	}
	docker := newDocker(t, api, newHost())

	found, err := docker.Containers(context.Background())
	if err != nil {
		t.Fatalf("Containers: %v", err)
	}
	if len(found) != 1 || found[0].ID != "here" {
		t.Errorf("got %+v, want only the container that is still there", found)
	}
}

// The rule that outranks everything else in this package: an engine that cannot be asked
// is not an empty machine. Returning an empty list here would have the reconcile loop
// conclude that every workload had gone.
func TestAnEngineThatCannotBeAskedIsAnErrorAndNotAnEmptyNode(t *testing.T) {
	api := newFake()
	api.onList = func(client.ContainerListOptions) (client.ContainerListResult, error) {
		return client.ContainerListResult{}, errors.New("connection refused")
	}
	docker := newDocker(t, api, newHost())

	found, err := docker.Containers(context.Background())
	if err == nil {
		t.Fatal("a Docker outage was reported as a node with nothing on it")
	}
	if found != nil {
		t.Errorf("got %+v alongside the error, want nothing at all", found)
	}
}

// The terminal is asked to open a shell in a workload and the log feed to tail one;
// neither is told a container id. And the two answers have to stay apart: "your service
// is not running" is a fact, "the engine did not answer" is not.
func TestContainerForDistinguishesAbsenceFromAnEngineThatWillNotAnswer(t *testing.T) {
	api := newFake()
	var asked client.ContainerListOptions
	api.onList = func(options client.ContainerListOptions) (client.ContainerListResult, error) {
		asked = options
		return client.ContainerListResult{Items: []container.Summary{{ID: "c1", Status: "Up 1 hour"}}}, nil
	}
	api.onInspect = func(id string) (client.ContainerInspectResult, error) {
		return client.ContainerInspectResult{Container: managedContainer(id, "42")}, nil
	}
	docker := newDocker(t, api, newHost())

	found, present, err := docker.ContainerFor(context.Background(), "42")
	if err != nil || !present {
		t.Fatalf("ContainerFor: %v present=%v", err, present)
	}
	if found.ID != "c1" {
		t.Errorf("container = %+v", found)
	}
	if !asked.Filters["label"][reconcile.LabelWorkload+"=42"] {
		t.Errorf("filters = %v, want the workload named on the label", asked.Filters)
	}

	api.onList = func(client.ContainerListOptions) (client.ContainerListResult, error) {
		return client.ContainerListResult{}, nil
	}
	if _, present, err := docker.ContainerFor(context.Background(), "42"); err != nil || present {
		t.Errorf("a workload with no container: present=%v err=%v, want absent and no error", present, err)
	}

	api.onList = func(client.ContainerListOptions) (client.ContainerListResult, error) {
		return client.ContainerListResult{}, errors.New("connection refused")
	}
	if _, present, err := docker.ContainerFor(context.Background(), "42"); err == nil || present {
		t.Error("an engine that could not be asked was reported as a workload that is not running")
	}
}

func TestContainerForRefusesAWorkloadIdItCannotTrust(t *testing.T) {
	docker := newDocker(t, newFake(), newHost())
	if _, _, err := docker.ContainerFor(context.Background(), "../evil"); err == nil {
		t.Fatal("a workload id that is a path fragment reached a label filter")
	}
}

func TestHealthHasFourStatesBecauseTwoOfThemAreNotFailures(t *testing.T) {
	cases := map[*container.Health]reconcile.ContainerHealth{
		nil:                               reconcile.HealthNone,
		{Status: container.NoHealthcheck}: reconcile.HealthNone,
		{Status: container.Starting}:      reconcile.HealthStarting,
		{Status: container.Healthy}:       reconcile.HealthPassing,
		{Status: container.Unhealthy}:     reconcile.HealthFailing,
	}
	for health, want := range cases {
		if got := healthOf(health); got != want {
			t.Errorf("healthOf(%+v) = %q, want %q", health, got, want)
		}
	}
}

func TestAContainerWithNoSummaryStillGetsASentence(t *testing.T) {
	exited := managedContainer("c1", "42")
	exited.State = &container.State{Status: container.StateExited, ExitCode: 137, OOMKilled: true}

	got := containerFrom(exited, "")
	if got.Status == "" {
		t.Fatal("a container with no summary line was given an empty status")
	}
	if !got.OOMKilled || got.ExitCode != 137 {
		t.Errorf("container = %+v, want the out-of-memory kill and its code", got)
	}
}

func TestARuntimeTheEngineDidNotNameIsRunc(t *testing.T) {
	if got := runtimeOf(nil); got != spec.RuntimeRunc {
		t.Errorf("runtime = %q, want runc: an unnamed runtime is the engine's default and "+
			"the node must not imply gVisor", got)
	}
	if got := runtimeOf(&container.HostConfig{}); got != spec.RuntimeRunc {
		t.Errorf("runtime = %q, want runc", got)
	}
}
