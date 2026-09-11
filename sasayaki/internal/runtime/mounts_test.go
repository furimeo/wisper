package runtime

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/moby/moby/api/types/mount"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
)

func withMounts(mounts ...spec.Mount) spec.Workload {
	workload := appWorkload()
	workload.Mounts = mounts
	return workload
}

func TestVolumeMountResolvesUnderTheStateRoot(t *testing.T) {
	docker := newDocker(t, readyEngine(), newHost())

	mounts, err := docker.mountsFor(t.Context(), withMounts(spec.Mount{
		VolumeID: "7", Kind: spec.MountKindVolume, Target: "/data",
	}))
	if err != nil {
		t.Fatalf("mountsFor: %v", err)
	}
	if len(mounts) != 1 {
		t.Fatalf("got %d mounts, want 1", len(mounts))
	}

	want := filepath.Join(docker.stateDir, "volumes", "42", "7")
	if mounts[0].Source != want {
		t.Errorf("source = %q, want %q: the layout is ids under the state root, so renaming "+
			"a service never moves a directory", mounts[0].Source, want)
	}
	if mounts[0].Type != mount.TypeBind || mounts[0].Target != "/data" || mounts[0].ReadOnly {
		t.Errorf("mount = %+v, want a writable bind at /data", mounts[0])
	}
	if info, err := os.Stat(want); err != nil || !info.IsDir() {
		t.Errorf("the volume directory was not created: %v", err)
	}
}

// The panel never sends a path, so there is no field in which to ask for the Docker
// socket. What is left is an id that tries to climb out of the tree, and that is refused
// rather than cleaned up: sanitising would map two different ids onto one directory.
func TestAnIdentifierCanNeverEscapeTheStateRoot(t *testing.T) {
	docker := newDocker(t, readyEngine(), newHost())

	for _, hostile := range []string{
		"../../../var/run",
		"..",
		".",
		".hidden",
		"a/b",
		`a\b`,
		"/var/run/docker.sock",
		"7\x00",
		strings.Repeat("x", maxIdentifier+1),
	} {
		_, err := docker.mountsFor(t.Context(), withMounts(spec.Mount{
			VolumeID: hostile, Kind: spec.MountKindVolume, Target: "/data",
		}))
		if err == nil {
			t.Errorf("volume id %q was accepted", hostile)
		}
	}
}

func TestASiteReleaseIsAlwaysReadOnly(t *testing.T) {
	docker := newDocker(t, readyEngine(), newHost())

	mounts, err := docker.mountsFor(t.Context(), withMounts(spec.Mount{
		VolumeID: "9", Kind: spec.MountKindSiteRelease, Target: "/site", ReadOnly: false,
	}))
	if err != nil {
		t.Fatalf("mountsFor: %v", err)
	}
	if !mounts[0].ReadOnly {
		t.Error("a site release was mounted writable: the next deployment would silently " +
			"revert whatever a customer edited in place")
	}
	want := filepath.Join(docker.stateDir, "sites", "9", "current")
	if mounts[0].Source != want {
		t.Errorf("source = %q, want the site's current symlink at %q", mounts[0].Source, want)
	}
}

func TestAnUnknownMountKindRefusesTheWholeWorkload(t *testing.T) {
	docker := newDocker(t, readyEngine(), newHost())

	_, err := docker.mountsFor(t.Context(), withMounts(spec.Mount{
		VolumeID: "7", Kind: spec.MountKindUnknown, Target: "/data",
	}))
	if err == nil {
		t.Fatal("started a workload without a mount it asked for: an application that comes " +
			"up with an empty data directory writes into it")
	}
}

func TestTmpfsIsNeverUnbounded(t *testing.T) {
	docker := newDocker(t, readyEngine(), newHost())

	mounts, err := docker.mountsFor(t.Context(), withMounts(spec.Mount{
		Kind: spec.MountKindTmpfs, Target: "/tmp",
	}))
	if err != nil {
		t.Fatalf("mountsFor: %v", err)
	}
	if mounts[0].TmpfsOptions == nil || mounts[0].TmpfsOptions.SizeBytes <= 0 {
		t.Fatalf("tmpfs = %+v, want a size: an unbounded one is memory exhaustion with extra steps",
			mounts[0].TmpfsOptions)
	}

	// A workload with a small memory ceiling gets a tmpfs it cannot use to exceed it.
	small := withMounts(spec.Mount{Kind: spec.MountKindTmpfs, Target: "/tmp"})
	small.Limits.MemoryBytes = 8 << 20
	mounts, err = docker.mountsFor(t.Context(), small)
	if err != nil {
		t.Fatalf("mountsFor: %v", err)
	}
	if mounts[0].TmpfsOptions.SizeBytes != 8<<20 {
		t.Errorf("tmpfs size = %d, want it held down to the memory ceiling %d",
			mounts[0].TmpfsOptions.SizeBytes, 8<<20)
	}
}

func TestATargetPathHasToBeSomewhereSensible(t *testing.T) {
	docker := newDocker(t, readyEngine(), newHost())

	for _, target := range []string{"", "data", "/", "/data/../etc", "./data"} {
		_, err := docker.mountsFor(t.Context(), withMounts(spec.Mount{
			VolumeID: "7", Kind: spec.MountKindVolume, Target: target,
		}))
		if err == nil {
			t.Errorf("target %q was accepted", target)
		}
	}

	// A trailing slash is somebody typing a directory the way people type directories,
	// and is normalised rather than refused.
	mounts, err := docker.mountsFor(t.Context(), withMounts(spec.Mount{
		VolumeID: "7", Kind: spec.MountKindVolume, Target: "/data/",
	}))
	if err != nil {
		t.Fatalf("mountsFor(/data/): %v", err)
	}
	if mounts[0].Target != "/data" {
		t.Errorf("target = %q, want it normalised to /data", mounts[0].Target)
	}
}

func TestTwoMountsCannotLandOnOneTarget(t *testing.T) {
	docker := newDocker(t, readyEngine(), newHost())

	_, err := docker.mountsFor(t.Context(), withMounts(
		spec.Mount{VolumeID: "7", Kind: spec.MountKindVolume, Target: "/data"},
		spec.Mount{VolumeID: "8", Kind: spec.MountKindVolume, Target: "/data"},
	))
	if err == nil {
		t.Fatal("two mounts were accepted at one target, where the second silently hides the first")
	}
}

func TestAVolumeIsWritableByWhateverUserTheImageRunsAs(t *testing.T) {
	directory := filepath.Join(t.TempDir(), "volumes", "42", "7")
	if err := ensureVolume(directory); err != nil {
		t.Fatalf("ensureVolume: %v", err)
	}
	info, err := os.Stat(directory)
	if err != nil {
		t.Fatalf("stat: %v", err)
	}
	// Windows does not carry a POSIX mode, so the assertion is only meaningful where the
	// node actually runs. What is checked everywhere is that the directory exists and
	// that calling twice is not an error.
	if info.Mode().Perm()&0o200 == 0 {
		t.Errorf("mode = %v, want the mount root writable", info.Mode().Perm())
	}
	if err := ensureVolume(directory); err != nil {
		t.Fatalf("ensureVolume is not idempotent: %v", err)
	}
}

func TestAFileWhereAVolumeShouldBeIsRefused(t *testing.T) {
	directory := t.TempDir()
	blocked := filepath.Join(directory, "volume")
	if err := os.WriteFile(blocked, []byte("not a directory"), 0o600); err != nil {
		t.Fatalf("write: %v", err)
	}
	if err := ensureVolume(blocked); err == nil {
		t.Fatal("a regular file was accepted as a volume, which would mount an empty file " +
			"over a customer's data directory")
	}
}
