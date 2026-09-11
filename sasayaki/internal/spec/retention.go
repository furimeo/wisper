package spec

import (
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Retention is what the node keeps and what it sweeps.
//
// On the node rather than the panel because the node is the only side that knows how much
// disk is actually left. One policy for the whole machine: the wire has no room for a
// second, and the panel sends the largest figure any site here is entitled to rather than
// the smallest, because deleting a release a customer was promised they could roll back to
// is worse than holding a few extra directories.
type Retention struct {
	// Static site releases kept for instant rollback. Rollback is pointing the `current`
	// symlink at an older one, so this number is literally how far back a customer can go.
	KeepReleases int32
	// Build workspaces (checkout, node_modules, cache) kept after a build finishes. Zero
	// deletes them immediately; a few make the next build much faster.
	KeepBuildWorkspaces int32
	// Docker json-file log rotation. Unrotated container logs are the classic way a node
	// fills its disk overnight.
	ContainerLogMaxBytes int64
	ContainerLogMaxFiles int32
	// Half-finished chunked uploads are swept after this. A phone that lost signal
	// mid-upload leaves parts behind and nobody comes back for them.
	OrphanUploadTTL time.Duration
}

func retentionFromProto(message *wisperpb.RetentionPolicy) Retention {
	return Retention{
		KeepReleases:         message.GetKeepReleases(),
		KeepBuildWorkspaces:  message.GetKeepBuildWorkspaces(),
		ContainerLogMaxBytes: message.GetContainerLogMaxBytes(),
		ContainerLogMaxFiles: message.GetContainerLogMaxFiles(),
		OrphanUploadTTL:      seconds(message.GetOrphanUploadTtlSeconds()),
	}
}
