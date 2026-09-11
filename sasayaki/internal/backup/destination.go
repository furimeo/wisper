package backup

import (
	"context"
	"fmt"
	"io"
	"strings"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Where an archive goes, and the two places it can go.
//
// One interface with two implementations rather than an S3 client with a "local" flag on it.
// The two share nothing in their mechanics - one is HTTP with a signature over every request
// and a three-call protocol for anything large, the other is a rename - and they share
// everything in their contract: a key, bytes, a listing, a deletion. Retention, verification
// and restore are written against the contract and have no idea which one they are talking
// to, which is why the retention tests can be arithmetic over a fake and still be about the
// real thing.

// storedObject is one thing at the destination, as a listing reports it.
type storedObject struct {
	Key  string
	Size int64
}

// Destination is an object store, or a directory pretending to be one.
type Destination interface {
	// Upload writes size bytes at key, reading them through body.
	//
	// journal is where an interrupted attempt left its progress, and is updated as the upload
	// proceeds. An implementation that cannot resume - a local copy, where re-copying costs
	// nothing anybody notices - is free to ignore it and start again.
	Upload(ctx context.Context, key string, body io.ReaderAt, size int64, journal *uploadJournal) error

	// Abandon throws away what an unfinished upload left at the destination.
	//
	// Called only for a run that has failed and been reported as failed, never for one that
	// was interrupted: the interrupted one is going to be resumed, and the parts it left are
	// the reason resuming is quick. An incomplete multipart upload is invisible in a bucket
	// listing and billed like an object, so leaving them all behind would be a slow leak
	// nobody can see.
	Abandon(ctx context.Context, key string, journal *uploadJournal)

	// Put writes a small object in a single request. Used for the digest sidecar, which is
	// eighty bytes and must land as one atomic thing.
	Put(ctx context.Context, key string, body []byte) error

	// Get opens an object. The caller closes it.
	Get(ctx context.Context, key string) (io.ReadCloser, error)

	// List is every object whose key starts with prefix. Used by retention, which is the one
	// operation that decides what to delete, so a listing that silently truncated would
	// delete the wrong generations - an implementation must follow pagination to the end or
	// return an error.
	List(ctx context.Context, prefix string) ([]storedObject, error)

	// Delete removes one object. A key that is not there is success: retention runs again
	// after a partial failure and must converge rather than fail forever on a deletion that
	// already happened.
	Delete(ctx context.Context, key string) error
}

// There is deliberately no Describe. BackupCompleted.location is "the S3 key or the local
// relative path" and RestoreBackup.location is the same string sent back, so decorating it
// with a scheme and a bucket would produce a handle the restore path then had to undecorate -
// and the bucket a restore reads from arrives with the command anyway, because credentials
// rotate. The key is the location.

// resolveDestination turns what the panel sent into something that can be written to.
//
// Credentials arrive with each command and are never written to the node's SQLite, which is
// what backup.proto says and what stops a customer's object store keys from surviving in
// /var/lib/wisper after an uninstall. They live in the returned value for the length of one
// backup.
func (r *Runner) resolveDestination(destination *wisperpb.BackupDestination) (Destination, string, error) {
	if destination == nil {
		return nil, "", fmt.Errorf("backup: the command carries no destination, so there is nowhere to put the archive")
	}

	switch destination.GetKind() {
	case wisperpb.DestinationKind_DESTINATION_KIND_LOCAL:
		prefix := strings.Trim(strings.TrimSpace(destination.GetLocalPrefix()), "/")
		if prefix != "" {
			if err := checkKey(prefix); err != nil {
				return nil, "", err
			}
		}
		return newLocalDestination(r.stateDir), prefix, nil

	case wisperpb.DestinationKind_DESTINATION_KIND_S3:
		settings := destination.GetS3()
		store, err := newS3Destination(settings, r.http, r.retry, r.partSize, r.log)
		if err != nil {
			return nil, "", err
		}
		prefix := strings.Trim(strings.TrimSpace(settings.GetPrefix()), "/")
		if prefix != "" {
			if err := checkKey(prefix); err != nil {
				return nil, "", err
			}
		}
		return store, prefix, nil

	default:
		return nil, "", fmt.Errorf("backup: destination kind %s is not one this node writes to",
			destination.GetKind())
	}
}
