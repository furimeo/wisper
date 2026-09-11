package dbengine

import (
	"context"
	"strings"
	"testing"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
	"github.com/furimeo/wisper/sasayaki/internal/state"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Measuring, which is the only quota enforcement this platform has: the node reports the size
// and the panel decides.

func TestStatusesReportTheSizeTheServerAccountsFor(t *testing.T) {
	h := newHarness(t)
	h.publish(
		[]*wisperpb.DatabaseEngineSpec{postgresEngine()},
		[]*wisperpb.DatabaseGrant{grant("db-1", "proj_api", "proj_api_user",
			wisperpb.DatabaseEngine_DATABASE_ENGINE_POSTGRES)},
	)
	h.converge(t)
	h.Servers.only().sizeOf("proj_api", 512<<20)

	statuses, err := h.Engines.Statuses(context.Background())
	if err != nil {
		t.Fatalf("measure: %v", err)
	}

	status := statusFor(t, statuses, "db-1")
	if !status.Exists {
		t.Fatalf("the database was reported as missing: %s", status.LastError)
	}
	if status.SizeBytes != 512<<20 {
		t.Fatalf("the size came back as %d", status.SizeBytes)
	}
	if status.QuotaBytes != 1<<30 {
		t.Fatalf("the quota came back as %d", status.QuotaBytes)
	}
	if status.OverQuota {
		t.Fatal("half a gigabyte in a one-gigabyte quota was reported as over")
	}
	if status.EngineVersion != "PostgreSQL 17.2" {
		t.Fatalf("the engine version came back as %q", status.EngineVersion)
	}
	if status.MeasuredAt.IsZero() {
		t.Fatal("the measurement has no timestamp, so the panel cannot tell a fresh figure from a stale one")
	}
}

func TestStatusesMarkADatabaseOverItsQuota(t *testing.T) {
	h := newHarness(t)
	h.publish(
		[]*wisperpb.DatabaseEngineSpec{mysqlEngine()},
		[]*wisperpb.DatabaseGrant{grant("db-2", "shop_db", "shop_user",
			wisperpb.DatabaseEngine_DATABASE_ENGINE_MYSQL)},
	)
	h.converge(t)
	h.Servers.only().sizeOf("shop_db", 1<<30)

	statuses, err := h.Engines.Statuses(context.Background())
	if err != nil {
		t.Fatalf("measure: %v", err)
	}

	status := statusFor(t, statuses, "db-2")
	if !status.OverQuota {
		t.Fatalf("a database exactly at its quota of %d was not reported as over", status.QuotaBytes)
	}
}

func TestStatusesReportUnavailableRatherThanDeletedWhenTheServerIsDown(t *testing.T) {
	h := newHarness(t)
	h.publish(
		[]*wisperpb.DatabaseEngineSpec{postgresEngine()},
		[]*wisperpb.DatabaseGrant{grant("db-1", "proj_api", "proj_api_user",
			wisperpb.DatabaseEngine_DATABASE_ENGINE_POSTGRES)},
	)
	h.converge(t)
	h.Servers.only().sizeOf("proj_api", 4096)

	// The server stops answering. The panel must be told "unavailable", not shown a database
	// that has silently become zero bytes and not shown nothing at all.
	h.Servers.only().setReady(false)

	statuses, err := h.Engines.Statuses(context.Background())
	if err != nil {
		t.Fatalf("measure: %v", err)
	}
	if len(statuses) != 1 {
		t.Fatalf("a server that is down produced %d statuses; the panel writes only the rows it "+
			"receives, so silence leaves a stale figure with no explanation", len(statuses))
	}

	status := statusFor(t, statuses, "db-1")
	if status.Exists {
		t.Fatal("a database on a server nobody could ask was reported as present")
	}
	if status.SizeBytes != 0 {
		t.Fatalf("a database that could not be measured reported a size of %d", status.SizeBytes)
	}
	if status.LastError == "" {
		t.Fatal("no reason was given for the database being unavailable")
	}
}

func TestStatusesSayNothingBeforeTheFirstSpecArrives(t *testing.T) {
	h := newHarness(t)
	h.Store.Err = state.ErrNoSpec

	statuses, err := h.Engines.Statuses(context.Background())
	if err != nil {
		t.Fatalf("a node that has never been given a spec failed to report: %v", err)
	}
	if len(statuses) != 0 {
		t.Fatalf("a node with no spec reported %d databases", len(statuses))
	}
}

func TestStatusesDoNotCreateAnything(t *testing.T) {
	// Statuses is called from the fifteen-second reconcile pass that systemd's watchdog is
	// watching. Pulling an image there would have the daemon restarted for doing its job.
	h := newHarness(t)
	h.publish(
		[]*wisperpb.DatabaseEngineSpec{postgresEngine()},
		[]*wisperpb.DatabaseGrant{grant("db-1", "proj_api", "proj_api_user",
			wisperpb.DatabaseEngine_DATABASE_ENGINE_POSTGRES)},
	)

	statuses, err := h.Engines.Statuses(context.Background())
	if err != nil {
		t.Fatalf("measure: %v", err)
	}
	if len(h.Docker.Created) != 0 || len(h.Docker.Pulled) != 0 {
		t.Fatal("measuring created a container or pulled an image")
	}

	status := statusFor(t, statuses, "db-1")
	if status.Exists {
		t.Fatal("a database on a server that does not exist was reported as present")
	}
	if !strings.Contains(status.LastError, "has not created the container") {
		t.Fatalf("the reason given is %q", status.LastError)
	}
}

func TestSettleRecreatesAGrantThatIsMissingFromItsServer(t *testing.T) {
	h := newHarness(t)
	h.publish(
		[]*wisperpb.DatabaseEngineSpec{postgresEngine()},
		[]*wisperpb.DatabaseGrant{grant("db-1", "proj_api", "proj_api_user",
			wisperpb.DatabaseEngine_DATABASE_ENGINE_POSTGRES)},
	)

	// The node lost its disk: the server comes up empty while the panel is still showing the
	// customer a connection string.
	h.converge(t)

	server := h.Servers.only()
	database, present := server.Databases["proj_api"]
	if !present {
		t.Fatal("a grant in the spec was not rebuilt on a server that had lost it")
	}
	if database.Owner != "proj_api_user" {
		t.Fatalf("the rebuilt database is owned by %q", database.Owner)
	}
	login, present := server.Logins["proj_api_user"]
	if !present {
		t.Fatal("the login was not rebuilt")
	}
	if !login.Locked {
		t.Fatal("the login was rebuilt unlocked, which is an account on a shared server that " +
			"nobody knows the password of")
	}

	statuses, err := h.Engines.Statuses(context.Background())
	if err != nil {
		t.Fatalf("measure: %v", err)
	}
	status := statusFor(t, statuses, "db-1")
	if !strings.Contains(status.LastError, "locked until the panel sends a new password") {
		t.Fatalf("the panel was not told to send a rotation: %q", status.LastError)
	}

	// And the rotation the panel sends back is what makes it usable.
	if err := h.Engines.RotateDatabasePassword(context.Background(), &wisperpb.RotateDatabasePassword{
		Id:          "db-1",
		Username:    "proj_api_user",
		Engine:      wisperpb.DatabaseEngine_DATABASE_ENGINE_POSTGRES,
		NewPassword: "a-new-password",
	}); err != nil {
		t.Fatalf("rotate: %v", err)
	}
	if server.Logins["proj_api_user"].Locked {
		t.Fatal("the rotation did not unlock the account")
	}
	if server.Logins["proj_api_user"].Password != "a-new-password" {
		t.Fatalf("the account's password is %q", server.Logins["proj_api_user"].Password)
	}
}

func TestSettleLeavesADatabaseThatBelongsToSomebodyElseAlone(t *testing.T) {
	h := newHarness(t)
	h.publish([]*wisperpb.DatabaseEngineSpec{postgresEngine()}, nil)
	h.converge(t)
	h.Servers.only().Databases["proj_api"] = &fakeDatabase{Owner: "someone_else", Bytes: 99}

	h.publish(
		[]*wisperpb.DatabaseEngineSpec{postgresEngine()},
		[]*wisperpb.DatabaseGrant{grant("db-1", "proj_api", "proj_api_user",
			wisperpb.DatabaseEngine_DATABASE_ENGINE_POSTGRES)},
	)
	h.converge(t)

	if h.Servers.only().Databases["proj_api"].Owner != "someone_else" {
		t.Fatal("convergence took over a database that belongs to another login")
	}

	statuses, err := h.Engines.Statuses(context.Background())
	if err != nil {
		t.Fatalf("measure: %v", err)
	}
	status := statusFor(t, statuses, "db-1")
	if status.Exists {
		t.Fatal("somebody else's database was reported as this grant's")
	}
	if !strings.Contains(status.LastError, "different login") {
		t.Fatalf("the collision was not reported: %q", status.LastError)
	}
}

func TestBothEnginesOnOneNodeAreMeasuredSeparately(t *testing.T) {
	// The ordinary shape of a busy node: one shared PostgreSQL and one shared MySQL, with the
	// same database name on both. Nothing may leak from one to the other, least of all a size.
	h := newHarness(t)
	h.publish(
		[]*wisperpb.DatabaseEngineSpec{postgresEngine(), mysqlEngine()},
		[]*wisperpb.DatabaseGrant{
			grant("db-pg", "proj_api", "proj_api_user", wisperpb.DatabaseEngine_DATABASE_ENGINE_POSTGRES),
			grant("db-my", "proj_api", "proj_api_user", wisperpb.DatabaseEngine_DATABASE_ENGINE_MYSQL),
		},
	)
	h.converge(t)

	h.Servers.ofKind(spec.EnginePostgres).sizeOf("proj_api", 111)
	h.Servers.ofKind(spec.EngineMySQL).sizeOf("proj_api", 222)

	statuses, err := h.Engines.Statuses(context.Background())
	if err != nil {
		t.Fatalf("measure: %v", err)
	}

	postgresStatus := statusFor(t, statuses, "db-pg")
	if postgresStatus.SizeBytes != 111 || postgresStatus.Engine != spec.EnginePostgres {
		t.Fatalf("the PostgreSQL database came back as %d bytes on %s",
			postgresStatus.SizeBytes, postgresStatus.Engine)
	}
	if postgresStatus.EngineVersion != "PostgreSQL 17.2" {
		t.Fatalf("the PostgreSQL version came back as %q", postgresStatus.EngineVersion)
	}

	mysqlStatus := statusFor(t, statuses, "db-my")
	if mysqlStatus.SizeBytes != 222 || mysqlStatus.Engine != spec.EngineMySQL {
		t.Fatalf("the MySQL database came back as %d bytes on %s",
			mysqlStatus.SizeBytes, mysqlStatus.Engine)
	}
	if mysqlStatus.EngineVersion != "8.4.3" {
		t.Fatalf("the MySQL version came back as %q", mysqlStatus.EngineVersion)
	}
}
