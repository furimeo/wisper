package backup

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
)

// What an interrupted upload leaves behind so the next attempt does not start from zero.
//
// A multipart upload is a create call, N part calls and a complete call, and the store keeps
// the parts on its side until the complete arrives. That is the whole reason resuming is
// possible at all: the bytes of part 1 through 6 are already where they need to be, and what
// was lost when the daemon was killed is only the knowledge of that. This file is that
// knowledge, on disk, beside the archive it belongs to.
//
// It is written after every part rather than at the end. A journal that recorded progress in
// memory and flushed at the end would be exactly as useful as no journal at all in the one
// case it exists for.
//
// It holds no credentials. The upload id is a handle the store issued, useless without the
// keys, and the keys arrive with each command and are never written down (backup.proto,
// S3Destination.access_key_id).

const journalSuffix = ".upload.json"

// journalPart is one part that made it.
type journalPart struct {
	Number int    `json:"number"`
	ETag   string `json:"etag"`
	Size   int64  `json:"size"`
}

// journalRecord is the file's contents.
type journalRecord struct {
	// Key and Size identify the archive this progress belongs to. A resumed run whose
	// archive differs in either - because the snapshot was taken again - resets rather than
	// finishing an upload of bytes nobody has any more.
	Key      string        `json:"key"`
	Size     int64         `json:"size"`
	UploadID string        `json:"upload_id"`
	Parts    []journalPart `json:"parts"`
}

// uploadJournal is the record plus the file it lives in.
type uploadJournal struct {
	path   string
	record journalRecord
}

// openJournal loads a run's journal, or produces an empty one.
//
// A file that will not parse is treated as absent. The cost of being wrong in that direction
// is one upload done twice; the cost of the other direction is a backup that refuses to run
// because a small JSON file got truncated.
func openJournal(stateDir, runID string) (*uploadJournal, error) {
	path, err := workPath(stateDir, runID, journalSuffix)
	if err != nil {
		return nil, err
	}
	journal := &uploadJournal{path: path}

	encoded, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return journal, nil
	}
	if err != nil {
		return nil, fmt.Errorf("backup: read the upload journal %s: %w", path, err)
	}
	if err := json.Unmarshal(encoded, &journal.record); err != nil {
		journal.record = journalRecord{}
	}
	return journal, nil
}

// begin declares which archive is being uploaded, and forgets any progress that belonged to a
// different one.
func (j *uploadJournal) begin(key string, size int64) error {
	if j.record.Key == key && j.record.Size == size {
		return nil
	}
	j.record = journalRecord{Key: key, Size: size}
	return j.save()
}

func (j *uploadJournal) uploadID() string { return j.record.UploadID }

func (j *uploadJournal) setUploadID(id string) error {
	j.record.UploadID = id
	j.record.Parts = nil
	return j.save()
}

// done reports whether a part of exactly this size has already been accepted.
//
// The size is checked as well as the number because the part layout changes if the archive
// does, and completing an upload with a part from a different archive produces an object that
// is the right length and is not the backup.
func (j *uploadJournal) done(number int, size int64) (journalPart, bool) {
	for _, part := range j.record.Parts {
		if part.Number == number {
			return part, part.Size == size
		}
	}
	return journalPart{}, false
}

// record adds a part that the store has accepted, and flushes immediately.
func (j *uploadJournal) add(part journalPart) error {
	for i, existing := range j.record.Parts {
		if existing.Number == part.Number {
			j.record.Parts[i] = part
			return j.save()
		}
	}
	j.record.Parts = append(j.record.Parts, part)
	return j.save()
}

// parts is every accepted part in ascending order, which is the order a complete call has to
// list them in.
func (j *uploadJournal) parts() []journalPart {
	sorted := append([]journalPart(nil), j.record.Parts...)
	sort.Slice(sorted, func(a, b int) bool { return sorted[a].Number < sorted[b].Number })
	return sorted
}

// reset forgets the upload id and every part, for the case where the store no longer has
// them.
func (j *uploadJournal) reset() error {
	j.record.UploadID = ""
	j.record.Parts = nil
	return j.save()
}

// discard removes the file. Called once the upload has completed, because a journal that
// outlives its upload is a resume that would try to add parts to an object that already
// exists.
func (j *uploadJournal) discard() error {
	j.record = journalRecord{}
	if err := os.Remove(j.path); err != nil && !errors.Is(err, os.ErrNotExist) {
		return fmt.Errorf("backup: remove the upload journal %s: %w", j.path, err)
	}
	return nil
}

// save writes the journal through a temporary file and a rename, so a crash during the write
// leaves the previous journal rather than half of a new one.
func (j *uploadJournal) save() error {
	if err := makeDirectory(filepath.Dir(j.path)); err != nil {
		return err
	}
	encoded, err := json.Marshal(j.record)
	if err != nil {
		return fmt.Errorf("backup: encode the upload journal: %w", err)
	}
	temporary := j.path + partialSuffix
	if err := os.WriteFile(temporary, encoded, fileMode); err != nil {
		return fmt.Errorf("backup: write the upload journal %s: %w", temporary, err)
	}
	if err := os.Rename(temporary, j.path); err != nil {
		os.Remove(temporary)
		return fmt.Errorf("backup: publish the upload journal %s: %w", j.path, err)
	}
	return nil
}
