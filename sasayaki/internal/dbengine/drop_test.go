package dbengine

import (
	"context"
	"strings"
	"testing"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Dropping, which is the only thing in this package that destroys anything.

func provisioned(t *testing.T, kind wisperpb.DatabaseEngine, engine *wisperpb.DatabaseEngineSpec) *harness {
	t.Helper()

	h := newHarness(t)
	h.publish(
		[]*wisperpb.DatabaseEngineSpec{engine},
		[]*wisperpb.DatabaseGrant{grant("db-1", "proj_api", "proj_api_user", kind)},
	)
	h.converge(t)
	if _, err := h.Engines.ProvisionDatabase(context.Background(), &wisperpb.ProvisionDatabase{
		Grant:    grant("db-1", "proj_api", "proj_api_user", kind),
		Password: "password",
	}); err != nil {
		t.Fatalf("provision: %v", err)
	}
	return h
}

func TestDropWithoutDropDataRemovesTheLoginAndKeepsTheData(t *testing.T) {
	h := provisioned(t, wisperpb.DatabaseEngine_DATABASE_ENGINE_POSTGRES, postgresEngine())
	server := h.Servers.only()
	server.Databases["proj_api"].Bytes = 8192

	if err := h.Engines.DropDatabase(context.Background(), &wisperpb.DropDatabase{
		Id:           "db-1",
		Engine:       wisperpb.DatabaseEngine_DATABASE_ENGINE_POSTGRES,
		DatabaseName: "proj_api",
		Username:     "proj_api_user",
		DropData:     false,
	}); err != nil {
		t.Fatalf("drop: %v", err)
	}

	if _, present := server.Databases["proj_api"]; !present {
		t.Fatal("the data was destroyed by a drop that did not ask for it")
	}
	login, present := server.Logins["proj_api_user"]
	if !present {
		t.Fatal("the PostgreSQL role was removed, and it owns the database that was kept")
	}
	if !login.Locked {
		t.Fatal("the login still works after being revoked")
	}
}

func TestDropWithDropDataRemovesBoth(t *testing.T) {
	h := provisioned(t, wisperpb.DatabaseEngine_DATABASE_ENGINE_MYSQL, mysqlEngine())
	server := h.Servers.only()

	if err := h.Engines.DropDatabase(context.Background(), &wisperpb.DropDatabase{
		Id:           "db-1",
		Engine:       wisperpb.DatabaseEngine_DATABASE_ENGINE_MYSQL,
		DatabaseName: "proj_api",
		Username:     "proj_api_user",
		DropData:     true,
	}); err != nil {
		t.Fatalf("drop: %v", err)
	}

	if _, present := server.Databases["proj_api"]; present {
		t.Fatal("the database is still there after an explicit drop")
	}
	if _, present := server.Logins["proj_api_user"]; present {
		t.Fatal("the login is still there after an explicit drop")
	}
}

func TestDropRefusesADatabaseThatBelongsToSomebodyElse(t *testing.T) {
	h := provisioned(t, wisperpb.DatabaseEngine_DATABASE_ENGINE_POSTGRES, postgresEngine())
	server := h.Servers.only()
	// The panel and the node disagree about who owns this name. Removing it anyway would
	// destroy a database nobody asked about.
	server.Databases["proj_api"].Owner = "someone_else"

	err := h.Engines.DropDatabase(context.Background(), &wisperpb.DropDatabase{
		Id:           "db-1",
		Engine:       wisperpb.DatabaseEngine_DATABASE_ENGINE_POSTGRES,
		DatabaseName: "proj_api",
		Username:     "proj_api_user",
		DropData:     true,
	})
	if err == nil {
		t.Fatal("dropping another login's database reported success")
	}
	if !strings.Contains(err.Error(), "different login") {
		t.Fatalf("the refusal does not say why: %v", err)
	}
	if _, present := server.Databases["proj_api"]; !present {
		t.Fatal("the database was removed despite the refusal")
	}
}

func TestAGrantThatLeavesTheSpecIsNotDestroyed(t *testing.T) {
	// docs/contracts/node-spec.md makes omission deletion for a container, and this package
	// deliberately does not extend that to a database: the wire gives the node no way to tell
	// a login it created from one an operator made, so the worst case of the rule is every
	// account on the machine. The panel sends DropDatabase instead.
	h := provisioned(t, wisperpb.DatabaseEngine_DATABASE_ENGINE_POSTGRES, postgresEngine())
	server := h.Servers.only()

	h.publish([]*wisperpb.DatabaseEngineSpec{postgresEngine()}, nil)
	h.converge(t)

	if _, present := server.Databases["proj_api"]; !present {
		t.Fatal("a grant that left the spec had its data destroyed without a DropDatabase command")
	}
	if _, present := server.Logins["proj_api_user"]; !present {
		t.Fatal("a grant that left the spec had its login removed without a DropDatabase command")
	}
}

func TestRotateRefusesADatabaseThePanelPutOnTheOtherEngine(t *testing.T) {
	h := provisioned(t, wisperpb.DatabaseEngine_DATABASE_ENGINE_POSTGRES, postgresEngine())

	err := h.Engines.RotateDatabasePassword(context.Background(), &wisperpb.RotateDatabasePassword{
		Id:          "db-1",
		Username:    "proj_api_user",
		Engine:      wisperpb.DatabaseEngine_DATABASE_ENGINE_MYSQL,
		NewPassword: "password",
	})
	if err == nil {
		t.Fatal("a rotation naming the wrong engine was accepted, and the same name can exist " +
			"on both servers")
	}
}

func TestRotateRefusesAGrantThisNodeHasNeverHeardOf(t *testing.T) {
	h := provisioned(t, wisperpb.DatabaseEngine_DATABASE_ENGINE_POSTGRES, postgresEngine())

	err := h.Engines.RotateDatabasePassword(context.Background(), &wisperpb.RotateDatabasePassword{
		Id:          "db-does-not-exist",
		Username:    "proj_api_user",
		Engine:      wisperpb.DatabaseEngine_DATABASE_ENGINE_POSTGRES,
		NewPassword: "a-different-password",
	})
	if err == nil {
		t.Fatal("a rotation for a grant that is not in the spec was accepted")
	}
	if h.Servers.only().Logins["proj_api_user"].Password == "a-different-password" {
		t.Fatal("the password was changed for a grant this node was never told about")
	}
}
