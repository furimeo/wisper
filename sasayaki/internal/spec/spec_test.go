package spec

import (
	"testing"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
	"google.golang.org/protobuf/types/known/timestamppb"
)

// A spec with one of everything, so a test can assert the whole document was read rather
// than the one part it happens to care about.
func fullSpec() *wisperpb.NodeSpec {
	return &wisperpb.NodeSpec{
		Generation: 47,
		IssuedAt:   timestamppb.New(time.Date(2026, 9, 11, 10, 15, 30, 0, time.UTC)),
		Workloads: []*wisperpb.Workload{{
			Id:           "1e9d",
			Kind:         wisperpb.WorkloadKind_WORKLOAD_KIND_APP,
			Name:         "api",
			Image:        "ghcr.io/acme/api:3",
			DesiredState: wisperpb.DesiredState_DESIRED_STATE_RUNNING,
			Runtime:      wisperpb.ContainerRuntime_CONTAINER_RUNTIME_RUNSC,
		}},
		Routes: []*wisperpb.Route{{
			Domain:     "acme.example",
			WorkloadId: "1e9d",
			Port:       8080,
			TlsMode:    wisperpb.TlsMode_TLS_MODE_ON_DEMAND,
		}},
		Engines: []*wisperpb.DatabaseEngineSpec{{
			Engine:     wisperpb.DatabaseEngine_DATABASE_ENGINE_POSTGRES,
			Image:      "postgres:17-alpine",
			ListenPort: 15432,
		}},
		Databases: []*wisperpb.DatabaseGrant{{
			Id:           "grant-1",
			Engine:       wisperpb.DatabaseEngine_DATABASE_ENGINE_POSTGRES,
			DatabaseName: "acme",
		}},
		Cron: []*wisperpb.CronEntry{{
			Id:         "cron-1",
			WorkloadId: "1e9d",
			Schedule:   "0 3 * * *",
		}},
		FileRoots: []*wisperpb.FileRoot{{
			Id:   "upload-staging",
			Kind: wisperpb.FileRootKind_FILE_ROOT_KIND_UPLOAD_STAGING,
		}},
		Retention: &wisperpb.RetentionPolicy{
			KeepReleases:           5,
			OrphanUploadTtlSeconds: 86400,
		},
		ReconcileIntervalSeconds: 15,
	}
}

func TestFromProtoReadsEveryPart(t *testing.T) {
	spec := FromProto(fullSpec())

	if spec.Generation != 47 {
		t.Errorf("generation = %d, want 47", spec.Generation)
	}
	if want := time.Date(2026, 9, 11, 10, 15, 30, 0, time.UTC); !spec.IssuedAt.Equal(want) {
		t.Errorf("issuedAt = %s, want %s", spec.IssuedAt, want)
	}
	if spec.ReconcileInterval != 15*time.Second {
		t.Errorf("reconcileInterval = %s, want 15s", spec.ReconcileInterval)
	}
	if got := len(spec.Workloads); got != 1 {
		t.Errorf("workloads = %d, want 1", got)
	}
	if got := len(spec.Routes); got != 1 {
		t.Errorf("routes = %d, want 1", got)
	}
	if got := len(spec.Engines); got != 1 {
		t.Errorf("engines = %d, want 1", got)
	}
	if got := len(spec.Grants); got != 1 {
		t.Errorf("grants = %d, want 1", got)
	}
	if got := len(spec.Cron); got != 1 {
		t.Errorf("cron = %d, want 1", got)
	}
	if got := len(spec.FileRoots); got != 1 {
		t.Errorf("fileRoots = %d, want 1", got)
	}
	if spec.Retention.KeepReleases != 5 {
		t.Errorf("keepReleases = %d, want 5", spec.Retention.KeepReleases)
	}
	if spec.Retention.OrphanUploadTTL != 24*time.Hour {
		t.Errorf("orphanUploadTTL = %s, want 24h", spec.Retention.OrphanUploadTTL)
	}
}

// A nil message is what a caller has when the panel has sent nothing yet, and it must not
// be a panic on the reconcile path.
func TestFromProtoAcceptsNil(t *testing.T) {
	spec := FromProto(nil)

	if !spec.IsEmpty() {
		t.Error("a nil message should read as an empty spec")
	}
	if spec.Generation != 0 {
		t.Errorf("generation = %d, want 0", spec.Generation)
	}
	if !spec.IssuedAt.IsZero() {
		t.Errorf("issuedAt = %s, want the zero time", spec.IssuedAt)
	}
	// Every lookup has to answer rather than panic on an empty document.
	if _, found := spec.Workload("anything"); found {
		t.Error("an empty spec should hold no workload")
	}
}

// A drained node is sent a spec with empty lists. It means "run nothing", which is a real
// instruction and not the same as having no spec at all.
func TestEmptySpecIsAnInstruction(t *testing.T) {
	spec := FromProto(&wisperpb.NodeSpec{Generation: 12})

	if !spec.IsEmpty() {
		t.Error("a spec with no entries should be empty")
	}
	if spec.Generation != 12 {
		t.Errorf("generation = %d, want 12: an empty spec still carries one", spec.Generation)
	}
}

func TestIsEmptyIsFalseWhenAnythingIsAskedFor(t *testing.T) {
	cases := map[string]*wisperpb.NodeSpec{
		"a workload":   {Workloads: []*wisperpb.Workload{{Id: "a"}}},
		"a route":      {Routes: []*wisperpb.Route{{Domain: "a.example"}}},
		"an engine":    {Engines: []*wisperpb.DatabaseEngineSpec{{Image: "postgres:17"}}},
		"a grant":      {Databases: []*wisperpb.DatabaseGrant{{Id: "g"}}},
		"a cron entry": {Cron: []*wisperpb.CronEntry{{Id: "c"}}},
		"a file root":  {FileRoots: []*wisperpb.FileRoot{{Id: "r"}}},
	}
	for name, message := range cases {
		t.Run(name, func(t *testing.T) {
			if FromProto(message).IsEmpty() {
				t.Errorf("a spec holding %s is not empty", name)
			}
		})
	}
}

// Slices are copied, not aliased. A consumer that sorts a workload's argv in place must not
// change the protobuf the caller still holds - and internal/state hands out a message it
// decoded from its own bytes.
func TestFromProtoCopiesSlices(t *testing.T) {
	message := &wisperpb.NodeSpec{Workloads: []*wisperpb.Workload{{
		Id:      "1e9d",
		Command: []string{"node", "server.js"},
	}}}

	spec := FromProto(message)
	spec.Workloads[0].Command[0] = "rm"

	if message.GetWorkloads()[0].GetCommand()[0] != "node" {
		t.Error("FromProto aliased the protobuf's argv instead of copying it")
	}
}
