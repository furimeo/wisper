package backup

import (
	"compress/gzip"
	"context"
	"fmt"
	"io"
	"log/slog"
	"os"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Putting a database back.
//
// A logical dump is replayed rather than moved, so there is no rename to make atomic and no
// staging tree to swap in - which means the safety copy is the whole of the protection. It is
// taken first, always, and a failure to take it stops the restore: a replay that half succeeds
// leaves a database that is neither the old one nor the new one, and without the dump taken a
// moment earlier there is nothing to go back to.
//
// A dry run creates a new database and replays into that. Nothing existing is touched at all,
// so the rehearsal can be run against a production database in the middle of the afternoon,
// and what the customer gets is a second database they can connect to and look at.

// restoreDatabase replays a dump, over the live database or into a rehearsal copy. It returns
// the line that describes what happened.
func (r *Runner) restoreDatabase(ctx context.Context, request *wisperpb.RestoreBackup,
	archive staged, outcome *restoreReport) (string, error) {

	if request.GetDryRun() {
		return r.rehearseDatabase(ctx, request, archive, outcome)
	}

	database := request.GetDatabaseName()
	r.note(ctx, request.GetRestoreId(), "dumping the current database before replacing it")
	rollback, err := r.dumpBeforeRestore(ctx, request)
	if err != nil {
		return "", err
	}

	r.note(ctx, request.GetRestoreId(), "replaying the dump")
	written, err := r.replay(ctx, request.GetEngine(), database, archive)
	outcome.bytes = written
	if err != nil {
		// Not undone automatically. A half-replayed dump is not a state this code can reason
		// about - the engine may have applied some statements and refused others - and
		// replaying the rollback dump on top of it without being asked could destroy whichever
		// of the two the customer would rather have kept. The message says where the copy is.
		r.log.Error("a database restore failed part way through",
			slog.String("database", database),
			slog.String("rollback_dump", rollback),
			slog.String("error", err.Error()))
		return "", fmt.Errorf("%w; the database as it was before this restore is dumped at %s",
			err, rollback)
	}

	outcome.restoredTo = database
	r.log.Info("restored a database",
		slog.String("database", database),
		slog.String("rollback_dump", rollback),
		slog.Int64("bytes", written))
	return fmt.Sprintf("%d bytes replayed into %s; the database as it was is dumped at %s",
		written, database, rollback), nil
}

// rehearseDatabase replays into a new database beside the live one.
func (r *Runner) rehearseDatabase(ctx context.Context, request *wisperpb.RestoreBackup,
	archive staged, outcome *restoreReport) (string, error) {

	rehearsal, err := dryRunDatabaseName(request.GetDatabaseName(), request.GetRestoreId())
	if err != nil {
		return "", err
	}
	if err := r.databases.CreateEmptyDatabase(ctx, request.GetEngine(), rehearsal); err != nil {
		return "", fmt.Errorf("backup: create %s to rehearse a restore into: %w", rehearsal, err)
	}

	r.note(ctx, request.GetRestoreId(), "replaying the dump into a copy")
	written, err := r.replay(ctx, request.GetEngine(), rehearsal, archive)
	outcome.bytes = written
	if err != nil {
		return "", err
	}

	outcome.restoredTo = rehearsal
	r.log.Info("rehearsed a database restore",
		slog.String("database", request.GetDatabaseName()),
		slog.String("into", rehearsal),
		slog.Int64("bytes", written))
	return fmt.Sprintf("%d bytes replayed into %s, beside %s rather than over it; nothing was "+
		"stopped and nothing was replaced", written, rehearsal, request.GetDatabaseName()), nil
}

// replay streams a gzipped dump into the engine and reports how many uncompressed bytes went.
func (r *Runner) replay(ctx context.Context, engine wisperpb.DatabaseEngine, database string,
	archive staged) (int64, error) {

	file, err := os.Open(archive.Path)
	if err != nil {
		return 0, fmt.Errorf("backup: open the downloaded dump %s: %w", archive.Path, err)
	}
	defer file.Close()

	stream, err := gzip.NewReader(contextReader(ctx, file))
	if err != nil {
		return 0, fmt.Errorf("backup: the dump of %s is not gzip: %w", database, err)
	}
	defer stream.Close()

	counted := &measuring{inner: stream}
	if err := r.databases.RestoreDatabase(ctx, engine, database, counted); err != nil {
		return counted.read, fmt.Errorf("backup: replay the dump into %s: %w", database, err)
	}
	return counted.read, nil
}

// measuring counts what was read out of a reader, so a restore can report the size of the dump
// it replayed rather than the size of the compressed file it came out of - which is the number
// a customer comparing a restore against their database can actually use.
type measuring struct {
	inner io.Reader
	read  int64
}

func (m *measuring) Read(p []byte) (int, error) {
	n, err := m.inner.Read(p)
	m.read += int64(n)
	return n, err
}
