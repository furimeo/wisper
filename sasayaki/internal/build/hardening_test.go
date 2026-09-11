package build

import (
	"context"
	"reflect"
	"strings"
	"testing"

	"github.com/moby/moby/api/types/container"
	"github.com/moby/moby/client"

	"github.com/furimeo/wisper/sasayaki/internal/reconcile"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// A build is not a lesser workload: it runs somebody else's install scripts with a network
// connection. These tests say so in a form that fails when somebody relaxes it.

func TestBuildResourcesSetTheCeilingAndNotTheWeight(t *testing.T) {
	resources := buildResources(&wisperpb.ResourceLimits{
		NanoCpus:    1_500_000_000,
		MemoryBytes: 512 << 20,
		PidsLimit:   256,
		NofileLimit: 8192,
	})

	if resources.NanoCPUs != 1_500_000_000 {
		t.Fatalf("the nano-CPU ceiling is %d", resources.NanoCPUs)
	}
	if resources.CPUShares != 0 {
		t.Fatal("CPUShares was set. It is a relative scheduling weight, not a ceiling, and " +
			"setting it where a limit was intended is the predecessor's defining bug")
	}
	if resources.Memory != 512<<20 {
		t.Fatalf("the memory ceiling is %d", resources.Memory)
	}
	if resources.MemorySwap != 512<<20 {
		t.Fatalf("swap was not disabled: %d", resources.MemorySwap)
	}
	if resources.PidsLimit == nil || *resources.PidsLimit != 256 {
		t.Fatalf("the pids ceiling is %v", resources.PidsLimit)
	}
	if len(resources.Ulimits) != 1 || resources.Ulimits[0].Name != "nofile" ||
		resources.Ulimits[0].Soft != 8192 || resources.Ulimits[0].Hard != 8192 {
		t.Fatalf("the file-descriptor ceiling is %v", resources.Ulimits)
	}
}

func TestBuildResourcesLeaveSwapAloneWithoutAMemoryLimit(t *testing.T) {
	// The engine rejects a swap figure with no memory limit beside it.
	resources := buildResources(&wisperpb.ResourceLimits{MemorySwapBytes: 1 << 30})
	if resources.MemorySwap != 0 {
		t.Fatalf("swap was set to %d with no memory limit", resources.MemorySwap)
	}
}

func TestHardenBuildDropsEverythingItCan(t *testing.T) {
	host := &container.HostConfig{}
	hardenBuild(host, "runsc")

	if host.Runtime != "runsc" {
		t.Fatalf("the runtime is %q", host.Runtime)
	}
	if host.Privileged {
		t.Fatal("a build container was made privileged")
	}
	if !reflect.DeepEqual(host.CapDrop, []string{"ALL"}) {
		t.Fatalf("capabilities were not dropped: %v", host.CapDrop)
	}
	for _, forbidden := range []string{"NET_RAW", "SYS_ADMIN", "SYS_CHROOT", "MKNOD", "SETUID", "SETGID"} {
		if contains(host.CapAdd, forbidden) {
			t.Fatalf("%s was added back to a build container", forbidden)
		}
	}
	if !contains(host.SecurityOpt, "no-new-privileges:true") {
		t.Fatalf("no-new-privileges is missing: %v", host.SecurityOpt)
	}
	for _, option := range host.SecurityOpt {
		if strings.Contains(option, "seccomp=unconfined") || strings.Contains(option, "apparmor=unconfined") {
			t.Fatalf("a build container was unconfined: %q", option)
		}
	}
	if host.CgroupnsMode != container.CgroupnsModePrivate || host.IpcMode != container.IPCModePrivate {
		t.Fatal("the build container shares a namespace it does not need")
	}
	if host.AutoRemove {
		t.Fatal("AutoRemove is on, so the exit code the whole build depends on would be gone")
	}
	if host.RestartPolicy.Name != container.RestartPolicyDisabled {
		t.Fatalf("a build container may be restarted: %q", host.RestartPolicy.Name)
	}
}

// The reconcile loop lists containers carrying both wisper.managed and wisper.workload. A
// build carries only the first, so a deployment in flight cannot be removed as an orphan.
func TestBuildContainersAreInvisibleToTheReconcileLoop(t *testing.T) {
	labels := buildLabels("77")

	if labels[reconcile.LabelManaged] != reconcile.LabelManagedValue {
		t.Fatalf("a build container is not marked as this daemon's: %v", labels)
	}
	if _, present := labels[reconcile.LabelWorkload]; present {
		t.Fatal("a build container carries the workload label, so the reconcile loop would " +
			"see it as an orphan and remove it mid-deployment")
	}
	if labels[labelBuild] != "77" {
		t.Fatalf("the build id is not on the container: %v", labels)
	}
}

func TestBuildRuntimeIsGVisorWhenTheNodeHasIt(t *testing.T) {
	h := newHarness(t)
	h.Engine.Runsc = true

	if got := h.Builder.buildRuntime(context.Background()); got != "runsc" {
		t.Fatalf("a node with gVisor built under %q", got)
	}
}

func TestBuildRuntimeFallsBackToRuncAndSaysSo(t *testing.T) {
	h := newHarness(t)
	h.Engine.Runsc = false

	if got := h.Builder.buildRuntime(context.Background()); got != "runc" {
		t.Fatalf("a node without gVisor built under %q", got)
	}

	dev := newHarness(t)
	dev.Engine.Runsc = true
	builder, err := New(Options{
		StateDir: dev.StateDir,
		Engine:   dev.Engine,
		Store:    dev.Store,
		Uploads:  dev.Uploads,
		Logs:     dev.Sink,
		Releases: dev.Releases,
		Logger:   testLogger(),
		Dev:      true,
	})
	if err != nil {
		t.Fatalf("open a developer's builder: %v", err)
	}
	if got := builder.buildRuntime(context.Background()); got != "runc" {
		t.Fatalf("--dev built under %q rather than runc", got)
	}
}

func TestBuildEnvironmentCarriesEveryNamedVariable(t *testing.T) {
	environment := buildEnvironment([]*wisperpb.EnvVar{
		{Name: "NODE_ENV", Value: "production"},
		{Name: "API_KEY", Value: "secret", Secret: true},
		{Name: "", Value: "nameless"},
	})

	if len(environment) != 2 {
		t.Fatalf("expected the two named variables, got %v", environment)
	}
	if !contains(environment, "NODE_ENV=production") || !contains(environment, "API_KEY=secret") {
		t.Fatalf("a variable did not reach the build: %v", environment)
	}
}

func TestTheWorkspaceIsTheOnlyThingBoundIntoABuild(t *testing.T) {
	h := newHarness(t)
	path, digest := zipOnDisk(t, map[string]string{"dist/index.html": "x"})
	h.Uploads.files["session-1"] = path
	h.Engine.Present["alpine:3"] = true
	h.Engine.Script = func(client.ContainerCreateOptions) fakeRun { return fakeRun{} }

	request := archiveBuild("session-1", digest)
	request.Plan = &wisperpb.BuildPlan{
		BuilderImage:    "alpine:3",
		BuildCommand:    []string{"true"},
		OutputDirectory: "dist",
	}
	if _, err := h.Builder.Build(context.Background(), request); err != nil {
		t.Fatalf("build: %v", err)
	}

	created := h.Engine.createdNamed(t, "-build")
	if len(created.HostConfig.Binds) != 1 {
		t.Fatalf("a build container sees more than its workspace: %v", created.HostConfig.Binds)
	}
	host, target, _ := cutBind(created.HostConfig.Binds[0])
	if target != workMount {
		t.Fatalf("the workspace is mounted at %q", target)
	}
	if !insideTree(h.StateDir, host) {
		t.Fatalf("a build container was given %q, which is outside the state directory", host)
	}
	if len(created.HostConfig.Mounts) != 0 {
		t.Fatalf("an extra mount reached a build container: %v", created.HostConfig.Mounts)
	}
}
