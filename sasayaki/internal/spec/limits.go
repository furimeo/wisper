package spec

import "github.com/furimeo/wisper/sasayaki/internal/wisperpb"

// Limits are the hard ceilings a workload runs under, applied through cgroups v2 by way of
// the Docker Engine API.
//
// Every figure is in the unit the Engine API itself takes, so nothing between here and the
// container config multiplies anything.
type Limits struct {
	// Docker's NanoCPUs: 1000000000 is one full core, 500000000 is half of one.
	//
	// This is NOT CPUShares multiplied by a thousand. CPUShares is a relative scheduling
	// weight; this is a ceiling. The predecessor conflated the two, so every container got
	// a weight where a limit was intended and one busy workload could take the whole
	// machine while the panel showed it politely limited (design section 9).
	NanoCPUs    int64
	MemoryBytes int64
	// Docker's memory+swap total, so it is always >= MemoryBytes. Equal disables swap; -1
	// is unlimited. The panel sends it equal, because a container that swaps makes the
	// whole machine grind while one that is killed fails a single workload.
	MemorySwapBytes int64
	// A fork bomb inside gVisor is still a fork bomb for the host's process table.
	PidsLimit int64
	// XFS project quota across this workload's volumes. Enforced by the filesystem, not by
	// polling - which is why the storage layout had to be decided before any code was
	// written (design section 11.1). Advisory on a filesystem without project quotas, and
	// the doctor report is what says which this node has.
	DiskBytes int64
	// RLIMIT_NOFILE. Node and Go servers open a lot of sockets, and the distribution
	// default is low enough that running out looks like a networking bug.
	NofileLimit int64
}

// MountKind is what a mount is backed by.
type MountKind string

const (
	// MountKindUnknown is a mount this binary cannot resolve. The reconciler must refuse to
	// start the workload rather than start it without the mount: an application that comes
	// up with an empty data directory writes into it.
	MountKindUnknown MountKind = "UNKNOWN"
	// MountKindVolume is a persistent directory under the node's state root, quota-managed
	// and backed up. What the customer thinks of as their disk.
	MountKindVolume MountKind = "VOLUME"
	// MountKindSiteRelease is a site's current release, read-only. It lets an app serve or
	// post-process what a build produced without a second copy of it.
	MountKindSiteRelease MountKind = "SITE_RELEASE"
	// MountKindTmpfs is RAM-backed scratch: the cheap way to give a read-only rootfs
	// somewhere to write.
	MountKindTmpfs MountKind = "TMPFS"
)

// Mount is one directory inside a container.
type Mount struct {
	// Identifies the directory to the node, which resolves it under its own state root.
	// The panel never sends a filesystem path, and this field is never joined onto one
	// without being checked first: a path from the network reaching a filesystem call is
	// the bug class this whole platform is built to avoid.
	VolumeID string
	Kind     MountKind
	// Absolute path inside the container.
	Target   string
	ReadOnly bool
	// Per-volume ceiling, separate from Limits.DiskBytes so one workload can have a large
	// data volume and a small log volume.
	QuotaBytes int64
	// MountKindTmpfs only. An unbounded tmpfs is memory exhaustion with extra steps.
	TmpfsBytes int64
}

// Protocol is a published port's transport.
type Protocol string

const (
	// ProtocolTCP is also what an unspecified protocol becomes: it is Docker's own default
	// and the only value the panel sends, so treating the absence as TCP cannot surprise
	// anybody.
	ProtocolTCP Protocol = "TCP"
	ProtocolUDP Protocol = "UDP"
)

// Port is a container port, optionally published on the host.
//
// Publishing is the exception. HTTP arrives through the embedded Caddy, which reaches
// containers over the tenant network and needs no host port; this exists for the workload
// that genuinely wants a raw port, such as a game server.
type Port struct {
	Container uint32
	// Zero means do not publish. Routes still work.
	Host     uint32
	Protocol Protocol
	// Bind address on the host. "127.0.0.1" keeps a published port off the public
	// interface, which is what a database or an admin port wants.
	HostIP string
}

// IsPublished reports whether this binding asks for a port on the host.
func (p Port) IsPublished() bool { return p.Host != 0 }

func limitsFromProto(message *wisperpb.ResourceLimits) Limits {
	return Limits{
		NanoCPUs:        message.GetNanoCpus(),
		MemoryBytes:     message.GetMemoryBytes(),
		MemorySwapBytes: message.GetMemorySwapBytes(),
		PidsLimit:       message.GetPidsLimit(),
		DiskBytes:       message.GetDiskBytes(),
		NofileLimit:     message.GetNofileLimit(),
	}
}

func mountFromProto(message *wisperpb.Mount) Mount {
	return Mount{
		VolumeID:   message.GetVolumeId(),
		Kind:       mountKindFromProto(message.GetKind()),
		Target:     message.GetTarget(),
		ReadOnly:   message.GetReadOnly(),
		QuotaBytes: message.GetQuotaBytes(),
		TmpfsBytes: message.GetTmpfsBytes(),
	}
}

func mountKindFromProto(value wisperpb.MountKind) MountKind {
	switch value {
	case wisperpb.MountKind_MOUNT_KIND_VOLUME:
		return MountKindVolume
	case wisperpb.MountKind_MOUNT_KIND_SITE_RELEASE:
		return MountKindSiteRelease
	case wisperpb.MountKind_MOUNT_KIND_TMPFS:
		return MountKindTmpfs
	default:
		return MountKindUnknown
	}
}

func portFromProto(message *wisperpb.PortBinding) Port {
	protocol := ProtocolTCP
	if message.GetProtocol() == wisperpb.PortProtocol_PORT_PROTOCOL_UDP {
		protocol = ProtocolUDP
	}
	return Port{
		Container: message.GetContainerPort(),
		Host:      message.GetHostPort(),
		Protocol:  protocol,
		HostIP:    message.GetHostIp(),
	}
}
