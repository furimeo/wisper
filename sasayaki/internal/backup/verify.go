package backup

import (
	"context"
	"fmt"
	"io"
	"log/slog"
)

// Reading it back.
//
// This is the difference between a backup and a log line saying one was uploaded. It costs the
// download - which on a metered connection is real money, which is why it is a field on the
// command rather than always on - and it buys the one fact nobody can otherwise assert: the
// bytes are at the destination, they are the right length, and they hash to what was computed
// before they left this machine.
//
// It catches the failures that a 200 from an object store does not: a proxy that truncated the
// body, a multipart upload completed with a part from a previous attempt, a bucket that
// silently discards writes over a quota, a disk at the far end that has begun to rot. Every
// one of those produces an object of plausible size that is not the backup.
//
// The archive is streamed and hashed, never held in memory. A forty-gigabyte volume verifies
// in constant space.

// verify downloads an archive and checks it against the recorded checksum.
func (r *Runner) verify(ctx context.Context, destination Destination, key string, expected staged) error {
	sidecar, err := readSidecar(ctx, destination, key)
	if err != nil {
		return err
	}
	if sidecar.SHA256 != expected.SHA256 || sidecar.Size != expected.Size {
		return fmt.Errorf("backup: the checksum recorded beside %s says %s of %d bytes, and the "+
			"archive that was just uploaded is %s of %d: %w",
			key, sidecar.SHA256, sidecar.Size, expected.SHA256, expected.Size, ErrChecksumMismatch)
	}

	reader, err := destination.Get(ctx, key)
	if err != nil {
		return fmt.Errorf("backup: read %s back to check it: %w", key, err)
	}
	defer reader.Close()

	hashing := newHashingReader(reader)
	if _, err := io.Copy(io.Discard, contextReader(ctx, hashing)); err != nil {
		return fmt.Errorf("backup: read %s back to check it: %w", key, err)
	}

	if hashing.read != expected.Size {
		return fmt.Errorf("backup: %s was uploaded as %d bytes and reads back as %d: %w",
			key, expected.Size, hashing.read, ErrChecksumMismatch)
	}
	if digest := hashing.sum(); digest != expected.SHA256 {
		return fmt.Errorf("backup: %s was uploaded with checksum %s and reads back as %s: %w",
			key, expected.SHA256, digest, ErrChecksumMismatch)
	}

	r.log.Info("read an archive back and checked it",
		slog.String("key", key), slog.Int64("bytes", hashing.read))
	return nil
}

// fetch downloads an archive to local disk and refuses to hand it over unless it matches.
//
// Used by a restore, and the order of operations is the whole design. The bytes land in the
// work directory, are hashed on the way in, and are compared against the sidecar before
// anything at all is stopped, moved or overwritten. A restore that discovers a corrupt archive
// discovers it while the customer's application is still running on data that is still theirs.
func (r *Runner) fetch(ctx context.Context, destination Destination, key, runID, extension string) (staged, error) {
	sidecar, err := readSidecar(ctx, destination, key)
	if err != nil {
		return staged{}, err
	}

	result, err := stageDownload(r.stateDir, runID, extension, func(out io.Writer) error {
		reader, err := destination.Get(ctx, key)
		if err != nil {
			return fmt.Errorf("backup: read %s: %w", key, err)
		}
		defer reader.Close()

		if _, err := io.Copy(out, contextReader(ctx, reader)); err != nil {
			return fmt.Errorf("backup: read %s: %w", key, err)
		}
		return nil
	})
	if err != nil {
		return staged{}, err
	}

	if result.Size != sidecar.Size || result.SHA256 != sidecar.SHA256 {
		// Removed rather than left behind. A downloaded archive that failed its checksum is a
		// file that must never be picked up by a resumed run as though it were sound.
		discardStaged(r.stateDir, runID, extension)
		return staged{}, fmt.Errorf("backup: %s should be %s of %d bytes and came back as %s of "+
			"%d, so nothing was restored: %w",
			key, sidecar.SHA256, sidecar.Size, result.SHA256, result.Size, ErrChecksumMismatch)
	}

	r.log.Info("downloaded an archive and checked it before restoring",
		slog.String("key", key), slog.Int64("bytes", result.Size))
	return result, nil
}
