package spec

import (
	"slices"
	"testing"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

func TestWorkloadFromProtoReadsEveryField(t *testing.T) {
	workload := workloadFromProto(&wisperpb.Workload{
		Id:          "1e9d",
		Kind:        wisperpb.WorkloadKind_WORKLOAD_KIND_APP,
		Name:        "api",
		Image:       "ghcr.io/acme/api:3",
		ImageDigest: "sha256:abc",
		Entrypoint:  []string{"/bin/tini", "--"},
		Command:     []string{"node", "server.js"},
		WorkingDir:  "/srv",
		Env: []*wisperpb.EnvVar{
			{Name: "PORT", Value: "8080"},
			{Name: "DATABASE_URL", Value: "postgres://...", Secret: true},
		},
		Limits: &wisperpb.ResourceLimits{
			NanoCpus:        500_000_000,
			MemoryBytes:     536_870_912,
			MemorySwapBytes: 536_870_912,
			PidsLimit:       512,
			DiskBytes:       1 << 30,
			NofileLimit:     65_536,
		},
		Mounts: []*wisperpb.Mount{{
			VolumeId:   "vol-1",
			Kind:       wisperpb.MountKind_MOUNT_KIND_VOLUME,
			Target:     "/data",
			QuotaBytes: 1 << 30,
		}},
		Ports: []*wisperpb.PortBinding{{
			ContainerPort: 8080,
			Protocol:      wisperpb.PortProtocol_PORT_PROTOCOL_TCP,
		}},
		Restart: &wisperpb.RestartPolicy{
			Mode:       wisperpb.RestartPolicyMode_RESTART_POLICY_MODE_ON_FAILURE,
			MaxRetries: 5,
		},
		Runtime:      wisperpb.ContainerRuntime_CONTAINER_RUNTIME_RUNSC,
		DesiredState: wisperpb.DesiredState_DESIRED_STATE_RUNNING,
		HealthCheck: &wisperpb.HealthCheck{
			Test:               []string{"wget", "--spider", "http://127.0.0.1:8080/up"},
			IntervalSeconds:    30,
			TimeoutSeconds:     10,
			Retries:            3,
			StartPeriodSeconds: 20,
		},
		TenantNetwork:    "wisper-tenant-9f",
		ReadOnlyRootfs:   true,
		User:             "1000:1000",
		StopGraceSeconds: 30,
		ReleaseId:        "rel-7",
	})

	if !workload.IsApp() || workload.IsSite() {
		t.Errorf("kind = %q, want APP", workload.Kind)
	}
	if workload.Desired != DesiredRunning {
		t.Errorf("desired = %q, want RUNNING", workload.Desired)
	}
	if workload.Runtime != RuntimeRunsc {
		t.Errorf("runtime = %q, want RUNSC", workload.Runtime)
	}
	if workload.StopGrace != 30*time.Second {
		t.Errorf("stopGrace = %s, want 30s", workload.StopGrace)
	}
	if workload.Limits.NanoCPUs != 500_000_000 {
		t.Errorf("nanoCPUs = %d, want 500000000 (half a core)", workload.Limits.NanoCPUs)
	}
	if got := len(workload.Mounts); got != 1 || workload.Mounts[0].Kind != MountKindVolume {
		t.Errorf("mounts = %+v, want one VOLUME mount", workload.Mounts)
	}
	if got := len(workload.Ports); got != 1 || workload.Ports[0].Protocol != ProtocolTCP {
		t.Errorf("ports = %+v, want one TCP binding", workload.Ports)
	}
	if workload.Ports[0].IsPublished() {
		t.Error("host port 0 means do not publish")
	}
	if workload.Restart.Mode != RestartOnFailure || workload.Restart.MaxRetries != 5 {
		t.Errorf("restart = %+v, want ON_FAILURE after 5", workload.Restart)
	}
	if !workload.Health.IsSet() || workload.Health.Interval != 30*time.Second {
		t.Errorf("health = %+v, want a check every 30s", workload.Health)
	}
	if !workload.ReadOnlyRootfs || workload.User != "1000:1000" {
		t.Errorf("hardening lost: rootfs=%v user=%q", workload.ReadOnlyRootfs, workload.User)
	}
	if workload.ReleaseID != "rel-7" {
		t.Errorf("releaseID = %q, want rel-7", workload.ReleaseID)
	}
}

// Environment order is the panel's, and the secret flag travels with the value so the node
// knows what must not reach a log line without guessing from the name.
func TestEnvironmentKeepsOrderAndSecrecy(t *testing.T) {
	workload := workloadFromProto(&wisperpb.Workload{Env: []*wisperpb.EnvVar{
		{Name: "AAA", Value: "1"},
		{Name: "DB_PW", Value: "hunter2", Secret: true},
		{Name: "ZZZ", Value: "3"},
	}})

	want := []string{"AAA=1", "DB_PW=hunter2", "ZZZ=3"}
	if got := workload.Environ(); !slices.Equal(got, want) {
		t.Errorf("Environ() = %v, want %v", got, want)
	}
	if !workload.Env[1].Secret {
		t.Error("the secret flag did not survive the conversion")
	}
	if workload.Env[0].Secret || workload.Env[2].Secret {
		t.Error("a plain variable was marked secret")
	}
}

// An unrecognised kind must not be guessed at. Starting a container for something the panel
// meant as a directory of files is worse than reporting that this workload cannot be
// handled.
func TestUnknownKindIsNamedRatherThanGuessed(t *testing.T) {
	workload := workloadFromProto(&wisperpb.Workload{
		Kind: wisperpb.WorkloadKind(99),
	})

	if workload.Kind != KindUnknown {
		t.Errorf("kind = %q, want UNKNOWN", workload.Kind)
	}
	if workload.IsApp() || workload.IsSite() {
		t.Error("an unknown kind is neither an app nor a site")
	}
}

// An unspecified desired state is a panel bug, and the safe answer is to name it: the
// reconciler then neither starts nor stops nor removes the workload.
func TestUnspecifiedDesiredStateIsNotRunning(t *testing.T) {
	workload := workloadFromProto(&wisperpb.Workload{})

	if workload.Desired != DesiredUnspecified {
		t.Errorf("desired = %q, want UNSPECIFIED", workload.Desired)
	}
	if workload.Desired == DesiredRunning || workload.Desired == DesiredStopped {
		t.Error("an unspecified desired state must not be read as an instruction")
	}
}

// Defaulting the other way would hand a workload less isolation than the panel asked for
// and say nothing about it.
func TestUnspecifiedRuntimeIsTheSecureOne(t *testing.T) {
	if got := runtimeFromProto(wisperpb.ContainerRuntime_CONTAINER_RUNTIME_UNSPECIFIED); got != RuntimeRunsc {
		t.Errorf("runtime = %q, want RUNSC", got)
	}
	if got := runtimeFromProto(wisperpb.ContainerRuntime_CONTAINER_RUNTIME_RUNC); got != RuntimeRunc {
		t.Errorf("runtime = %q, want RUNC", got)
	}
}

func TestRuntimeRoundTrips(t *testing.T) {
	for _, runtime := range []Runtime{RuntimeRunsc, RuntimeRunc} {
		if got := runtimeFromProto(runtimeToProto(runtime)); got != runtime {
			t.Errorf("%q round-tripped to %q", runtime, got)
		}
	}
}

// A mount whose kind this binary cannot resolve must not be silently dropped: an
// application that comes up with an empty data directory writes into it.
func TestUnknownMountKindIsNamed(t *testing.T) {
	mount := mountFromProto(&wisperpb.Mount{
		VolumeId: "vol-1",
		Kind:     wisperpb.MountKind(42),
		Target:   "/data",
	})

	if mount.Kind != MountKindUnknown {
		t.Errorf("kind = %q, want UNKNOWN", mount.Kind)
	}
	if mount.Target != "/data" {
		t.Errorf("target = %q, want /data: the rest of the mount is still readable", mount.Target)
	}
}

// Docker's own default, and safe here because the reconcile loop is the real backstop.
func TestUnspecifiedRestartModeIsNever(t *testing.T) {
	if got := restartFromProto(nil).Mode; got != RestartNever {
		t.Errorf("a missing policy gave %q, want NEVER", got)
	}
	if got := restartFromProto(&wisperpb.RestartPolicy{}).Mode; got != RestartNever {
		t.Errorf("an unspecified mode gave %q, want NEVER", got)
	}
}

// An empty test vector disables the check; that is the proto's rule, and IsSet is how a
// caller asks without dereferencing an absence.
func TestHealthIsUnsetWhenThereIsNoTest(t *testing.T) {
	if healthFromProto(nil).IsSet() {
		t.Error("a missing health check should not be set")
	}
	if healthFromProto(&wisperpb.HealthCheck{IntervalSeconds: 30}).IsSet() {
		t.Error("an empty test vector disables the check")
	}
	if !healthFromProto(&wisperpb.HealthCheck{Test: []string{"true"}}).IsSet() {
		t.Error("a test vector enables the check")
	}
}

func TestSiteIndexDefaults(t *testing.T) {
	if got := siteOptionsFromProto(nil).Index(); got != "index.html" {
		t.Errorf("index = %q, want index.html", got)
	}
	options := siteOptionsFromProto(&wisperpb.SiteOptions{IndexFile: "main.html"})
	if got := options.Index(); got != "main.html" {
		t.Errorf("index = %q, want main.html", got)
	}
	if options.DirectoryListing {
		t.Error("directory listing must stay off unless the customer asked")
	}
}

func TestWorkloadFromProtoAcceptsNil(t *testing.T) {
	workload := workloadFromProto(nil)

	if workload.ID != "" || workload.Kind != KindUnknown {
		t.Errorf("a nil workload gave %+v", workload)
	}
	if got := workload.Environ(); len(got) != 0 {
		t.Errorf("Environ() = %v, want empty", got)
	}
}
