package spec

import (
	"testing"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

func TestEngineFromProto(t *testing.T) {
	engine := engineFromProto(&wisperpb.DatabaseEngineSpec{
		Engine:         wisperpb.DatabaseEngine_DATABASE_ENGINE_POSTGRES,
		Image:          "postgres:17-alpine",
		ListenPort:     15432,
		AdminUsername:  "wisper",
		AdminPassword:  "generated-by-the-panel",
		DataVolumeId:   "engine-1",
		NanoCpus:       2_000_000_000,
		MemoryBytes:    2 << 30,
		MaxConnections: 200,
	})

	if engine.Kind != EnginePostgres {
		t.Errorf("kind = %q, want POSTGRES", engine.Kind)
	}
	if engine.DataVolumeID != "engine-1" {
		t.Errorf("dataVolumeID = %q: it is an id the node resolves, never a path",
			engine.DataVolumeID)
	}
	if engine.NanoCPUs != 2_000_000_000 {
		t.Errorf("nanoCPUs = %d, want two cores", engine.NanoCPUs)
	}
	if engine.MaxConnections != 200 {
		t.Errorf("maxConnections = %d, want 200", engine.MaxConnections)
	}
}

func TestGrantFromProto(t *testing.T) {
	grant := grantFromProto(&wisperpb.DatabaseGrant{
		Id:                "grant-1",
		Engine:            wisperpb.DatabaseEngine_DATABASE_ENGINE_MYSQL,
		DatabaseName:      "acme",
		Username:          "acme_app",
		QuotaBytes:        1 << 30,
		DedicatedInstance: true,
		Encoding:          "utf8mb4",
	})

	if grant.Engine != EngineMySQL {
		t.Errorf("engine = %q, want MYSQL", grant.Engine)
	}
	if !grant.Dedicated {
		t.Error("the dedicated-instance flag did not survive")
	}
	if grant.Encoding != "utf8mb4" {
		t.Errorf("encoding = %q, want utf8mb4", grant.Encoding)
	}
}

// A grant carries no password. It reaches the node once, in a provision or a rotation
// command, so that a secret is not on the wire on every generation.
func TestGrantCarriesNoPassword(t *testing.T) {
	message := &wisperpb.DatabaseGrant{Id: "grant-1"}
	// The compiler is the assertion: if a password field ever appears on DatabaseGrant this
	// test is where somebody has to decide whether the daemon should read it.
	if message.ProtoReflect().Descriptor().Fields().ByName("password") != nil {
		t.Fatal("DatabaseGrant grew a password field; node-spec.md section 3.8 says it must not")
	}
}

// An engine this binary does not implement must be named, not defaulted: a container
// started with the wrong client produces a database no dump can be restored into.
func TestUnknownEngineIsNamed(t *testing.T) {
	if got := engineKindFromProto(wisperpb.DatabaseEngine_DATABASE_ENGINE_UNSPECIFIED); got != EngineUnknown {
		t.Errorf("engine = %q, want UNKNOWN", got)
	}
	if got := engineKindFromProto(wisperpb.DatabaseEngine(77)); got != EngineUnknown {
		t.Errorf("engine = %q, want UNKNOWN", got)
	}
}

func TestEngineKindRoundTrips(t *testing.T) {
	for _, kind := range []EngineKind{EnginePostgres, EngineMySQL, EngineUnknown} {
		if got := engineKindFromProto(engineKindToProto(kind)); got != kind {
			t.Errorf("%q round-tripped to %q", kind, got)
		}
	}
}

func TestDatabaseFromProtoAcceptsNil(t *testing.T) {
	if got := engineFromProto(nil); got.Kind != EngineUnknown || got.Image != "" {
		t.Errorf("a nil engine gave %+v", got)
	}
	if got := grantFromProto(nil); got.ID != "" || got.Engine != EngineUnknown {
		t.Errorf("a nil grant gave %+v", got)
	}
	if engineFromProto(nil) != engineFromProto(&wisperpb.DatabaseEngineSpec{}) {
		t.Error("nil and an empty message must read the same")
	}
}
