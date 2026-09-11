package dbengine

import (
	"strings"
	"testing"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
)

// Which server a grant lives on, on a node running more than one of a kind.
//
// The wire does not say (placement.go), so these tests pin the rule that stands in for the
// field that is missing. The one that matters most is the last: a dedicated grant with nowhere
// to go is refused rather than placed somewhere plausible, because two customers who each paid
// for their own server ending up on one is not something the panel can detect afterwards.

func instanceOf(t *testing.T, kind spec.EngineKind, id string, port uint32) instance {
	t.Helper()
	built, err := instanceFor(`/state`, spec.Engine{
		Kind:          kind,
		Image:         "image:1",
		ListenPort:    port,
		AdminUsername: "admin_user",
		AdminPassword: "password",
		DataVolumeID:  id,
	})
	if err != nil {
		t.Fatalf("resolve the instance %s: %v", id, err)
	}
	return built
}

func ready(built instance, databases ...databaseFact) observed {
	return observed{Instance: built, ContainerID: "container-" + built.ID, Ready: true, Databases: databases}
}

func TestOneServerOfAKindTakesEverything(t *testing.T) {
	shared := instanceOf(t, spec.EnginePostgres, "shared", 5432)
	servers := map[string]observed{"shared": ready(shared)}

	desired := spec.Spec{Grants: []spec.Grant{
		{ID: "a", Engine: spec.EnginePostgres, DatabaseName: "one", Username: "one_user"},
		{ID: "b", Engine: spec.EnginePostgres, DatabaseName: "two", Username: "two_user", Dedicated: true},
	}}

	placed := place(desired, []instance{shared}, servers)
	for _, id := range []string{"a", "b"} {
		if home, refusal := placed.homeOf(id); home != "shared" {
			t.Fatalf("grant %s went to %q (%s)", id, home, refusal)
		}
	}
}

func TestAGrantGoesWhereItsDatabaseAlreadyIs(t *testing.T) {
	shared := instanceOf(t, spec.EnginePostgres, "shared", 5432)
	private := instanceOf(t, spec.EnginePostgres, "private", 5433)
	servers := map[string]observed{
		"shared":  ready(shared),
		"private": ready(private, databaseFact{Name: "one", Owner: "one_user"}),
	}

	// The grant is not marked dedicated, so the rule would send it to the shared server - but
	// its database is observably on the private one, and observed fact wins.
	desired := spec.Spec{Grants: []spec.Grant{
		{ID: "a", Engine: spec.EnginePostgres, DatabaseName: "one", Username: "one_user"},
	}}

	placed := place(desired, []instance{shared, private}, servers)
	if home, refusal := placed.homeOf("a"); home != "private" {
		t.Fatalf("the grant went to %q (%s) rather than to the server that already holds it",
			home, refusal)
	}
}

func TestADedicatedGrantTakesAnEmptyServer(t *testing.T) {
	shared := instanceOf(t, spec.EnginePostgres, "shared", 5432)
	first := instanceOf(t, spec.EnginePostgres, "private-a", 5433)
	second := instanceOf(t, spec.EnginePostgres, "private-b", 5434)
	servers := map[string]observed{
		"shared":    ready(shared),
		"private-a": ready(first),
		"private-b": ready(second),
	}

	desired := spec.Spec{Grants: []spec.Grant{
		{ID: "shared-one", Engine: spec.EnginePostgres, DatabaseName: "one", Username: "one_user"},
		{ID: "paid-a", Engine: spec.EnginePostgres, DatabaseName: "two", Username: "two_user", Dedicated: true},
		{ID: "paid-b", Engine: spec.EnginePostgres, DatabaseName: "three", Username: "three_user", Dedicated: true},
	}}

	placed := place(desired, []instance{shared, first, second}, servers)

	if home, _ := placed.homeOf("shared-one"); home != "shared" {
		t.Fatalf("the ordinary grant went to %q", home)
	}
	one, _ := placed.homeOf("paid-a")
	two, _ := placed.homeOf("paid-b")
	if one == "shared" || two == "shared" {
		t.Fatalf("a dedicated grant was placed on the shared server: %s, %s", one, two)
	}
	if one == two {
		t.Fatalf("two customers who each paid for their own server were both placed on %s", one)
	}
}

func TestADedicatedGrantWithNowhereToGoIsRefused(t *testing.T) {
	shared := instanceOf(t, spec.EnginePostgres, "shared", 5432)
	private := instanceOf(t, spec.EnginePostgres, "private", 5433)
	servers := map[string]observed{
		"shared":  ready(shared),
		"private": ready(private, databaseFact{Name: "somebody_else", Owner: "somebody_else_user"}),
	}

	desired := spec.Spec{Grants: []spec.Grant{
		{ID: "paid", Engine: spec.EnginePostgres, DatabaseName: "two", Username: "two_user", Dedicated: true},
	}}

	placed := place(desired, []instance{shared, private}, servers)
	home, refusal := placed.homeOf("paid")
	if home != "" {
		t.Fatalf("a dedicated grant with no free server was placed on %q", home)
	}
	if !strings.Contains(refusal, "no free one") {
		t.Fatalf("the refusal does not explain itself: %q", refusal)
	}
}

func TestADedicatedGrantIsNotPlacedOnAServerNobodyCouldLookAt(t *testing.T) {
	// A server that could not be asked what it holds is not an empty server. Treating the two
	// the same is how a customer's dedicated instance gets a second customer on it.
	shared := instanceOf(t, spec.EnginePostgres, "shared", 5432)
	private := instanceOf(t, spec.EnginePostgres, "private", 5433)
	servers := map[string]observed{
		"shared":  ready(shared),
		"private": {Instance: private, ContainerID: "container-private", Detail: "not answering"},
	}

	desired := spec.Spec{Grants: []spec.Grant{
		{ID: "paid", Engine: spec.EnginePostgres, DatabaseName: "two", Username: "two_user", Dedicated: true},
	}}

	placed := place(desired, []instance{shared, private}, servers)
	if home, _ := placed.homeOf("paid"); home != "" {
		t.Fatalf("a dedicated grant was placed on %q, which nobody could look at", home)
	}
}

func TestAGrantForAnEngineThisNodeDoesNotRunIsRefused(t *testing.T) {
	shared := instanceOf(t, spec.EnginePostgres, "shared", 5432)
	servers := map[string]observed{"shared": ready(shared)}

	desired := spec.Spec{Grants: []spec.Grant{
		{ID: "a", Engine: spec.EngineMySQL, DatabaseName: "one", Username: "one_user"},
	}}

	placed := place(desired, []instance{shared}, servers)
	home, refusal := placed.homeOf("a")
	if home != "" {
		t.Fatalf("a MySQL grant was placed on %q", home)
	}
	if !strings.Contains(refusal, "no MYSQL server") {
		t.Fatalf("the refusal does not explain itself: %q", refusal)
	}
}

func TestAnUnknownEngineCreatesNothing(t *testing.T) {
	// A panel that has been upgraded first can send an engine this binary does not know. It
	// must produce nothing rather than a container started with the wrong client.
	_, err := instanceFor(`/state`, spec.Engine{
		Kind:          spec.EngineUnknown,
		Image:         "something:1",
		AdminUsername: "admin_user",
		AdminPassword: "password",
		DataVolumeID:  "unknown-1",
	})
	if err == nil {
		t.Fatal("an engine kind this binary does not know was resolved into a container")
	}
	if !strings.Contains(err.Error(), "does not know the database engine") {
		t.Fatalf("the refusal does not explain itself: %v", err)
	}
}
