package files

import (
	"context"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/rpc"
	"github.com/furimeo/wisper/sasayaki/internal/state"
)

// What this package needs from the rest of the daemon, declared here by the consumer.
//
// There is exactly one collaborator: the node's SQLite. Everything else a file operation
// touches is the filesystem, which is not abstracted - a fake filesystem would prove that
// this package can traverse a fake, and the whole point of the code below is what happens
// on a real one, where symlinks, hard links and O_EXCL mean something.

// Store is the node's memory, as the file manager needs it.
//
// Two jobs, and they are unrelated to each other. It holds the NodeSpec, which is the only
// list of roots this node will honour: a root id the panel has not published is refused
// even when the directory it would name exists. And it holds resumable upload sessions,
// so a phone that lost signal in a lift can ask what arrived and carry on from the first
// gap, across a daemon restart if it took that long.
type Store interface {
	// LoadSpec carries FileRoots and RetentionPolicy. state.ErrNoSpec means the panel has
	// never spoken to this node, which is answered with "unknown root" rather than a
	// failure: a node with no spec has no roots, and that is a complete answer.
	LoadSpec(ctx context.Context) (state.StoredSpec, error)
	// SpecGeneration is the cheap half of LoadSpec, and is what lets a two-gigabyte upload
	// avoid decoding the whole spec once per four-megabyte chunk (roots.go).
	SpecGeneration(ctx context.Context) (uint64, error)

	// BeginUpload opens a session, or returns the one already open under that id. Every
	// chunk repeats the session description, so the second call is the normal case.
	BeginUpload(ctx context.Context, session state.UploadSession) (state.UploadSession, error)
	Upload(ctx context.Context, sessionID string) (state.UploadSession, error)
	UploadProgress(ctx context.Context, sessionID string) (state.UploadProgress, error)
	RecordChunk(ctx context.Context, sessionID string, received state.ByteRange, at time.Time) (state.UploadProgress, error)
	// CompleteUpload refuses a session with a hole in it, which is the check that stops a
	// truncated file from being declared whole.
	CompleteUpload(ctx context.Context, sessionID string, at time.Time) error
	// ForgetUpload drops the record and returns what it was, so the parts file it names can
	// be deleted. The bytes are this package's to remove; the record is not.
	ForgetUpload(ctx context.Context, sessionID string) (state.UploadSession, error)
	ExpiredUploads(ctx context.Context, now time.Time) ([]state.UploadSession, error)
}

// The one implementation, asserted here rather than discovered in the composition root.
// Store is written in the state package's own signatures precisely so that *state.Store
// satisfies it with no adapter; this line is what keeps that true when either side moves.
var _ Store = (*state.Store)(nil)

// The contract with the control stream, checked by the compiler. rpc.FileHost is declared
// by its consumer, Go style, and implemented here.
var _ rpc.FileHost = (*Host)(nil)
