package spec

import "testing"

func lookupFixture() Spec {
	return Spec{
		Workloads: []Workload{
			{ID: "app", Kind: KindApp},
			{ID: "site", Kind: KindSite},
		},
		Routes: []Route{
			{Domain: "acme.example", WorkloadID: "app"},
			{Domain: "www.acme.example", WorkloadID: "app"},
			{Domain: "docs.acme.example", WorkloadID: "site"},
		},
		Engines: []Engine{
			{Kind: EnginePostgres, ListenPort: 15432},
			{Kind: EngineMySQL, ListenPort: 13306},
		},
		Grants: []Grant{
			{ID: "g1", Engine: EnginePostgres, DatabaseName: "acme"},
			{ID: "g2", Engine: EngineMySQL, DatabaseName: "legacy"},
			{ID: "g3", Engine: EnginePostgres, DatabaseName: "reports"},
		},
		Cron: []CronEntry{
			{ID: "c1", WorkloadID: "app"},
			{ID: "c2", WorkloadID: "app"},
		},
		FileRoots: []FileRoot{
			{ID: "upload-staging", Kind: FileRootUploadStaging},
			{ID: "vol-1", Kind: FileRootVolume},
		},
	}
}

func TestWorkloadLookup(t *testing.T) {
	spec := lookupFixture()

	workload, found := spec.Workload("site")
	if !found || workload.Kind != KindSite {
		t.Errorf("Workload(site) = %+v, %v", workload, found)
	}
	if _, found := spec.Workload("gone"); found {
		t.Error("a workload that is not in the spec must not be found: omission is deletion")
	}
}

func TestWorkloadIDsKeepsSpecOrder(t *testing.T) {
	ids := lookupFixture().WorkloadIDs()

	if len(ids) != 2 || ids[0] != "app" || ids[1] != "site" {
		t.Errorf("WorkloadIDs() = %v, want [app site]", ids)
	}
}

// An apex and a www alias is the ordinary case, not the exception.
func TestRoutesForReturnsEveryHostname(t *testing.T) {
	routes := lookupFixture().RoutesFor("app")

	if len(routes) != 2 {
		t.Fatalf("RoutesFor(app) = %v, want two", routes)
	}
	if routes[0].Domain != "acme.example" || routes[1].Domain != "www.acme.example" {
		t.Errorf("RoutesFor(app) = %v, want spec order", routes)
	}
	if got := lookupFixture().RoutesFor("nothing"); len(got) != 0 {
		t.Errorf("RoutesFor(nothing) = %v, want empty", got)
	}
}

// The first call in every file operation. A miss is a refusal, never a fallback to some
// default root - resolving an unknown root by guessing is the first half of a traversal.
func TestFileRootLookup(t *testing.T) {
	spec := lookupFixture()

	root, found := spec.FileRoot("vol-1")
	if !found || root.Kind != FileRootVolume {
		t.Errorf("FileRoot(vol-1) = %+v, %v", root, found)
	}
	missing, found := spec.FileRoot("../../etc")
	if found {
		t.Error("an unknown root must not resolve")
	}
	if missing.ID != "" || missing.Writable {
		t.Errorf("a missed lookup returned %+v, want the zero value", missing)
	}
}

func TestCronForOneWorkload(t *testing.T) {
	entries := lookupFixture().CronFor("app")

	if len(entries) != 2 {
		t.Errorf("CronFor(app) = %v, want two", entries)
	}
	if got := lookupFixture().CronFor("site"); len(got) != 0 {
		t.Errorf("CronFor(site) = %v, want empty: a site has no process", got)
	}
}

func TestEngineAndGrantLookup(t *testing.T) {
	spec := lookupFixture()

	engine, found := spec.Engine(EnginePostgres)
	if !found || engine.ListenPort != 15432 {
		t.Errorf("Engine(POSTGRES) = %+v, %v", engine, found)
	}
	if _, found := spec.Engine(EngineUnknown); found {
		t.Error("an unknown engine kind must not match one that is present")
	}

	grants := spec.GrantsFor(EnginePostgres)
	if len(grants) != 2 || grants[0].ID != "g1" || grants[1].ID != "g3" {
		t.Errorf("GrantsFor(POSTGRES) = %v, want g1 and g3 in spec order", grants)
	}

	grant, found := spec.Grant("g2")
	if !found || grant.DatabaseName != "legacy" {
		t.Errorf("Grant(g2) = %+v, %v", grant, found)
	}
	if _, found := spec.Grant("g9"); found {
		t.Error("a grant left out of the spec is one the node should drop, not find")
	}
}

// Every lookup has to answer on the document a freshly enrolled node holds.
func TestLookupsOnAnEmptySpec(t *testing.T) {
	var spec Spec

	if _, found := spec.Workload("a"); found {
		t.Error("Workload")
	}
	if got := spec.WorkloadIDs(); len(got) != 0 {
		t.Errorf("WorkloadIDs() = %v", got)
	}
	if got := spec.RoutesFor("a"); len(got) != 0 {
		t.Errorf("RoutesFor() = %v", got)
	}
	if _, found := spec.FileRoot("a"); found {
		t.Error("FileRoot")
	}
	if got := spec.CronFor("a"); len(got) != 0 {
		t.Errorf("CronFor() = %v", got)
	}
	if _, found := spec.Engine(EnginePostgres); found {
		t.Error("Engine")
	}
	if got := spec.GrantsFor(EnginePostgres); len(got) != 0 {
		t.Errorf("GrantsFor() = %v", got)
	}
	if _, found := spec.Grant("a"); found {
		t.Error("Grant")
	}
}
