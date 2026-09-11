package runtime

import (
	"context"
	"slices"
	"testing"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
)

func TestAnEmptyEntrypointLeavesTheImagesOwnAlone(t *testing.T) {
	api := readyEngine()
	docker := newDocker(t, api, newHost())

	workload := appWorkload()
	workload.Entrypoint = nil
	workload.Command = nil
	if _, err := docker.Create(context.Background(), workload, "wf1:abc"); err != nil {
		t.Fatalf("Create: %v", err)
	}

	config := api.lastCreate(t).Config
	if config.Entrypoint != nil || config.Cmd != nil {
		t.Errorf("entrypoint=%v cmd=%v, want both left unset: an empty slice clears the "+
			"image's own and the container starts with nothing to run",
			config.Entrypoint, config.Cmd)
	}
}

// The panel decides whether there is a health check. A customer shown as unhealthy by a
// check they did not configure, cannot see and cannot switch off has been failed by the
// platform rather than helped by it.
func TestNoHealthCheckMeansNoneRatherThanWhateverTheImageDeclares(t *testing.T) {
	api := readyEngine()
	docker := newDocker(t, api, newHost())

	workload := appWorkload()
	workload.Health = spec.Health{}
	if _, err := docker.Create(context.Background(), workload, "wf1:abc"); err != nil {
		t.Fatalf("Create: %v", err)
	}

	health := api.lastCreate(t).Config.Healthcheck
	if health == nil || !slices.Equal(health.Test, []string{"NONE"}) {
		t.Errorf("healthcheck = %+v, want an explicit NONE", health)
	}
}

func TestAHealthCheckGetsDockersCmdMarkerFromTheNode(t *testing.T) {
	api := readyEngine()
	docker := newDocker(t, api, newHost())

	workload := appWorkload()
	workload.Health = spec.Health{
		Test:        []string{"wget", "--spider", "http://127.0.0.1:3000/healthz"},
		Interval:    10 * time.Second,
		Timeout:     10 * time.Second,
		Retries:     3,
		StartPeriod: 20 * time.Second,
	}
	if _, err := docker.Create(context.Background(), workload, "wf1:abc"); err != nil {
		t.Fatalf("Create: %v", err)
	}

	health := api.lastCreate(t).Config.Healthcheck
	want := []string{"CMD", "wget", "--spider", "http://127.0.0.1:3000/healthz"}
	if !slices.Equal(health.Test, want) {
		t.Errorf("test = %v, want %v: the wire carries argv and the node adds the marker",
			health.Test, want)
	}
	if health.Interval != 10*time.Second || health.StartPeriod != 20*time.Second || health.Retries != 3 {
		t.Errorf("healthcheck = %+v, want the intervals as durations", health)
	}
}

func TestTheStopTimeoutIsRecordedOnTheContainerAsWell(t *testing.T) {
	api := readyEngine()
	docker := newDocker(t, api, newHost())

	if _, err := docker.Create(context.Background(), appWorkload(), "wf1:abc"); err != nil {
		t.Fatalf("Create: %v", err)
	}
	timeout := api.lastCreate(t).Config.StopTimeout
	if timeout == nil || *timeout != 30 {
		t.Errorf("stopTimeout = %v, want 30: it matters when the engine stops the container "+
			"without going through this daemon, such as a host reboot", timeout)
	}
}

func TestAWorkloadIsNeverGivenATtyOrStdin(t *testing.T) {
	api := readyEngine()
	docker := newDocker(t, api, newHost())

	if _, err := docker.Create(context.Background(), appWorkload(), "wf1:abc"); err != nil {
		t.Fatalf("Create: %v", err)
	}
	config := api.lastCreate(t).Config
	if config.Tty {
		t.Error("with a tty the engine stops multiplexing stdout and stderr, and the log feed " +
			"loses the difference between a crash and an access log")
	}
	if config.OpenStdin {
		t.Error("stdin was opened on a service")
	}
}

func TestRetentionFromTheSpecReachesTheNextContainer(t *testing.T) {
	api := readyEngine()
	docker := newDocker(t, api, newHost())

	docker.UseRetention(spec.Retention{ContainerLogMaxBytes: 8 << 20, ContainerLogMaxFiles: 5})
	if _, err := docker.Create(context.Background(), appWorkload(), "wf1:abc"); err != nil {
		t.Fatalf("Create: %v", err)
	}

	logging := api.lastCreate(t).HostConfig.LogConfig
	if logging.Config["max-size"] != "8388608" || logging.Config["max-file"] != "5" {
		t.Errorf("logConfig = %+v, want the policy from the current spec", logging.Config)
	}
}

func TestAnUnpublishedPortIsStillDeclared(t *testing.T) {
	api := readyEngine()
	docker := newDocker(t, api, newHost())

	if _, err := docker.Create(context.Background(), appWorkload(), "wf1:abc"); err != nil {
		t.Fatalf("Create: %v", err)
	}
	created := api.lastCreate(t)
	if len(created.Config.ExposedPorts) != 1 {
		t.Errorf("exposedPorts = %v, want the container port declared", created.Config.ExposedPorts)
	}
	if len(created.HostConfig.PortBindings) != 0 {
		t.Errorf("portBindings = %v, want nothing published: HTTP arrives through the "+
			"embedded Caddy over the tenant network", created.HostConfig.PortBindings)
	}
}

func TestAPublishedPortCanBeHeldToOneInterface(t *testing.T) {
	api := readyEngine()
	docker := newDocker(t, api, newHost())

	workload := appWorkload()
	workload.Ports = []spec.Port{{Container: 5432, Host: 15432, Protocol: spec.ProtocolTCP, HostIP: "127.0.0.1"}}
	if _, err := docker.Create(context.Background(), workload, "wf1:abc"); err != nil {
		t.Fatalf("Create: %v", err)
	}

	bindings := api.lastCreate(t).HostConfig.PortBindings
	if len(bindings) != 1 {
		t.Fatalf("portBindings = %v, want one", bindings)
	}
	for port, bound := range bindings {
		if port.Num() != 5432 || port.Proto() != "tcp" {
			t.Errorf("port = %v, want 5432/tcp", port)
		}
		if bound[0].HostPort != "15432" || bound[0].HostIP.String() != "127.0.0.1" {
			t.Errorf("binding = %+v, want it off the public interface", bound[0])
		}
	}
}

func TestAPortNumberThatIsNotOneIsRefused(t *testing.T) {
	docker := newDocker(t, readyEngine(), newHost())

	workload := appWorkload()
	workload.Ports = []spec.Port{{Container: 0}}
	if _, _, err := portsFor(workload); err == nil {
		t.Error("port 0 was accepted as a container port")
	}

	workload.Ports = []spec.Port{{Container: 80, Host: 80, HostIP: "not-an-address"}}
	if _, _, err := portsFor(workload); err == nil {
		t.Error("a bind address that is not one was accepted")
	}
	_ = docker
}
