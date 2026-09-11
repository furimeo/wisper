package runtime

import (
	"context"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/moby/moby/api/types/container"
	"github.com/moby/moby/client"

	"github.com/furimeo/wisper/sasayaki/internal/reconcile"
	"github.com/furimeo/wisper/sasayaki/internal/spec"
)

// A workload with every field the panel actually sends, so a test can change one thing
// and assert on it without describing a whole service each time.
func appWorkload() spec.Workload {
	return spec.Workload{
		ID:            "42",
		Kind:          spec.KindApp,
		Name:          "Blog API",
		Image:         "nginx:1.27",
		Entrypoint:    []string{"/docker-entrypoint.sh"},
		Command:       []string{"nginx", "-g", "daemon off;"},
		WorkingDir:    "/app",
		Env:           []spec.EnvVar{{Name: "PORT", Value: "3000"}, {Name: "SECRET", Value: "s", Secret: true}},
		Runtime:       spec.RuntimeRunsc,
		Desired:       spec.DesiredRunning,
		TenantNetwork: "wisper-tenant-7",
		StopGrace:     30 * time.Second,
		Restart:       spec.Restart{Mode: spec.RestartOnFailure, MaxRetries: 5},
		Limits: spec.Limits{
			NanoCPUs:        500_000_000,
			MemoryBytes:     512 << 20,
			MemorySwapBytes: 512 << 20,
			PidsLimit:       256,
			NofileLimit:     65536,
		},
		Ports: []spec.Port{{Container: 3000, Protocol: spec.ProtocolTCP}},
	}
}

// readyEngine is a fake that can see the image and the network, so Create gets as far as
// building a configuration.
func readyEngine() *fakeEngine {
	api := newFake()
	api.onImage = func(string) (client.ImageInspectResult, error) {
		result := client.ImageInspectResult{}
		result.ID = "sha256:aaaa"
		result.RepoDigests = []string{"docker.io/library/nginx@sha256:bbbb"}
		return result, nil
	}
	api.onNetInspect = func(name string) (client.NetworkInspectResult, error) {
		return networkNamed(name, "wsp000000000"), nil
	}
	api.onInspect = func(id string) (client.ContainerInspectResult, error) {
		return client.ContainerInspectResult{Container: container.InspectResponse{
			ID:     id,
			Name:   "/wisper-blog-api-42",
			State:  &container.State{Status: container.StateCreated},
			Config: &container.Config{Image: "nginx:1.27"},
		}}, nil
	}
	return api
}

// The whole reason this package exists in one test.
//
// NanoCPUs is a hard ceiling in billionths of a core. CPUShares is a relative scheduling
// weight and bounds nothing. The predecessor set the second one from the first and every
// workload on the platform ran unlimited while the panel showed a tidy figure next to it.
func TestCreateSetsNanoCPUsAndNeverCPUShares(t *testing.T) {
	api := readyEngine()
	docker := newDocker(t, api, newHost())

	if _, err := docker.Create(context.Background(), appWorkload(), "wf1:abc"); err != nil {
		t.Fatalf("Create: %v", err)
	}

	host := api.lastCreate(t).HostConfig
	if host.NanoCPUs != 500_000_000 {
		t.Errorf("NanoCPUs = %d, want 500000000 (half a core)", host.NanoCPUs)
	}
	if host.CPUShares != 0 {
		t.Errorf("CPUShares = %d, want 0: it is a relative weight, not a limit, and setting "+
			"it is the predecessor's bug", host.CPUShares)
	}
	if host.CPUQuota != 0 || host.CPUPeriod != 0 {
		t.Errorf("CPUQuota/CPUPeriod = %d/%d, want 0/0: the ceiling travels as NanoCPUs and "+
			"two ways of saying it would eventually disagree", host.CPUQuota, host.CPUPeriod)
	}
}

func TestCreateAppliesTheRestOfTheCeilings(t *testing.T) {
	api := readyEngine()
	docker := newDocker(t, api, newHost())

	if _, err := docker.Create(context.Background(), appWorkload(), "wf1:abc"); err != nil {
		t.Fatalf("Create: %v", err)
	}
	host := api.lastCreate(t).HostConfig

	if host.Memory != 512<<20 {
		t.Errorf("Memory = %d, want %d", host.Memory, 512<<20)
	}
	if host.MemorySwap != 512<<20 {
		t.Errorf("MemorySwap = %d, want it equal to Memory so swap is disabled", host.MemorySwap)
	}
	if host.PidsLimit == nil || *host.PidsLimit != 256 {
		t.Errorf("PidsLimit = %v, want 256: a fork bomb inside gVisor is still a fork bomb", host.PidsLimit)
	}
	if len(host.Ulimits) != 1 || host.Ulimits[0].Name != "nofile" ||
		host.Ulimits[0].Soft != 65536 || host.Ulimits[0].Hard != 65536 {
		t.Errorf("Ulimits = %+v, want one nofile at 65536 soft and hard", host.Ulimits)
	}
	if host.RestartPolicy.Name != container.RestartPolicyOnFailure || host.RestartPolicy.MaximumRetryCount != 5 {
		t.Errorf("RestartPolicy = %+v, want on-failure with 5 retries", host.RestartPolicy)
	}
	if host.LogConfig.Type != "json-file" || host.LogConfig.Config["max-file"] != "3" {
		t.Errorf("LogConfig = %+v, want json-file rotated: an unrotated log fills the node", host.LogConfig)
	}
}

func TestCreateLabelsTheContainerForConvergence(t *testing.T) {
	api := readyEngine()
	docker := newDocker(t, api, newHost())

	if _, err := docker.Create(context.Background(), appWorkload(), "wf1:abcdef"); err != nil {
		t.Fatalf("Create: %v", err)
	}
	created := api.lastCreate(t)
	labels := created.Config.Labels

	if labels[reconcile.LabelManaged] != reconcile.LabelManagedValue {
		t.Errorf("%s = %q, want %q", reconcile.LabelManaged, labels[reconcile.LabelManaged], reconcile.LabelManagedValue)
	}
	if labels[reconcile.LabelWorkload] != "42" {
		t.Errorf("%s = %q, want the workload id", reconcile.LabelWorkload, labels[reconcile.LabelWorkload])
	}
	if labels[reconcile.LabelFingerprint] != "wf1:abcdef" {
		t.Errorf("%s = %q, want the fingerprint it was created from",
			reconcile.LabelFingerprint, labels[reconcile.LabelFingerprint])
	}
	if labels[labelImageDigest] != "sha256:bbbb" {
		t.Errorf("%s = %q, want the repository digest that was resolved",
			labelImageDigest, labels[labelImageDigest])
	}
	if created.Name != "wisper-blog-api-42" {
		t.Errorf("container name = %q, want it readable in docker ps and unique", created.Name)
	}
}

func TestCreateJoinsTheTenantNetworkWithStableNames(t *testing.T) {
	api := readyEngine()
	docker := newDocker(t, api, newHost())

	if _, err := docker.Create(context.Background(), appWorkload(), "wf1:abc"); err != nil {
		t.Fatalf("Create: %v", err)
	}
	created := api.lastCreate(t)

	if string(created.HostConfig.NetworkMode) != "wisper-tenant-7" {
		t.Errorf("NetworkMode = %q, want the tenant network", created.HostConfig.NetworkMode)
	}
	endpoint, joined := created.NetworkingConfig.EndpointsConfig["wisper-tenant-7"]
	if !joined {
		t.Fatalf("the container did not join its tenant network: %+v", created.NetworkingConfig)
	}
	if !slices.Contains(endpoint.Aliases, "42") {
		t.Errorf("aliases = %v, want the workload id so the edge can address it across a rename",
			endpoint.Aliases)
	}
}

func TestCreateRefusesAWorkloadWithNoTenantNetwork(t *testing.T) {
	api := readyEngine()
	docker := newDocker(t, api, newHost())

	workload := appWorkload()
	workload.TenantNetwork = ""
	_, err := docker.Create(context.Background(), workload, "wf1:abc")
	if err == nil {
		t.Fatal("created a container on the default bridge, where every other customer on "+
			"the node can reach it", err)
	}
	if !strings.Contains(err.Error(), "tenant network") {
		t.Errorf("error = %v, want it to name the tenant network", err)
	}
}

func TestCreateRefusesASite(t *testing.T) {
	docker := newDocker(t, readyEngine(), newHost())

	workload := appWorkload()
	workload.Kind = spec.KindSite
	if _, err := docker.Create(context.Background(), workload, "wf1:abc"); err == nil {
		t.Fatal("built a container for a static site, which is a directory and a symlink")
	}
}

func TestCreateRefusesWhenTheImageIsNotHere(t *testing.T) {
	api := readyEngine()
	api.onImage = nil // the default answers "no such image"
	docker := newDocker(t, api, newHost())

	if _, err := docker.Create(context.Background(), appWorkload(), "wf1:abc"); err == nil {
		t.Fatal("created a container from an image that is not on the node")
	}
}

func TestCreateKeepsSecretsOutOfNothingAndTheEnvironmentIntact(t *testing.T) {
	api := readyEngine()
	docker := newDocker(t, api, newHost())

	if _, err := docker.Create(context.Background(), appWorkload(), "wf1:abc"); err != nil {
		t.Fatalf("Create: %v", err)
	}
	environment := api.lastCreate(t).Config.Env
	if !slices.Contains(environment, "PORT=3000") || !slices.Contains(environment, "SECRET=s") {
		t.Errorf("Env = %v: a secret is marked so it stays out of logs, not withheld from "+
			"the process that needs it", environment)
	}
}
