package dbengine

import (
	"bytes"
	"context"
	"os"
	"strings"
	"testing"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Dump and restore, which is the half of a backup this package owns.
//
// The round trip is the test that matters: a backup that has never been restored is not a
// backup (design section 8.3), and the same is true of the code that takes one.

func seededServer(t *testing.T, engine *wisperpb.DatabaseEngineSpec, kind wisperpb.DatabaseEngine) *harness {
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

	server := h.Servers.only()
	server.Databases["proj_api"].Content = "forty-two customer rows"
	server.Databases["proj_api"].Bytes = 4096
	return h
}

func TestDumpAndRestoreRoundTripOnPostgres(t *testing.T) {
	h := seededServer(t, postgresEngine(), wisperpb.DatabaseEngine_DATABASE_ENGINE_POSTGRES)
	roundTrip(t, h, spec.EnginePostgres)
}

func TestDumpAndRestoreRoundTripOnMySQL(t *testing.T) {
	h := seededServer(t, mysqlEngine(), wisperpb.DatabaseEngine_DATABASE_ENGINE_MYSQL)
	roundTrip(t, h, spec.EngineMySQL)
}

// roundTrip dumps a database, wipes it, and puts the archive back.
func roundTrip(t *testing.T, h *harness, kind spec.EngineKind) {
	t.Helper()
	server := h.Servers.only()

	archive := &bytes.Buffer{}
	written, err := h.Engines.Dump(context.Background(), Target{
		Engine:       kind,
		DatabaseName: "proj_api",
	}, archive)
	if err != nil {
		t.Fatalf("dump: %v", err)
	}
	if written == 0 || int64(archive.Len()) != written {
		t.Fatalf("the dump reported %d bytes and produced %d", written, archive.Len())
	}

	// Everything is lost between the backup and the restore, which is the case a restore
	// point exists for.
	server.Databases["proj_api"].Content = ""
	server.Databases["proj_api"].Bytes = 0

	read, err := h.Engines.Restore(context.Background(), Target{
		Engine:       kind,
		DatabaseName: "proj_api",
		Username:     "proj_api_user",
		Replace:      true,
	}, bytes.NewReader(archive.Bytes()))
	if err != nil {
		t.Fatalf("restore: %v", err)
	}
	if read != written {
		t.Fatalf("the restore read %d bytes of a %d-byte archive", read, written)
	}

	restored, present := server.Databases["proj_api"]
	if !present {
		t.Fatal("the database is not there after being restored into")
	}
	if restored.Content != "forty-two customer rows" {
		t.Fatalf("the restored database holds %q", restored.Content)
	}
	if restored.Bytes != 4096 {
		t.Fatalf("the restored database measures %d bytes", restored.Bytes)
	}
}

func TestRestoreIntoASiblingIsWhatADryRunNeeds(t *testing.T) {
	h := seededServer(t, postgresEngine(), wisperpb.DatabaseEngine_DATABASE_ENGINE_POSTGRES)
	server := h.Servers.only()

	archive := &bytes.Buffer{}
	if _, err := h.Engines.Dump(context.Background(), Target{
		Engine:       spec.EnginePostgres,
		DatabaseName: "proj_api",
	}, archive); err != nil {
		t.Fatalf("dump: %v", err)
	}

	// A dry run restores alongside rather than over the top, into a name no grant has, so a
	// customer can look before committing.
	if _, err := h.Engines.Restore(context.Background(), Target{
		Engine:       spec.EnginePostgres,
		DatabaseName: "proj_api_restore",
		Username:     "proj_api_user",
	}, bytes.NewReader(archive.Bytes())); err != nil {
		t.Fatalf("dry-run restore: %v", err)
	}

	sibling, present := server.Databases["proj_api_restore"]
	if !present {
		t.Fatal("the sibling database was not created")
	}
	if sibling.Content != "forty-two customer rows" {
		t.Fatalf("the sibling holds %q", sibling.Content)
	}
	if sibling.Owner != "proj_api_user" {
		t.Fatalf("the sibling is owned by %q, so the customer cannot look at it", sibling.Owner)
	}
	if server.Databases["proj_api"].Content != "forty-two customer rows" {
		t.Fatal("the dry run changed the live database")
	}
}

func TestDumpLeavesNoFileBehind(t *testing.T) {
	h := seededServer(t, postgresEngine(), wisperpb.DatabaseEngine_DATABASE_ENGINE_POSTGRES)

	if _, err := h.Engines.Dump(context.Background(), Target{
		Engine:       spec.EnginePostgres,
		DatabaseName: "proj_api",
	}, &bytes.Buffer{}); err != nil {
		t.Fatalf("dump: %v", err)
	}

	paths, err := pathsFor(h.StateDir, postgresInstance)
	if err != nil {
		t.Fatalf("resolve the transfer directory: %v", err)
	}
	entries, err := os.ReadDir(paths.Transfer)
	if err != nil {
		t.Fatalf("read the transfer directory: %v", err)
	}
	if len(entries) != 0 {
		t.Fatalf("the transfer directory still holds %d files; a node that keeps every dump "+
			"fills its disk one backup at a time", len(entries))
	}
}

func TestDumpRefusesADatabaseThatIsNotThere(t *testing.T) {
	h := seededServer(t, postgresEngine(), wisperpb.DatabaseEngine_DATABASE_ENGINE_POSTGRES)

	_, err := h.Engines.Dump(context.Background(), Target{
		Engine:       spec.EnginePostgres,
		DatabaseName: "no_such_db",
	}, &bytes.Buffer{})
	if err == nil {
		t.Fatal("dumping a database that does not exist reported success, which would record a " +
			"restore point that restores nothing")
	}
	if !strings.Contains(err.Error(), "nothing to dump") {
		t.Fatalf("the refusal does not say why: %v", err)
	}
}

func TestRestoreRefusesAnEmptyArchive(t *testing.T) {
	h := seededServer(t, postgresEngine(), wisperpb.DatabaseEngine_DATABASE_ENGINE_POSTGRES)

	_, err := h.Engines.Restore(context.Background(), Target{
		Engine:       spec.EnginePostgres,
		DatabaseName: "proj_api",
		Replace:      true,
	}, bytes.NewReader(nil))
	if err == nil {
		t.Fatal("restoring an empty archive reported success")
	}
	if h.Servers.only().Databases["proj_api"].Content != "forty-two customer rows" {
		t.Fatal("an empty archive was allowed to destroy the live database on its way to failing")
	}
}

func TestRestoreRefusesADatabaseNameThatCouldNotHaveComeFromThePanel(t *testing.T) {
	h := seededServer(t, postgresEngine(), wisperpb.DatabaseEngine_DATABASE_ENGINE_POSTGRES)

	_, err := h.Engines.Restore(context.Background(), Target{
		Engine:       spec.EnginePostgres,
		DatabaseName: `x"; DROP DATABASE postgres; --`,
	}, bytes.NewReader([]byte("anything")))
	if err == nil {
		t.Fatal("a restore into a name with a quote in it was accepted")
	}
}

func TestARestoreOverALiveDatabaseKeepsTheLogin(t *testing.T) {
	// Emptying the target must not take the account with it. The node cannot put the login
	// back - the password belongs to the panel - so a restore that dropped it would leave a
	// customer with restored data and no way to reach it.
	h := seededServer(t, postgresEngine(), wisperpb.DatabaseEngine_DATABASE_ENGINE_POSTGRES)
	server := h.Servers.only()

	archive := &bytes.Buffer{}
	if _, err := h.Engines.Dump(context.Background(), Target{
		Engine:       spec.EnginePostgres,
		DatabaseName: "proj_api",
	}, archive); err != nil {
		t.Fatalf("dump: %v", err)
	}

	if _, err := h.Engines.Restore(context.Background(), Target{
		Engine:       spec.EnginePostgres,
		DatabaseName: "proj_api",
		Username:     "proj_api_user",
		Replace:      true,
	}, bytes.NewReader(archive.Bytes())); err != nil {
		t.Fatalf("restore: %v", err)
	}

	login, present := server.Logins["proj_api_user"]
	if !present {
		t.Fatal("the customer's login was removed by a restore over their own database")
	}
	if login.Locked {
		t.Fatal("the customer's login was locked by a restore")
	}
	if login.Password != "password" {
		t.Fatalf("the customer's password changed to %q during a restore", login.Password)
	}
	if server.Databases["proj_api"].Owner != "proj_api_user" {
		t.Fatalf("the restored database is owned by %q", server.Databases["proj_api"].Owner)
	}
}
