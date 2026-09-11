package dbengine

import (
	"context"
	"strings"
	"testing"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Provisioning: that it works, that it can be repeated, and that it refuses a name that
// already belongs to somebody else.

func TestProvisionCreatesTheDatabaseAndTheLogin(t *testing.T) {
	h := newHarness(t)
	h.publish([]*wisperpb.DatabaseEngineSpec{postgresEngine()}, nil)
	h.converge(t)

	provisioned, err := h.Engines.ProvisionDatabase(context.Background(), &wisperpb.ProvisionDatabase{
		Grant:    grant("db-1", "proj_api", "proj_api_user", wisperpb.DatabaseEngine_DATABASE_ENGINE_POSTGRES),
		Password: "s3cret-'quoted'",
	})
	if err != nil {
		t.Fatalf("provision: %v", err)
	}

	if provisioned.GetHost() != "wisper-db-postgres-"+postgresInstance {
		t.Fatalf("the connection string points at %q, which is not the name the server answers "+
			"to on a tenant network", provisioned.GetHost())
	}
	if provisioned.GetPort() != 5432 {
		t.Fatalf("the connection string names port %d", provisioned.GetPort())
	}
	if provisioned.GetEngineVersion() != "PostgreSQL 17.2" {
		t.Fatalf("the engine version came back as %q", provisioned.GetEngineVersion())
	}

	server := h.Servers.only()
	database, present := server.Databases["proj_api"]
	if !present {
		t.Fatal("the database was not created on the server")
	}
	if database.Owner != "proj_api_user" {
		t.Fatalf("the database is owned by %q", database.Owner)
	}
	login, present := server.Logins["proj_api_user"]
	if !present {
		t.Fatal("the login was not created")
	}
	if login.Locked {
		t.Fatal("the login was left locked, so the customer cannot use the password they were shown")
	}
	if login.Password != "s3cret-'quoted'" {
		t.Fatalf("the password reached the server as %q; the quoting is wrong", login.Password)
	}
}

func TestProvisionRepeatedIsTheSameDatabase(t *testing.T) {
	h := newHarness(t)
	h.publish([]*wisperpb.DatabaseEngineSpec{postgresEngine()}, nil)
	h.converge(t)

	request := &wisperpb.ProvisionDatabase{
		Grant:    grant("db-1", "proj_api", "proj_api_user", wisperpb.DatabaseEngine_DATABASE_ENGINE_POSTGRES),
		Password: "first-password",
	}
	if _, err := h.Engines.ProvisionDatabase(context.Background(), request); err != nil {
		t.Fatalf("first provision: %v", err)
	}
	// The panel resends a command it never saw answered. The second one must succeed and
	// leave one database behind, not fail on a name that is already there.
	if _, err := h.Engines.ProvisionDatabase(context.Background(), request); err != nil {
		t.Fatalf("the repeated provision failed: %v", err)
	}

	if got := len(h.Servers.only().Databases); got != 1 {
		t.Fatalf("two provisions of one grant left %d databases", got)
	}
}

func TestProvisionRefusesANameThatBelongsToSomebodyElse(t *testing.T) {
	h := newHarness(t)
	h.publish([]*wisperpb.DatabaseEngineSpec{postgresEngine()}, nil)
	h.converge(t)

	// Another customer already has this name on the shared server.
	server := h.Servers.only()
	server.Databases["proj_api"] = &fakeDatabase{Owner: "someone_else"}

	_, err := h.Engines.ProvisionDatabase(context.Background(), &wisperpb.ProvisionDatabase{
		Grant:    grant("db-1", "proj_api", "proj_api_user", wisperpb.DatabaseEngine_DATABASE_ENGINE_POSTGRES),
		Password: "password",
	})
	if err == nil {
		t.Fatal("provisioning over another customer's database reported success")
	}
	if !strings.Contains(err.Error(), "different login") {
		t.Fatalf("the refusal does not say why: %v", err)
	}
	if server.Databases["proj_api"].Owner != "someone_else" {
		t.Fatal("the other customer's database was taken over")
	}
	if _, present := server.Logins["proj_api_user"]; present {
		t.Fatal("a login was created for a database this node refused to provision")
	}
}

func TestProvisionRefusesADatabaseNobodyOwns(t *testing.T) {
	h := newHarness(t)
	h.publish([]*wisperpb.DatabaseEngineSpec{mysqlEngine()}, nil)
	h.converge(t)

	// A schema an operator created by hand: it exists and no login has privileges on it.
	h.Servers.only().Databases["legacy_app"] = &fakeDatabase{}

	_, err := h.Engines.ProvisionDatabase(context.Background(), &wisperpb.ProvisionDatabase{
		Grant:    grant("db-9", "legacy_app", "legacy_user", wisperpb.DatabaseEngine_DATABASE_ENGINE_MYSQL),
		Password: "password",
	})
	if err == nil {
		t.Fatal("provisioning over a database this platform did not create reported success")
	}
	if !strings.Contains(err.Error(), "not created by this platform") {
		t.Fatalf("the refusal does not say why: %v", err)
	}
}

func TestProvisionRefusesAGrantWhoseNamesCouldNotHaveComeFromThePanel(t *testing.T) {
	h := newHarness(t)
	h.publish([]*wisperpb.DatabaseEngineSpec{postgresEngine()}, nil)
	h.converge(t)

	attempts := map[string]*wisperpb.DatabaseGrant{
		"a quoted name": grant("db-1", `evil"; DROP DATABASE postgres; --`, "user_one",
			wisperpb.DatabaseEngine_DATABASE_ENGINE_POSTGRES),
		"an upper-case name": grant("db-2", "ProjApi", "user_one",
			wisperpb.DatabaseEngine_DATABASE_ENGINE_POSTGRES),
		"a login with a backtick": grant("db-3", "proj_api", "user`one",
			wisperpb.DatabaseEngine_DATABASE_ENGINE_POSTGRES),
		"a name that is too short": grant("db-4", "ab", "user_one",
			wisperpb.DatabaseEngine_DATABASE_ENGINE_POSTGRES),
	}

	for description, attempt := range attempts {
		_, err := h.Engines.ProvisionDatabase(context.Background(), &wisperpb.ProvisionDatabase{
			Grant: attempt, Password: "password",
		})
		if err == nil {
			t.Fatalf("%s was accepted", description)
		}
	}
	if got := len(h.Servers.only().Databases); got != 0 {
		t.Fatalf("%d databases were created from refused requests", got)
	}
}

func TestProvisionRefusesAPasswordWithALineBreakInIt(t *testing.T) {
	h := newHarness(t)
	h.publish([]*wisperpb.DatabaseEngineSpec{postgresEngine()}, nil)
	h.converge(t)

	_, err := h.Engines.ProvisionDatabase(context.Background(), &wisperpb.ProvisionDatabase{
		Grant:    grant("db-1", "proj_api", "proj_api_user", wisperpb.DatabaseEngine_DATABASE_ENGINE_POSTGRES),
		Password: "pass\nword",
	})
	if err == nil {
		t.Fatal("a password containing a line break was accepted, and in psql a line break is " +
			"where a backslash command would start")
	}
}

func TestProvisionRefusesWhenThisNodeRunsNoServerOfThatKind(t *testing.T) {
	h := newHarness(t)
	h.publish([]*wisperpb.DatabaseEngineSpec{postgresEngine()}, nil)
	h.converge(t)

	_, err := h.Engines.ProvisionDatabase(context.Background(), &wisperpb.ProvisionDatabase{
		Grant:    grant("db-1", "proj_api", "proj_api_user", wisperpb.DatabaseEngine_DATABASE_ENGINE_MYSQL),
		Password: "password",
	})
	if err == nil {
		t.Fatal("a MySQL database was provisioned on a node that runs no MySQL")
	}
	if !strings.Contains(err.Error(), "no MYSQL server") {
		t.Fatalf("the refusal does not say why: %v", err)
	}
}

func TestProvisionCreatesTheServerWhenTheSpecHasOnlyJustArrived(t *testing.T) {
	h := newHarness(t)
	// No convergence has run: the spec arrived a moment ago and the customer is already
	// pressing the button.
	h.publish([]*wisperpb.DatabaseEngineSpec{postgresEngine()}, nil)

	if _, err := h.Engines.ProvisionDatabase(context.Background(), &wisperpb.ProvisionDatabase{
		Grant:    grant("db-1", "proj_api", "proj_api_user", wisperpb.DatabaseEngine_DATABASE_ENGINE_POSTGRES),
		Password: "password",
	}); err != nil {
		t.Fatalf("provision on a node whose server had not been created yet: %v", err)
	}
	if len(h.Docker.Created) != 1 {
		t.Fatal("the server was not created on the way to provisioning")
	}
}

func TestProvisionKeepsThePasswordOutOfAFailureMessage(t *testing.T) {
	h := newHarness(t)
	h.publish([]*wisperpb.DatabaseEngineSpec{postgresEngine()}, nil)
	h.converge(t)

	const password = "correct-horse-battery-staple"
	// psql reports a syntax error by quoting the line it failed on, which is the line the
	// password is on.
	h.Servers.only().FailScript = &quotingFailure{password: password}

	_, err := h.Engines.ProvisionDatabase(context.Background(), &wisperpb.ProvisionDatabase{
		Grant:    grant("db-1", "proj_api", "proj_api_user", wisperpb.DatabaseEngine_DATABASE_ENGINE_POSTGRES),
		Password: password,
	})
	if err == nil {
		t.Fatal("a failing statement reported success")
	}
	if strings.Contains(err.Error(), password) {
		t.Fatalf("the customer's password is in the error the panel records: %v", err)
	}
	if !strings.Contains(err.Error(), "[redacted]") {
		t.Fatalf("the error does not show that something was removed from it: %v", err)
	}
}

// quotingFailure is a server error that echoes the statement, password and all.
type quotingFailure struct{ password string }

func (q *quotingFailure) Error() string {
	return `ERROR:  syntax error at or near "PASSWORD '` + q.password + `'"`
}
