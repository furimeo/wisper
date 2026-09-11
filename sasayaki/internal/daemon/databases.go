package daemon

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"io"

	"github.com/furimeo/wisper/sasayaki/internal/backup"
	"github.com/furimeo/wisper/sasayaki/internal/dbengine"
	"github.com/furimeo/wisper/sasayaki/internal/spec"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// A backup's view of the database servers.
//
// backup speaks in wire enums and byte counts because that is what a RunBackup carries;
// dbengine speaks in spec.EngineKind and dbengine.Target because that is what a server
// needs. The translation is three lines and it lives here, in the composition root, so that
// neither package has to import the other's vocabulary.
//
// Two decisions in this file are not translation, and both are worth reading.

// databaseServers is the shared database layer, as a backup needs it.
type databaseServers interface {
	Dump(ctx context.Context, target dbengine.Target, into io.Writer) (int64, error)
	Restore(ctx context.Context, target dbengine.Target, from io.Reader) (int64, error)
	ProvisionDatabase(ctx context.Context, request *wisperpb.ProvisionDatabase) (*wisperpb.DatabaseProvisioned, error)
}

// databaseDumps is the daemon's backup.DatabaseDumps.
type databaseDumps struct {
	engines databaseServers
}

var _ backup.DatabaseDumps = databaseDumps{}

// DumpDatabase writes a logical dump of one database.
//
// Consistent without anything being paused: the dump is taken inside a transaction, which
// is the whole reason a database backup needs no quiesce window while a volume backup does.
func (d databaseDumps) DumpDatabase(ctx context.Context, engine wisperpb.DatabaseEngine,
	database string, out io.Writer) error {

	kind, err := engineKind(engine)
	if err != nil {
		return err
	}
	_, err = d.engines.Dump(ctx, dbengine.Target{Engine: kind, DatabaseName: database}, out)
	return err
}

// RestoreDatabase replays a dump into a database.
//
// Replace is set, and that is a deliberate departure from the literal wording of
// backup.DatabaseDumps, which says the dump empties what it recreates. That is true of
// mysqldump, whose output carries DROP TABLE IF EXISTS in front of every table. It is not
// true of a PostgreSQL custom-format archive: pg_restore without --clean creates and does
// not drop, so a replay over a live database fails on the first relation that already
// exists - which would make a real PostgreSQL restore impossible while looking like a
// permissions problem.
//
// Dropping the database first is safe here and only here, because the caller has already
// taken a rollback dump of it and refuses to go on if that failed
// (backup/restoredatabase.go). A rehearsal is unaffected: it replays into a sibling name
// that was created empty a moment earlier.
func (d databaseDumps) RestoreDatabase(ctx context.Context, engine wisperpb.DatabaseEngine,
	database string, in io.Reader) error {

	kind, err := engineKind(engine)
	if err != nil {
		return err
	}
	_, err = d.engines.Restore(ctx, dbengine.Target{
		Engine:       kind,
		DatabaseName: database,
		Replace:      true,
	}, in)
	return err
}

// CreateEmptyDatabase makes the database a rehearsal restores into, and refuses a name that
// is already somebody's.
//
// It goes through ProvisionDatabase because that is the only exported way into the
// dialect's CREATE DATABASE, and it is the one that carries the check that matters: a name
// already held by a database with a different owner is refused rather than taken over. That
// check is the whole point of this method - the replay that follows drops and recreates
// what it is given, so a rehearsal that landed on a live database would destroy it.
//
// The cost of the route is a login the rehearsal does not need. It is created with a
// password generated here and immediately forgotten, so nothing can connect as it; the
// alternative would be a second CREATE DATABASE path in the composition root, written
// against two SQL dialects that already have one.
func (d databaseDumps) CreateEmptyDatabase(ctx context.Context, engine wisperpb.DatabaseEngine,
	database string) error {

	if _, err := engineKind(engine); err != nil {
		return err
	}
	password, err := unusablePassword()
	if err != nil {
		return err
	}

	_, err = d.engines.ProvisionDatabase(ctx, &wisperpb.ProvisionDatabase{
		Grant: &wisperpb.DatabaseGrant{
			// Not a grant the panel knows about. The id is only used to place the database
			// on this node's shared server of that engine and to key the last-error record,
			// which the next convergence pass forgets because it is not in the spec.
			Id:           "rehearsal:" + database,
			Engine:       engine,
			DatabaseName: database,
			Username:     rehearsalLogin(database),
		},
		Password: password,
	})
	return err
}

// maxRehearsalLogin is what a MySQL user may be, and what the panel's own constraint allows.
// A rehearsal database name can be longer than that, so the login is the front of it.
const maxRehearsalLogin = 31

// rehearsalLogin is the owner a rehearsal database is created under.
//
// Derived from the database name, which already carries the restore id that makes it unique
// (backup/objectname.go, dryRunDatabaseName), so it cannot collide with the login of the
// database being rehearsed. Truncated rather than hashed, because it ends up in the output
// of \du on a shared server and an operator should be able to see what it belongs to.
func rehearsalLogin(database string) string {
	if len(database) <= maxRehearsalLogin {
		return database
	}
	return database[:maxRehearsalLogin]
}

// unusablePassword is a secret nobody is given.
//
// The database layer refuses an empty one, correctly: an account with no password on a
// shared server is an account every other customer on it can use. So the rehearsal's login
// gets a real one that is generated, sent to the server and dropped on the floor here.
func unusablePassword() (string, error) {
	secret := make([]byte, 32)
	if _, err := rand.Read(secret); err != nil {
		return "", fmt.Errorf("daemon: generate a password for a rehearsal database: %w", err)
	}
	return hex.EncodeToString(secret), nil
}

// engineKind is the wire enum as the database layer names it.
//
// An engine this binary does not implement is refused rather than guessed at: starting the
// wrong client against a server produces a dump no restore can read, which is the one
// failure a backup system must not have.
func engineKind(engine wisperpb.DatabaseEngine) (spec.EngineKind, error) {
	switch engine {
	case wisperpb.DatabaseEngine_DATABASE_ENGINE_POSTGRES:
		return spec.EnginePostgres, nil
	case wisperpb.DatabaseEngine_DATABASE_ENGINE_MYSQL:
		return spec.EngineMySQL, nil
	default:
		return spec.EngineUnknown, fmt.Errorf("daemon: %s is not a database engine this node runs",
			engine)
	}
}
