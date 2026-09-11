package files

import (
	"context"
	"errors"
	"io/fs"
	"log/slog"
	"os"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
	"github.com/furimeo/wisper/sasayaki/internal/state"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Turning a root id into a directory that can be operated in, which is the first thing
// every request does and the last chance to refuse one cheaply.
//
// The list of roots is the NodeSpec's, not this node's filesystem: a directory that exists
// but is not published is unreachable, because the panel is the side that decides what a
// customer owns. That is also why nothing here falls back to a default root - resolving an
// unrecognised id by guessing is the first half of a path traversal.

// openRoot is a file root with its directory open.
//
// The *os.Root is the security boundary (doc.go): on Linux it is a directory file
// descriptor used with openat2 and RESOLVE_BENEATH, so a name that leaves the tree is
// refused by the kernel rather than by a string comparison. It is opened per request and
// closed with it - caching the handle would pin a volume directory that a reconcile pass
// has since deleted, and the file manager would keep writing into a tree nobody can see.
type openRoot struct {
	id         string
	label      string
	writable   bool
	quotaBytes int64
	// directory is the absolute path, for the daemon's own log. It never travels to the
	// panel: the panel does not learn where a root is, only that it has one.
	directory string
	root      *os.Root
}

func (r *openRoot) Close() {
	if r.root != nil {
		r.root.Close()
	}
}

// requireWritable refuses a change to a root the panel published read-only.
//
// The site root is the case that matters: an edit made in place would be silently reverted
// by the next deployment, which is worse for a customer than not being able to make it
// (docs/contracts/node-spec.md section 3.10).
func (r *openRoot) requireWritable(path string) *failure {
	if r.writable {
		return nil
	}
	return refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_PERMISSION_DENIED, path,
		"%q is read-only: the panel published this root as one a customer may look at but "+
			"not change", r.label)
}

// open resolves a root id against the current spec and opens its directory.
func (h *Host) open(ctx context.Context, rootID string) (*openRoot, *failure) {
	published, err := h.currentSpec(ctx)
	if err != nil {
		return nil, classify(ctx, err, "", "read the node's desired state")
	}

	root, known := published.FileRoot(rootID)
	if !known {
		return nil, refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_UNKNOWN_ROOT, "",
			"this node has no file root %q at generation %d", rootID, published.Generation)
	}

	directory, resolveErr := h.rootDirectory(root)
	if resolveErr != nil {
		return nil, refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_UNKNOWN_ROOT, "",
			"file root %q cannot be resolved on this node: %v", rootID, resolveErr)
	}

	// The staging root belongs to the node, so this package creates it. A volume belongs
	// to the runtime and a site's releases to the builder, and creating one of those here
	// would hide a workload that has not converged behind an empty directory that looks
	// like an empty disk.
	if root.Kind == spec.FileRootUploadStaging {
		if err := os.MkdirAll(directory, nodeDirectoryMode); err != nil {
			return nil, classify(ctx, err, "", "prepare the upload staging directory")
		}
	}

	handle, err := os.OpenRoot(directory)
	if err != nil {
		if errors.Is(err, fs.ErrNotExist) {
			return nil, refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_NOT_FOUND, "",
				"file root %q is not on this node's disk yet: the workload it belongs to has "+
					"not been created here", rootID)
		}
		return nil, classify(ctx, err, "", "open the file root")
	}

	return &openRoot{
		id:         root.ID,
		label:      root.Label,
		writable:   root.Writable,
		quotaBytes: root.QuotaBytes,
		directory:  directory,
		root:       handle,
	}, nil
}

// rootDirectory is where each kind of root lives (layout.go).
func (h *Host) rootDirectory(root spec.FileRoot) (string, error) {
	switch root.Kind {
	case spec.FileRootVolume:
		return volumeDirectory(h.stateDir, root.WorkloadID, root.VolumeID)
	case spec.FileRootSite:
		return siteReleasesDirectory(h.stateDir, root.WorkloadID)
	case spec.FileRootUploadStaging:
		return stagingRoot(h.stateDir), nil
	default:
		return "", errors.New("this binary does not know that kind of file root, and resolving " +
			"one by guessing is how a path traversal starts")
	}
}

// currentSpec is the published document, reloaded only when the generation moves.
//
// Reading the generation is one row of one table and no protobuf decoding, which is what
// makes it affordable on every chunk of an upload. A spec resent under the same generation
// with different bytes is possible - the panel resends the whole document on every
// reconnect - and is deliberately not reloaded here: generations are strictly increasing
// per node, so the only way two documents share one is a resend of the same intent.
func (h *Host) currentSpec(ctx context.Context) (spec.Spec, error) {
	generation, err := h.store.SpecGeneration(ctx)
	if errors.Is(err, state.ErrNoSpec) {
		// A freshly enrolled node. It has no roots, and that is a complete answer rather
		// than a failure: every request against it is refused with UNKNOWN_ROOT.
		return spec.Spec{}, nil
	}
	if err != nil {
		return spec.Spec{}, err
	}

	h.cached.Lock()
	defer h.cached.Unlock()
	if h.cached.loaded && h.cached.generation == generation {
		return h.cached.spec, nil
	}

	stored, err := h.store.LoadSpec(ctx)
	if errors.Is(err, state.ErrNoSpec) {
		return spec.Spec{}, nil
	}
	if err != nil {
		return spec.Spec{}, err
	}

	h.cached.spec = spec.FromProto(stored.Spec)
	h.cached.generation = stored.Generation
	h.cached.loaded = true
	return h.cached.spec, nil
}

// retention is the panel's sweep policy, which the upload sweeper needs and which arrives
// on the same document as the roots.
func (h *Host) retention(ctx context.Context) spec.Retention {
	published, err := h.currentSpec(ctx)
	if err != nil {
		h.log.Warn("could not read the retention policy; using the built-in one",
			slog.String("error", err.Error()))
		return spec.Retention{}
	}
	return published.Retention
}
