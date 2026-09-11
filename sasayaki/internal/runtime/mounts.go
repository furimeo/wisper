package runtime

import (
	"context"
	"fmt"
	"path"
	"strings"

	"github.com/moby/moby/api/types/mount"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
)

// defaultTmpfsBytes bounds a tmpfs the panel did not size.
//
// Never unbounded: a tmpfs with no size is the container's memory limit's problem right
// up until the moment it is the machine's. Sixty-four megabytes is enough for the thing
// tmpfs is actually for here - somewhere for a read-only rootfs to write a pid file, a
// socket or a cache - and a workload that needs more gets it from the spec.
const defaultTmpfsBytes int64 = 64 << 20

// mountsFor turns the spec's id-based mounts into the engine's path-based ones.
//
// This is the only function in wisper where something from the panel becomes a path on
// the node's disk, and it is written to make the dangerous version impossible rather than
// unlikely:
//
//   - the source is never taken from the spec. It is built from the state root and an id
//     that checkIdentifier has already refused if it contains a separator or a dot
//     segment, so there is no string a panel - or anything that has compromised one -
//     could send that resolves outside <state>/volumes or <state>/sites. `docker.sock`
//     is not blocklisted here because it is not reachable: there is no field to ask for
//     it in;
//   - a mount kind this binary does not know refuses the whole workload rather than being
//     skipped. An application that comes up with an empty data directory writes into it,
//     and by the time anybody notices the real volume is a week behind;
//   - a site release is read-only whatever the spec says. The next deployment replaces
//     it, so an edit made in place is silently reverted, which is worse than an edit that
//     is refused.
func (d *Docker) mountsFor(ctx context.Context, workload spec.Workload) ([]mount.Mount, error) {
	mounts := make([]mount.Mount, 0, len(workload.Mounts))
	seen := make(map[string]struct{}, len(workload.Mounts))

	for _, wanted := range workload.Mounts {
		target, err := containerPath(wanted.Target)
		if err != nil {
			return nil, fmt.Errorf("runtime: workload %s mount %s: %w", workload.ID, wanted.VolumeID, err)
		}
		if _, duplicate := seen[target]; duplicate {
			return nil, fmt.Errorf("runtime: workload %s asks for two mounts at %s, and the "+
				"second would silently hide the first", workload.ID, target)
		}
		seen[target] = struct{}{}

		switch wanted.Kind {
		case spec.MountKindVolume:
			source, err := d.volumePath(workload.ID, wanted.VolumeID)
			if err != nil {
				return nil, err
			}
			if err := ensureVolume(source); err != nil {
				return nil, err
			}
			if err := d.applyQuota(ctx, source, quotaFor(wanted, workload.Limits)); err != nil {
				return nil, err
			}
			mounts = append(mounts, bind(source, target, wanted.ReadOnly))

		case spec.MountKindSiteRelease:
			source, err := d.sitePath(wanted.VolumeID)
			if err != nil {
				return nil, err
			}
			mounts = append(mounts, bind(source, target, true))

		case spec.MountKindTmpfs:
			mounts = append(mounts, mount.Mount{
				Type:         mount.TypeTmpfs,
				Target:       target,
				TmpfsOptions: &mount.TmpfsOptions{SizeBytes: tmpfsBytes(wanted, workload.Limits)},
			})

		default:
			return nil, fmt.Errorf("runtime: workload %s asks for a %s mount at %s, which this "+
				"version of sasayaki does not understand; the workload is left alone rather "+
				"than started without its data", workload.ID, wanted.Kind, target)
		}
	}
	return mounts, nil
}

// bind is one host directory inside a container.
//
// CreateMountpoint matters for a read-only rootfs: without it the engine cannot make the
// directory the mount goes over, and the container fails to start with an error that
// blames the image.
func bind(source, target string, readOnly bool) mount.Mount {
	return mount.Mount{
		Type:        mount.TypeBind,
		Source:      source,
		Target:      target,
		ReadOnly:    readOnly,
		BindOptions: &mount.BindOptions{CreateMountpoint: true},
	}
}

// containerPath checks where inside a container a mount is allowed to land.
//
// Absolute, cleaned, and not the root itself. Mounting over / replaces the image with an
// empty directory, which presents to a customer as "my application vanished".
func containerPath(target string) (string, error) {
	if target == "" {
		return "", fmt.Errorf("the target path is empty")
	}
	if !strings.HasPrefix(target, "/") {
		return "", fmt.Errorf("the target %q is not absolute", target)
	}
	cleaned := path.Clean(target)
	if cleaned == "/" {
		return "", fmt.Errorf("the target is /, which would replace the image's filesystem")
	}
	if cleaned != target && cleaned+"/" != target {
		return "", fmt.Errorf("the target %q is not in canonical form (it means %q)", target, cleaned)
	}
	return cleaned, nil
}

// quotaFor is the ceiling to put on one volume: its own if it has one, otherwise the
// workload's whole-disk figure. Per-volume first, because a workload with a large data
// volume and a small log volume is exactly why the two numbers are separate.
func quotaFor(wanted spec.Mount, limits spec.Limits) int64 {
	if wanted.QuotaBytes > 0 {
		return wanted.QuotaBytes
	}
	return limits.DiskBytes
}

// tmpfsBytes is how big a scratch filesystem may get.
func tmpfsBytes(wanted spec.Mount, limits spec.Limits) int64 {
	if wanted.TmpfsBytes > 0 {
		return wanted.TmpfsBytes
	}
	// A tmpfs page is charged to the cgroup of whatever wrote it, so a default that
	// cannot exceed the container's own memory ceiling cannot hurt anything but the
	// container that filled it.
	if limits.MemoryBytes > 0 && limits.MemoryBytes < defaultTmpfsBytes {
		return limits.MemoryBytes
	}
	return defaultTmpfsBytes
}
