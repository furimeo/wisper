package runtime

import (
	"context"
	"log/slog"
	"slices"
	"strings"
	"testing"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
)

// hasCapability accepts a capability under either spelling. The engine's client upper-
// cases and prefixes with CAP_ on the way out, so a test that pinned one form would be
// asserting on the library rather than on this package.
func hasCapability(list []string, want string) bool {
	return slices.Contains(list, want) || slices.Contains(list, "CAP_"+want)
}

func TestCreateHardensEveryContainer(t *testing.T) {
	api := readyEngine()
	docker := newDocker(t, api, newHost())

	if _, err := docker.Create(context.Background(), appWorkload(), "wf1:abc"); err != nil {
		t.Fatalf("Create: %v", err)
	}
	host := api.lastCreate(t).HostConfig

	// The engine's own client normalises these to a CAP_ prefix on the way out, so both
	// spellings are accepted here and neither is what the assertion is about.
	if !hasCapability(host.CapDrop, "ALL") {
		t.Errorf("CapDrop = %v, want ALL", host.CapDrop)
	}
	for _, banned := range []string{"NET_RAW", "SYS_ADMIN", "SYS_CHROOT", "MKNOD", "SETFCAP", "AUDIT_WRITE"} {
		if hasCapability(host.CapAdd, banned) {
			t.Errorf("CapAdd contains %s, which is one of the ones dropping ALL exists to remove", banned)
		}
	}
	if !hasCapability(host.CapAdd, "NET_BIND_SERVICE") {
		t.Errorf("CapAdd = %v, want NET_BIND_SERVICE so an image can listen on :80 inside "+
			"its own namespace", host.CapAdd)
	}
	if !hasCapability(host.CapAdd, "SETUID") || !hasCapability(host.CapAdd, "CHOWN") {
		t.Errorf("CapAdd = %v, want the set every official image's entrypoint needs to step "+
			"down to a service user", host.CapAdd)
	}
	if !slices.Contains(host.SecurityOpt, "no-new-privileges:true") {
		t.Errorf("SecurityOpt = %v, want no-new-privileges", host.SecurityOpt)
	}
	for _, option := range host.SecurityOpt {
		if strings.Contains(option, "seccomp=unconfined") || strings.Contains(option, "apparmor=unconfined") {
			t.Errorf("SecurityOpt = %v: nothing in wisper turns a filter off", host.SecurityOpt)
		}
	}
	if host.Privileged {
		t.Error("Privileged is set")
	}
	if host.PidMode.IsHost() || host.IpcMode.IsHost() || host.UTSMode.IsHost() || host.UsernsMode.IsHost() {
		t.Errorf("a namespace is shared with the host: pid=%q ipc=%q uts=%q userns=%q",
			host.PidMode, host.IpcMode, host.UTSMode, host.UsernsMode)
	}
	if !host.IpcMode.IsPrivate() {
		t.Errorf("IpcMode = %q, want private", host.IpcMode)
	}
	if !host.CgroupnsMode.IsPrivate() {
		t.Errorf("CgroupnsMode = %q, want private", host.CgroupnsMode)
	}
	if host.Init == nil || !*host.Init {
		t.Error("Init is not set on an engine that reports an init binary")
	}
	if host.Runtime != "runsc" {
		t.Errorf("Runtime = %q, want runsc on an engine that has it", host.Runtime)
	}
}

func TestCreateNeverAsksForInitWhenTheEngineHasNone(t *testing.T) {
	api := readyEngine()
	api.info.InitBinary = ""
	docker := newDocker(t, api, newHost())

	if _, err := docker.Create(context.Background(), appWorkload(), "wf1:abc"); err != nil {
		t.Fatalf("Create: %v", err)
	}
	if host := api.lastCreate(t).HostConfig; host.Init != nil {
		t.Error("asked for an init binary the engine does not have, which fails every container")
	}
}

func TestCreateFallsBackToRuncAndSaysSo(t *testing.T) {
	api := readyEngine().withoutRunsc()
	docker := newDocker(t, api, newHost())

	found, err := docker.Create(context.Background(), appWorkload(), "wf1:abc")
	if err != nil {
		t.Fatalf("Create: %v", err)
	}
	if got := api.lastCreate(t).HostConfig.Runtime; got != "runc" {
		t.Errorf("Runtime = %q, want runc on a node with no gVisor", got)
	}
	if found.Runtime != spec.RuntimeRunc {
		t.Errorf("reported runtime = %q, want runc: a node must not imply isolation it does "+
			"not have", found.Runtime)
	}

	isolation, err := docker.Isolation(context.Background())
	if err != nil {
		t.Fatalf("Isolation: %v", err)
	}
	if isolation.Runsc {
		t.Error("Isolation says runsc on a node whose engine does not offer it")
	}
}

func TestDevModeForcesRuncEvenWhereGvisorIsAvailable(t *testing.T) {
	api := readyEngine()
	docker, err := New(context.Background(), t.TempDir(), withEngine(api),
		WithCommandRunner(newHost().run), WithLogger(slog.New(slog.DiscardHandler)), WithDevMode(true))
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	defer docker.Close()

	if _, err := docker.Create(context.Background(), appWorkload(), "wf1:abc"); err != nil {
		t.Fatalf("Create: %v", err)
	}
	if got := api.lastCreate(t).HostConfig.Runtime; got != "runc" {
		t.Errorf("Runtime = %q, want runc under --dev", got)
	}
}
