package dbengine

import (
	"context"
	"fmt"
	"io"
	"log/slog"
	"os"
	"path/filepath"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
)

// Taking a logical backup of one customer's database.
//
// # Why a file and not a pipe
//
// The obvious shape is `pg_dump` writing to standard output and this function copying that
// stream straight to whoever asked. It is not the shape used here, because the exec transport
// hands back a byte slice: a dump of a customer's ten-gigabyte database would be a
// ten-gigabyte allocation on a node running forty other customers, and the customers whose
// databases are worth backing up are exactly the ones for whom that is fatal.
//
// So the dump is written into the transfer directory, which is bind-mounted into the server's
// container, and the host reads that file and streams it out. The peak cost is disk, which the
// node has and can account for, rather than memory, which it has less of and cannot.
//
// # Target
//
// The backup package supplies the name rather than a grant id, because a restore point may
// outlive the grant it came from and a dry run deliberately reads and writes a name no grant
// has (backup.proto, RestoreBackup.dry_run).

// Target names one database on this node for a dump or a restore.
type Target struct {
	// Engine is which server holds it. Required: the same name can exist on both.
	Engine spec.EngineKind
	// DatabaseName is the database to read or write. For a dry-run restore this is the
	// sibling name, which need not exist yet.
	DatabaseName string
	// Username is the login that should own what a restore creates. Empty leaves ownership to
	// the administrator, which is right for a dry run nobody is going to connect to as the
	// customer.
	Username string
	// Replace drops the database and recreates it empty before a restore loads into it.
	//
	// False by default, and the default is the safe one: a restore into a database that
	// already has tables merges rather than replaces, which is almost never what a restore
	// means but is recoverable. True is what "put this restore point back" actually is, and
	// the backup package sets it deliberately.
	Replace bool
}

// Dump writes a logical backup of one database to `into` and returns how many bytes it wrote.
//
// The dump is the engine's own format: PostgreSQL's custom format, which is compressed and can
// be loaded into a database with a different name, and plain SQL for MySQL with no
// CREATE DATABASE in it, which achieves the same thing.
func (e *Engines) Dump(ctx context.Context, target Target, into io.Writer) (int64, error) {
	seen, err := e.serverOf(ctx, target.Engine)
	if err != nil {
		return 0, err
	}
	if _, present := seen.database(target.DatabaseName); !present {
		return 0, fmt.Errorf("dbengine: there is no database called %s on this node's %s server, "+
			"so there was nothing to dump", target.DatabaseName, target.Engine)
	}

	name, err := transferName("dump", seen.Instance.Talk.dumpExtension())
	if err != nil {
		return 0, err
	}
	hostPath := filepath.Join(seen.Instance.Paths.Transfer, name)
	containerPath := transferMount + "/" + name
	// Not deferred to a shutdown hook: sasayaki is crash-only and leaves nothing behind on
	// exit, so the file is removed here on every path including the failing ones. A daemon
	// killed mid-dump leaves one file behind, which the next dump of the same database does
	// not collide with because the name is random.
	defer removeTransfer(e.log, hostPath)

	statement, err := seen.Instance.Talk.dump(seen.Instance.Spec, target.DatabaseName, containerPath)
	if err != nil {
		return 0, err
	}
	if _, err := e.runner(seen.ContainerID)(ctx, statement); err != nil {
		return 0, err
	}

	file, err := os.Open(hostPath)
	if err != nil {
		return 0, fmt.Errorf("dbengine: the dump of %s was reported as written and could not be "+
			"read back: %w", target.DatabaseName, err)
	}
	defer file.Close()

	written, err := io.Copy(into, file)
	if err != nil {
		return written, fmt.Errorf("dbengine: send the dump of %s: %w", target.DatabaseName, err)
	}
	if written == 0 {
		return 0, fmt.Errorf("dbengine: the dump of %s came out empty, which no database "+
			"produces; it has not been treated as a backup", target.DatabaseName)
	}

	e.log.Info("dumped a database",
		slog.String("database", target.DatabaseName),
		slog.String("instance", seen.Instance.ID),
		slog.Int64("bytes", written))
	return written, nil
}

// serverOf finds the one ready server of a kind on this node.
//
// By kind rather than by grant id, because a restore point outlives the grant that produced
// it: the row the panel is restoring from may name a database whose managed_database record
// was deleted, and a lookup through the spec would then find nothing. On a node with more than
// one server of a kind the shared one is used, which is where every database the panel places
// without a dedicated instance lives.
func (e *Engines) serverOf(ctx context.Context, kind spec.EngineKind) (observed, error) {
	if _, err := dialectFor(kind); err != nil {
		return observed{}, err
	}

	_, wanted, servers, err := e.survey(ctx, GlanceBudget)
	if err != nil {
		return observed{}, err
	}

	for _, built := range wanted {
		if built.Kind != kind {
			continue
		}
		seen := servers[built.ID]
		if seen.Ready {
			return seen, nil
		}
		if seen.ContainerID == "" {
			return observed{}, fmt.Errorf("dbengine: this node's %s server has not been created "+
				"yet: %s", kind, seen.Detail)
		}
		if err := e.waitReady(ctx, built, seen.ContainerID, StartupBudget); err != nil {
			return observed{}, err
		}
		existing, err := e.containers(ctx)
		if err != nil {
			return observed{}, err
		}
		if seen = e.inspect(ctx, built, existing[built.ID], GlanceBudget); seen.Ready {
			return seen, nil
		}
		return observed{}, fmt.Errorf("dbengine: this node's %s server is not ready: %s",
			kind, seen.Detail)
	}
	return observed{}, fmt.Errorf("dbengine: this node runs no %s server", kind)
}

// removeTransfer deletes a dump that has been read or written.
//
// A failure is logged rather than returned: the caller already has a result, and replacing it
// with "and the cleanup failed too" would hide which of the two mattered. What it must not do
// is stay silent, because a transfer directory that only grows is a node that fills its disk
// one backup at a time.
func removeTransfer(log *slog.Logger, path string) {
	if err := os.Remove(path); err != nil && !os.IsNotExist(err) {
		log.Warn("could not remove a database dump after using it",
			slog.String("path", path), slog.String("error", err.Error()))
	}
}
