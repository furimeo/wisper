package backup

import (
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"os"
)

// Getting the archive to the destination, and recording what it should hash to.
//
// The sidecar is written after the archive and not before. An archive with no sidecar is
// visible as an incomplete backup and is refused by a restore; a sidecar with no archive would
// be a promise about something that is not there, and retention counts generations by the
// archive's own name, so the pair are read together and only ever in that order.
//
// The digest in the sidecar is the one taken while the archive was written to local disk, over
// the exact bytes that were then uploaded. That is what makes the verify step meaningful: it
// re-derives the digest from what came back and compares it against a number computed before
// the network was involved at all.

// upload sends a staged archive and its sidecar, resuming through the journal.
func (r *Runner) upload(ctx context.Context, destination Destination, key string,
	archive staged, journal *uploadJournal) error {

	file, err := os.Open(archive.Path)
	if err != nil {
		return fmt.Errorf("backup: open the staged archive %s: %w", archive.Path, err)
	}
	defer file.Close()

	if err := destination.Upload(ctx, key, file, archive.Size, journal); err != nil {
		return err
	}

	sidecar, err := json.Marshal(archiveManifest{SHA256: archive.SHA256, Size: archive.Size})
	if err != nil {
		return fmt.Errorf("backup: encode the checksum of %s: %w", key, err)
	}
	if err := destination.Put(ctx, digestKey(key), sidecar); err != nil {
		return fmt.Errorf("backup: the archive %s was uploaded but its checksum was not, so no "+
			"restore would accept it: %w", key, err)
	}

	r.log.Info("uploaded an archive",
		slog.String("key", key),
		slog.Int64("bytes", archive.Size),
		slog.String("sha256", archive.SHA256))
	return nil
}

// readSidecar fetches the recorded checksum of an archive.
//
// An archive with no sidecar is refused rather than trusted. It means one of two things - an
// upload that died between the two objects, or something in the bucket this node did not put
// there - and neither is a thing to restore over a customer's live data. Saying so plainly is
// the point: "there is no recorded checksum for this archive" is a sentence somebody can act
// on, and a silent restore of unverified bytes is not.
func readSidecar(ctx context.Context, destination Destination, key string) (archiveManifest, error) {
	reader, err := destination.Get(ctx, digestKey(key))
	if err != nil {
		return archiveManifest{}, fmt.Errorf("backup: %s has no recorded checksum beside it, so "+
			"there is nothing to check the archive against: %w", key, err)
	}
	defer reader.Close()

	var manifest archiveManifest
	if err := json.NewDecoder(reader).Decode(&manifest); err != nil {
		return archiveManifest{}, fmt.Errorf("backup: the checksum recorded beside %s cannot be "+
			"read: %w", key, err)
	}
	if manifest.SHA256 == "" || manifest.Size <= 0 {
		return archiveManifest{}, fmt.Errorf("backup: the checksum recorded beside %s is empty", key)
	}
	return manifest, nil
}
