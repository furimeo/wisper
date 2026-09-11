package reconcile

import (
	"strings"
	"testing"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
)

func TestTheSameWorkloadAlwaysHashesTheSame(t *testing.T) {
	// If it did not, every fifteen-second pass would find drift and rebuild a perfectly
	// good container, forever.
	workload := app("wl-api")
	workload.Env = []spec.EnvVar{{Name: "A", Value: "1"}, {Name: "B", Value: "2", Secret: true}}
	workload.Mounts = []spec.Mount{{VolumeID: "v", Kind: spec.MountKindVolume, Target: "/d"}}

	first := Fingerprint(workload)
	for range 5 {
		if again := Fingerprint(workload); again != first {
			t.Fatalf("the same workload hashed to %s and then %s", first, again)
		}
	}
	if !strings.HasPrefix(first, fingerprintVersion+":") {
		t.Fatalf("the fingerprint %q does not carry its version", first)
	}
}

func TestEveryFieldThatShapesAContainerChangesTheFingerprint(t *testing.T) {
	base := app("wl-api")
	base.Env = []spec.EnvVar{{Name: "NODE_ENV", Value: "production"}}
	base.Limits = spec.Limits{NanoCPUs: 500_000_000, MemoryBytes: 512 << 20}

	changes := map[string]func(w *spec.Workload){
		"the image":             func(w *spec.Workload) { w.Image = "docker.io/library/busybox:2" },
		"the resolved digest":   func(w *spec.Workload) { w.ImageDigest = "sha256:beef" },
		"an environment value":  func(w *spec.Workload) { w.Env[0].Value = "staging" },
		"an environment name":   func(w *spec.Workload) { w.Env[0].Name = "NODE_ENVIRONMENT" },
		"a new variable":        func(w *spec.Workload) { w.Env = append(w.Env, spec.EnvVar{Name: "X", Value: ""}) },
		"the cpu ceiling":       func(w *spec.Workload) { w.Limits.NanoCPUs = 1_000_000_000 },
		"the memory ceiling":    func(w *spec.Workload) { w.Limits.MemoryBytes = 1 << 30 },
		"the pids ceiling":      func(w *spec.Workload) { w.Limits.PidsLimit = 128 },
		"the disk quota":        func(w *spec.Workload) { w.Limits.DiskBytes = 1 << 30 },
		"the command":           func(w *spec.Workload) { w.Command = []string{"sh", "-c", "sleep 1"} },
		"the container runtime": func(w *spec.Workload) { w.Runtime = spec.RuntimeRunc },
		"the tenant network":    func(w *spec.Workload) { w.TenantNetwork = "wisper-tenant-9" },
		"a mount": func(w *spec.Workload) {
			w.Mounts = []spec.Mount{{VolumeID: "v", Kind: spec.MountKindVolume, Target: "/d"}}
		},
		"a published port":       func(w *spec.Workload) { w.Ports = []spec.Port{{Container: 8080, Host: 8080}} },
		"the read-only rootfs":   func(w *spec.Workload) { w.ReadOnlyRootfs = true },
		"the user":               func(w *spec.Workload) { w.User = "10001:10001" },
		"the stop grace":         func(w *spec.Workload) { w.StopGrace = 45 * time.Second },
		"the health check":       func(w *spec.Workload) { w.Health = spec.Health{Test: []string{"wget", "-q", "/"}} },
		"the restart policy":     func(w *spec.Workload) { w.Restart = spec.Restart{Mode: spec.RestartAlways} },
		"the name on the engine": func(w *spec.Workload) { w.Name = "api-2" },
	}

	original := Fingerprint(base)
	for what, change := range changes {
		changed := base
		changed.Env = append([]spec.EnvVar(nil), base.Env...)
		change(&changed)
		if Fingerprint(changed) == original {
			t.Errorf("changing %s did not change the fingerprint, so the drift would be invisible", what)
		}
	}
}

func TestPausingAWorkloadDoesNotChangeItsFingerprint(t *testing.T) {
	// A pause button that destroyed the container would take the customer's logs with it.
	running := app("wl-api")
	stopped := running
	stopped.Desired = spec.DesiredStopped

	if Fingerprint(running) != Fingerprint(stopped) {
		t.Fatal("stopping a workload changed its fingerprint, which would rebuild it on the way back")
	}
}

func TestASiteReleaseDoesNotChangeAnAppFingerprint(t *testing.T) {
	// The release id names a directory the symlink points at. The mount's target never
	// moves, so the container does not have to be rebuilt when a site is deployed.
	before := app("wl-api")
	after := before
	after.ReleaseID = "dep-2000"
	after.Site = spec.SiteOptions{SPAFallback: true}

	if Fingerprint(before) != Fingerprint(after) {
		t.Fatal("a new site release rebuilt an app container")
	}
}

func TestFieldsCannotBleedIntoEachOther(t *testing.T) {
	// Without a length in front of every string, {Name: "ab", Image: "c"} and
	// {Name: "a", Image: "bc"} hash identically, and a rename that shifted one character
	// between two fields would be invisible drift.
	first := app("wl")
	first.Name = "ab"
	first.Image = "c"

	second := app("wl")
	second.Name = "a"
	second.Image = "bc"

	if Fingerprint(first) == Fingerprint(second) {
		t.Fatal("two different workloads hashed the same; the fields are not delimited")
	}
}

func TestShortFingerprintKeepsTheVersionAndSomeOfTheHash(t *testing.T) {
	full := Fingerprint(app("wl-api"))
	brief := short(full)

	if !strings.HasPrefix(full, brief) {
		t.Fatalf("the short form %q is not a prefix of %q", brief, full)
	}
	if len(brief) >= len(full) {
		t.Fatalf("the short form %q is no shorter than the full hash", brief)
	}
}
