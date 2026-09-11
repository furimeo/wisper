package spec

import "github.com/furimeo/wisper/sasayaki/internal/wisperpb"

// FileRootKind is what a file root exposes.
type FileRootKind string

const (
	// FileRootUnknown is a root this binary cannot resolve. The files package answers every
	// operation against it with a refusal: resolving an unrecognised root by guessing is
	// the first half of a path traversal.
	FileRootUnknown FileRootKind = "UNKNOWN"
	// FileRootVolume is a workload volume - what the customer thinks of as their disk.
	FileRootVolume FileRootKind = "VOLUME"
	// FileRootSite is a site's releases tree, so a customer can see what a build produced.
	FileRootSite FileRootKind = "SITE"
	// FileRootUploadStaging is where the panel pushes archives before it asks for a build.
	// Not shown to customers; it exists because a node can reach the panel's gRPC endpoint
	// and nothing else, so an uploaded zip travels the same resumable path as any other
	// upload.
	FileRootUploadStaging FileRootKind = "UPLOAD_STAGING"
)

// FileRoot is a directory the file manager may operate in.
//
// This list is the entire surface a customer has on a node's filesystem: there is no SSH,
// no SFTP and no WebDAV (design section 8.2). Every operation names a root by id and a path
// relative to it, and the node resolves the root itself and rejects anything that climbs
// out of it. Path traversal is the largest attack surface in v1, and this shape is what
// makes it checkable in one place instead of at every call site.
type FileRoot struct {
	ID   string
	Kind FileRootKind
	// The workload this root belongs to. Empty for the staging root, which belongs to the
	// node.
	WorkloadID string
	// Which mount of that workload this exposes. Empty for a site or staging root.
	VolumeID string
	// What the file manager shows in its breadcrumb.
	Label string
	// False for the releases of a static site: a customer editing a built artifact in place
	// would have it silently reverted by the next deployment.
	Writable   bool
	QuotaBytes int64
}

func fileRootFromProto(message *wisperpb.FileRoot) FileRoot {
	return FileRoot{
		ID:         message.GetId(),
		Kind:       fileRootKindFromProto(message.GetKind()),
		WorkloadID: message.GetWorkloadId(),
		VolumeID:   message.GetVolumeId(),
		Label:      message.GetLabel(),
		Writable:   message.GetWritable(),
		QuotaBytes: message.GetQuotaBytes(),
	}
}

func fileRootKindFromProto(value wisperpb.FileRootKind) FileRootKind {
	switch value {
	case wisperpb.FileRootKind_FILE_ROOT_KIND_VOLUME:
		return FileRootVolume
	case wisperpb.FileRootKind_FILE_ROOT_KIND_SITE:
		return FileRootSite
	case wisperpb.FileRootKind_FILE_ROOT_KIND_UPLOAD_STAGING:
		return FileRootUploadStaging
	default:
		return FileRootUnknown
	}
}
