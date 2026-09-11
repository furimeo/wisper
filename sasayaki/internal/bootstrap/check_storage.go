package bootstrap

import (
	"context"
	"fmt"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// How much room a node needs before it is worth giving work to. A Docker image layer set
// and one release of a customer's site is several gigabytes on its own, and a node that
// fills up stops being able to deploy rather than merely being slow.
const (
	minimumFreeBytes     = 10 << 30
	comfortableFreeBytes = 25 << 30
)

// checkStorage looks at the filesystem the state directory will live on.
//
// This is the check that has to be got right before anything is deployed, because the
// answer cannot be changed afterwards without moving customer data: only a filesystem
// with project quotas can enforce a per-volume disk limit, and getting it wrong means the
// panel showing limits it has no way to apply (design section 11.1).
func checkStorage(_ context.Context, m *machine, facts *wisperpb.MachineFacts) []*wisperpb.DoctorCheck {
	// The state directory does not exist during a first install - doctor runs before
	// anything is written - so what is judged is the filesystem that will hold it.
	target := nearestExistingAncestor(m.stateDir)

	mount, found := mountFor(m, target)
	facts.StateFilesystem = mount.filesystem
	facts.ProjectQuotaSupported = mount.projectQuota

	checks := []*wisperpb.DoctorCheck{filesystemCheck(m.stateDir, target, mount, found)}

	total, free, err := m.diskSpace(target)
	if err != nil {
		return append(checks, check("storage.free", "Free disk space", severityRequired,
			outcomeWarn, fmt.Sprintf("the filesystem holding %s could not be measured: %v",
				target, err),
			"Check the path exists and is on a mounted filesystem."))
	}
	facts.DiskTotalBytes = total
	facts.DiskFreeBytes = free

	return append(checks, freeSpaceCheck(target, total, free))
}

func filesystemCheck(stateDir, target string, mount mountPoint, found bool) *wisperpb.DoctorCheck {
	where := stateDir
	if stateDir != target {
		where = fmt.Sprintf("%s (not created yet; judging %s)", stateDir, target)
	}

	if !found {
		return check("storage.filesystem", "State directory filesystem", severityAdvisory,
			outcomeWarn,
			fmt.Sprintf("the filesystem under %s could not be identified from "+
				"/proc/self/mountinfo, so it is not known whether disk quotas can be "+
				"enforced", where),
			"Check /proc is mounted. Until the filesystem is known the panel will show "+
				"volume limits as advisory rather than enforced.")
	}

	switch {
	case mount.filesystem == "xfs" && mount.projectQuota:
		return passed("storage.filesystem", "State directory filesystem", severityAdvisory,
			fmt.Sprintf("%s: xfs mounted %s at %s, so volume limits are enforced",
				where, mount.quotaOption, mount.point))

	case mount.filesystem == "xfs":
		return check("storage.filesystem", "State directory filesystem", severityAdvisory,
			outcomeWarn,
			fmt.Sprintf("%s: xfs at %s, but it is not mounted with prjquota, so a volume "+
				"limit cannot be enforced and one customer can fill the node", where, mount.point),
			"Add prjquota to the mount options for "+mount.point+" in /etc/fstab and "+
				"remount. Doing this after customers have data here means moving that data.")

	case mount.filesystem == "ext4" && mount.projectQuota:
		return check("storage.filesystem", "State directory filesystem", severityAdvisory,
			outcomeWarn,
			fmt.Sprintf("%s: ext4 mounted %s at %s. Project quotas work here, but wisper is "+
				"tested on xfs and enforcement on ext4 is best-effort", where,
				mount.quotaOption, mount.point),
			"Prefer a separate xfs filesystem with prjquota for "+stateDir+".")

	case mount.filesystem == "ext4":
		return check("storage.filesystem", "State directory filesystem", severityAdvisory,
			outcomeWarn,
			fmt.Sprintf("%s: ext4 at %s with no project quota. DISK LIMITS CANNOT BE "+
				"ENFORCED on this node - the panel will record a volume's size limit and "+
				"nothing will stop a customer going past it", where, mount.point),
			"Give "+stateDir+" its own xfs filesystem mounted with prjquota. Changing this "+
				"after customers have data on the node means moving that data.")

	default:
		return check("storage.filesystem", "State directory filesystem", severityAdvisory,
			outcomeWarn,
			fmt.Sprintf("%s: %s at %s. DISK LIMITS CANNOT BE ENFORCED on this filesystem",
				where, mount.filesystem, mount.point),
			"Give "+stateDir+" its own xfs filesystem mounted with prjquota.")
	}
}

func freeSpaceCheck(target string, total, free int64) *wisperpb.DoctorCheck {
	detail := fmt.Sprintf("%s free of %s on the filesystem holding %s",
		formatBytes(free), formatBytes(total), target)

	switch {
	case free < minimumFreeBytes:
		return check("storage.free", "Free disk space", severityRequired, outcomeFail,
			detail+fmt.Sprintf(", below the %s a node needs for images, builds and releases",
				formatBytes(minimumFreeBytes)),
			"Free space or attach a larger disk before enrolling this node.")
	case free < comfortableFreeBytes:
		return check("storage.free", "Free disk space", severityRequired, outcomeWarn,
			detail+fmt.Sprintf(", under the %s that leaves room for image layers and a few "+
				"releases", formatBytes(comfortableFreeBytes)),
			"The node will work, and will run out sooner than you expect. Plan for more.")
	default:
		return passed("storage.free", "Free disk space", severityRequired, detail)
	}
}
