package daemon

import (
	"context"
	"testing"

	"google.golang.org/protobuf/types/known/timestamppb"

	"github.com/furimeo/wisper/sasayaki/internal/bootstrap"
	"github.com/furimeo/wisper/sasayaki/internal/runtime"
	"github.com/furimeo/wisper/sasayaki/internal/stats"
	"github.com/furimeo/wisper/sasayaki/internal/version"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Describing the machine, and the three places where the preflight probe and the running
// daemon can honestly disagree.
//
// The one that matters most is runsc. The probe asks the engine whether a runtime called
// runsc is registered; the daemon knows whether a container created right now would
// actually get it, which is false under --dev whatever the engine has. A panel shown the
// first answer believes a developer's node provides gVisor, which is the exact failure
// gVisor was adopted to prevent.

type fakeGenerations struct {
	generation uint64
	err        error
}

func (f fakeGenerations) AppliedGeneration(context.Context) (uint64, error) {
	return f.generation, f.err
}

type fakeContainment struct {
	isolation runtime.Isolation
	err       error
	quota     bool
}

func (f fakeContainment) Isolation(context.Context) (runtime.Isolation, error) {
	return f.isolation, f.err
}

func (f fakeContainment) QuotaEnforceable() bool { return f.quota }

func TestHelloCorrectsTheDoctorWithWhatTheDaemonKnows(t *testing.T) {
	cases := []struct {
		name string
		// what the preflight probe found
		probed *wisperpb.MachineFacts
		// what the running engine says
		engine fakeContainment

		runsc     bool
		cgroupsV2 bool
		quota     bool
		memory    int64
		diskFree  int64
		cpuCores  int32
	}{
		{
			name: "a node with gVisor reports gVisor",
			probed: &wisperpb.MachineFacts{
				RunscAvailable: true, CgroupsV2: true, ProjectQuotaSupported: true,
			},
			engine: fakeContainment{
				isolation: runtime.Isolation{Runsc: true, CgroupVersion: "2"},
				quota:     true,
			},
			runsc: true, cgroupsV2: true, quota: true,
			memory: 8 << 30, diskFree: 60 << 30, cpuCores: 4,
		},
		{
			name: "a --dev node has runsc registered and will not use it, and says so",
			probed: &wisperpb.MachineFacts{
				RunscAvailable: true, CgroupsV2: true, ProjectQuotaSupported: false,
			},
			engine: fakeContainment{
				isolation: runtime.Isolation{Runsc: false, CgroupVersion: "2"},
				quota:     false,
			},
			runsc: false, cgroupsV2: true, quota: false,
			memory: 8 << 30, diskFree: 60 << 30, cpuCores: 4,
		},
		{
			name: "an engine that did not answer leaves the probe's own findings alone",
			probed: &wisperpb.MachineFacts{
				RunscAvailable: true, CgroupsV2: true,
			},
			engine: fakeContainment{err: errEngineDown, quota: true},
			// Not being able to ask is not a fact about this machine's isolation, and
			// reporting "no runsc" because of a socket timeout would have the panel mark a
			// perfectly well isolated node as unsafe.
			runsc: true, cgroupsV2: true, quota: true,
			memory: 8 << 30, diskFree: 60 << 30, cpuCores: 4,
		},
	}

	for _, test := range cases {
		t.Run(test.name, func(t *testing.T) {
			describe := nodeDescription{
				generations: fakeGenerations{generation: 47},
				engine:      test.engine,
				capacity: fakeSampler{snapshot: capacitySnapshot(&wisperpb.Capacity{
					NanoCpusTotal:    4 * nanoCPUsPerCore,
					MemoryBytesTotal: 8 << 30,
					DiskBytesTotal:   100 << 30,
					DiskBytesUsed:    40 << 30,
				})},
				log:       discardLogger(),
				stateDir:  "/var/lib/wisper",
				preflight: staticReport(test.probed),
			}

			hello, err := describe.Hello(context.Background())
			if err != nil {
				t.Fatalf("describe this node: %v", err)
			}

			machine := hello.GetMachine()
			if machine.GetRunscAvailable() != test.runsc {
				t.Errorf("runsc_available = %t, want %t", machine.GetRunscAvailable(), test.runsc)
			}
			if machine.GetCgroupsV2() != test.cgroupsV2 {
				t.Errorf("cgroups_v2 = %t, want %t", machine.GetCgroupsV2(), test.cgroupsV2)
			}
			if machine.GetProjectQuotaSupported() != test.quota {
				t.Errorf("project_quota_supported = %t, want %t",
					machine.GetProjectQuotaSupported(), test.quota)
			}
			if machine.GetMemoryBytes() != test.memory {
				t.Errorf("memory_bytes = %d, want %d", machine.GetMemoryBytes(), test.memory)
			}
			if machine.GetDiskFreeBytes() != test.diskFree {
				t.Errorf("disk_free_bytes = %d, want %d", machine.GetDiskFreeBytes(), test.diskFree)
			}
			if machine.GetCpuCores() != test.cpuCores {
				t.Errorf("cpu_cores = %d, want %d", machine.GetCpuCores(), test.cpuCores)
			}
		})
	}
}

// The handshake carries what this build is and what it has converged to, and it leaves the
// two fields that belong to the connection for rpc to stamp - so no implementation of
// HelloSource can get the protocol version wrong.
func TestHelloCarriesTheVersionAndTheAppliedGeneration(t *testing.T) {
	describe := nodeDescription{
		generations: fakeGenerations{generation: 47},
		engine:      fakeContainment{isolation: runtime.Isolation{CgroupVersion: "2"}},
		capacity:    fakeSampler{snapshot: capacitySnapshot(&wisperpb.Capacity{})},
		log:         discardLogger(),
		stateDir:    "/var/lib/wisper",
		preflight:   staticReport(&wisperpb.MachineFacts{}),
	}

	hello, err := describe.Hello(context.Background())
	if err != nil {
		t.Fatalf("describe this node: %v", err)
	}

	if hello.GetAgentVersion() != version.Number {
		t.Errorf("agent_version = %q, want %q", hello.GetAgentVersion(), version.Number)
	}
	if hello.GetAppliedGeneration() != 47 {
		t.Errorf("applied_generation = %d, want 47", hello.GetAppliedGeneration())
	}
	if hello.GetDoctor() == nil {
		t.Error("no doctor report: the panel learns that runsc was removed from this")
	}
	if hello.GetProtocolVersion() != 0 || hello.GetFreshStart() {
		t.Error("the handshake filled in fields that belong to the connection rather than to " +
			"the node; rpc stamps those")
	}
}

// A state database that will not answer must not cost the node its management plane. Zero
// makes the panel resend the whole spec, which it was going to do on this reconnect anyway.
func TestHelloConnectsEvenWhenTheGenerationCannotBeRead(t *testing.T) {
	describe := nodeDescription{
		generations: fakeGenerations{err: errEngineDown},
		engine:      fakeContainment{isolation: runtime.Isolation{CgroupVersion: "2"}},
		capacity:    fakeSampler{snapshot: capacitySnapshot(&wisperpb.Capacity{})},
		log:         discardLogger(),
		stateDir:    "/var/lib/wisper",
		preflight:   staticReport(&wisperpb.MachineFacts{}),
	}

	hello, err := describe.Hello(context.Background())
	if err != nil {
		t.Fatalf("a node that could not read one row refused to connect: %v", err)
	}
	if hello.GetAppliedGeneration() != 0 {
		t.Errorf("applied_generation = %d, want 0", hello.GetAppliedGeneration())
	}
}

func capacitySnapshot(capacity *wisperpb.Capacity) stats.Snapshot {
	return stats.Snapshot{At: noon, Capacity: capacity}
}

// staticReport stands in for bootstrap.Preflight, which a unit test cannot run: half its
// checks bind ports and the other half read /proc.
func staticReport(facts *wisperpb.MachineFacts) func(context.Context, bootstrap.PreflightOptions) *wisperpb.DoctorReport {
	return func(context.Context, bootstrap.PreflightOptions) *wisperpb.DoctorReport {
		return &wisperpb.DoctorReport{
			Machine:              facts,
			TakenAt:              timestamppb.New(noon),
			RequiredChecksPassed: true,
		}
	}
}
