package backup

import (
	"fmt"
	"path"
	"strings"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// What an archive is called at the destination, and how to read that name back.
//
// The name carries the time because the retention rule is expressed in days, weeks and
// months and the alternative - trusting an object store's own modification timestamp - is
// wrong twice over: a copy or a lifecycle transition rewrites it, and a local directory
// destination loses it to any tool that touches the file. A name that encodes its own
// generation means the same retention decision comes out of an S3 bucket, a MinIO instance
// and a directory on the node, and it means an operator reading a bucket listing can see
// what they have without a panel.
//
//	<prefix>/<subject-id>/20260911T100000Z-<backup-id>.tar.gz
//	<prefix>/<subject-id>/20260911T100000Z-<backup-id>.tar.gz.sha256
//
// Subject first, so listing one subject's history is a prefix query rather than a scan of
// every backup in the bucket - which matters at the point a customer with four volumes shares
// a bucket with two hundred others.
const (
	// stampLayout is RFC 3339 with the punctuation removed, so the name sorts
	// lexicographically in the order it was taken and needs no escaping in a URL.
	stampLayout = "20060102T150405Z"

	// volumeExtension and databaseExtension say what is inside without opening it. Both are
	// gzip, and both keep the inner format visible: a tar of a directory tree, or one file
	// of SQL.
	volumeExtension   = ".tar.gz"
	databaseExtension = ".sql.gz"

	// rollbackExtension names the dump a restore takes of the database it is about to
	// overwrite. Distinct from databaseExtension so the two never share a staging name inside
	// one restore, which stages both.
	rollbackExtension = ".pre.sql.gz"

	// digestSuffix names the sidecar holding the archive's SHA-256 and length.
	//
	// A sidecar rather than object metadata because there are two destinations and metadata
	// exists at one of them. One mechanism means the restore path has one answer to "what
	// should this hash to", and a restore that cannot find that answer refuses rather than
	// trusting the bytes it was handed.
	digestSuffix = ".sha256"
)

// extensionFor is what an archive of this kind is called.
func extensionFor(kind wisperpb.BackupTargetKind) string {
	if kind == wisperpb.BackupTargetKind_BACKUP_TARGET_KIND_DATABASE {
		return databaseExtension
	}
	return volumeExtension
}

// subjectPrefix is everything belonging to one volume or one database.
func subjectPrefix(prefix, subjectID string) string {
	cleaned := strings.Trim(strings.TrimSpace(prefix), "/")
	if cleaned == "" {
		return subjectID
	}
	return cleaned + "/" + subjectID
}

// objectKey is where one archive goes.
func objectKey(prefix, subjectID string, at time.Time, backupID string, kind wisperpb.BackupTargetKind) (string, error) {
	if err := checkIdentifier("subject id", subjectID); err != nil {
		return "", err
	}
	if err := checkIdentifier("backup id", backupID); err != nil {
		return "", err
	}
	key := subjectPrefix(prefix, subjectID) + "/" +
		at.UTC().Format(stampLayout) + "-" + backupID + extensionFor(kind)
	if err := checkKey(key); err != nil {
		return "", err
	}
	return key, nil
}

// digestKey is the sidecar beside an archive.
func digestKey(key string) string { return key + digestSuffix }

// restorePointID is the handle a restore is asked for by.
//
// Minted here because the node is the side that knows the final object name, which is what
// backup.proto says and what makes the id and the location two views of one fact rather than
// two things that can drift apart.
func restorePointID(at time.Time, backupID string) string {
	return "rp-" + at.UTC().Format(stampLayout) + "-" + backupID
}

// generation is one archive already at the destination, as retention sees it.
type generation struct {
	Key   string
	Taken time.Time
	Size  int64
}

// parseGeneration reads the time back out of a key, and reports false for anything this
// package did not write.
//
// Refusing to interpret a stranger's object is not fussiness. Retention deletes what it does
// not keep, and a bucket shared with something else - a customer's own tooling, a lifecycle
// rule's leftovers - must come out of a listing as "not mine" rather than as a generation
// with an unparseable date that sorts to the epoch and gets deleted first.
func parseGeneration(key string, size int64) (generation, bool) {
	name := path.Base(key)
	if strings.HasSuffix(name, digestSuffix) {
		return generation{}, false
	}

	var stem string
	switch {
	case strings.HasSuffix(name, volumeExtension):
		stem = strings.TrimSuffix(name, volumeExtension)
	case strings.HasSuffix(name, databaseExtension):
		stem = strings.TrimSuffix(name, databaseExtension)
	default:
		return generation{}, false
	}

	separator := strings.IndexByte(stem, '-')
	if separator <= 0 {
		return generation{}, false
	}
	taken, err := time.Parse(stampLayout, stem[:separator])
	if err != nil {
		return generation{}, false
	}
	return generation{Key: key, Taken: taken.UTC(), Size: size}, true
}

// describeStage is what a stage is called in a log line and in a failure detail. The enum's
// own name - BACKUP_STAGE_QUIESCE - is not what a customer should be reading.
func describeStage(stage wisperpb.BackupStage) string {
	switch stage {
	case wisperpb.BackupStage_BACKUP_STAGE_QUIESCE:
		return "quiesce"
	case wisperpb.BackupStage_BACKUP_STAGE_SNAPSHOT:
		return "snapshot"
	case wisperpb.BackupStage_BACKUP_STAGE_UPLOAD:
		return "upload"
	case wisperpb.BackupStage_BACKUP_STAGE_VERIFY:
		return "verify"
	case wisperpb.BackupStage_BACKUP_STAGE_PRUNE:
		return "prune"
	default:
		return "start"
	}
}

// dryRunDatabaseName is where a rehearsal restores a database to.
//
// The panel's own schema constrains a database name to ^[a-z][a-z0-9_]{2,62}$, so the suffix
// is built to fit inside that rather than appended and hoped for: a name that came back
// eight characters too long would be refused by the engine at the point the customer was
// watching a rehearsal they had been told was safe.
func dryRunDatabaseName(database, restoreID string) (string, error) {
	if database == "" {
		return "", fmt.Errorf("backup: a dry run needs the name of the database it is rehearsing")
	}
	suffix := "_r" + sanitiseNamePart(restoreID)
	const limit = 63
	if len(suffix) > 16 {
		suffix = suffix[:16]
	}
	stem := database
	if len(stem)+len(suffix) > limit {
		stem = stem[:limit-len(suffix)]
	}
	name := stem + suffix
	if len(name) < 3 {
		return "", fmt.Errorf("backup: %q and restore %s produce no usable rehearsal name", database, restoreID)
	}
	return name, nil
}

// sanitiseNamePart reduces an id to the characters a SQL identifier accepts. Rewriting rather
// than refusing is safe here for one reason: the result is a suffix on a name whose
// uniqueness the caller already owns, and nothing is ever parsed back out of it.
func sanitiseNamePart(value string) string {
	var out strings.Builder
	for _, character := range strings.ToLower(value) {
		switch {
		case character >= 'a' && character <= 'z', character >= '0' && character <= '9':
			out.WriteRune(character)
		default:
			out.WriteByte('_')
		}
	}
	if out.Len() == 0 {
		return "0"
	}
	return out.String()
}
