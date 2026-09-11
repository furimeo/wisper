package bootstrap

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// The oldest kernel wisper supports.
//
// 5.10 is the line because it is the first long-term kernel where everything below is
// true at once: cgroup v2 is complete enough for Docker to enforce CPU and memory limits
// through it, overlayfs works without a metacopy workaround, and gVisor's supported
// range starts there. Debian 12, Ubuntu 22.04 and RHEL 9 are all above it, so this
// refuses nothing anybody would deploy on today.
const (
	minimumKernelMajor = 5
	minimumKernelMinor = 10
)

// checkKernel decides whether the kernel under this machine can hold a container with
// limits on it. Two findings: the version, and whether the cgroup v2 unified hierarchy is
// what is mounted.
func checkKernel(_ context.Context, m *machine, facts *wisperpb.MachineFacts) []*wisperpb.DoctorCheck {
	return []*wisperpb.DoctorCheck{
		kernelVersionCheck(facts),
		cgroupsCheck(m, facts),
	}
}

func kernelVersionCheck(facts *wisperpb.MachineFacts) *wisperpb.DoctorCheck {
	release := facts.GetKernelVersion()
	if release == "" {
		return check("kernel.version", "Kernel version", severityRequired, outcomeFail,
			"the kernel release could not be read from /proc/sys/kernel/osrelease or uname",
			"This does not look like a Linux machine. A wisper node has to be one.")
	}

	major, minor, ok := parseKernelRelease(release)
	if !ok {
		return check("kernel.version", "Kernel version", severityRequired, outcomeWarn,
			fmt.Sprintf("%s does not parse as a version, so it could not be compared with "+
				"the %d.%d minimum", release, minimumKernelMajor, minimumKernelMinor),
			"Check the kernel is at least "+minimumKernel()+" by hand.")
	}

	if major < minimumKernelMajor || (major == minimumKernelMajor && minor < minimumKernelMinor) {
		return check("kernel.version", "Kernel version", severityRequired, outcomeFail,
			fmt.Sprintf("kernel %s is older than the %s wisper needs: below it, cgroup v2 "+
				"cannot enforce the CPU and memory limits in a spec", release, minimumKernel()),
			"Upgrade the kernel, or use a distribution release that ships "+minimumKernel()+" or newer.")
	}

	return passed("kernel.version", "Kernel version", severityRequired, "Linux "+release)
}

// cgroupsCheck insists on the unified hierarchy.
//
// The marker is /sys/fs/cgroup/cgroup.controllers, which exists only under cgroup v2. A
// hybrid mount - v1 controllers with a v2 tree beside them - has the file too, so the
// list of controllers is read as well: memory and cpu have to be delegated, or Docker
// will accept a limit and not apply it, which is worse than refusing it.
func cgroupsCheck(m *machine, facts *wisperpb.MachineFacts) *wisperpb.DoctorCheck {
	root := filepath.Join(m.sysRoot, "fs", "cgroup")
	controllers, err := readTrimmed(filepath.Join(root, "cgroup.controllers"))
	if err != nil {
		detail := fmt.Sprintf("%s/cgroup.controllers is not readable (%v), so this machine "+
			"is not running the cgroup v2 unified hierarchy", root, err)
		if os.IsNotExist(err) {
			detail = fmt.Sprintf("%s/cgroup.controllers does not exist, so this machine is "+
				"running cgroup v1", root)
		}
		return check("kernel.cgroups2", "cgroup v2 unified hierarchy", severityRequired,
			outcomeFail, detail,
			"Boot with systemd.unified_cgroup_hierarchy=1 (or remove "+
				"systemd.unified_cgroup_hierarchy=0) and reboot. Without it no CPU, memory "+
				"or pid limit in a spec can be enforced.")
	}

	facts.CgroupsV2 = true

	available := strings.Fields(controllers)
	missing := make([]string, 0, 3)
	for _, wanted := range []string{"cpu", "memory", "pids"} {
		if !contains(available, wanted) {
			missing = append(missing, wanted)
		}
	}
	if len(missing) > 0 {
		return check("kernel.cgroups2", "cgroup v2 unified hierarchy", severityRequired,
			outcomeFail,
			fmt.Sprintf("cgroup v2 is mounted at %s but %s %s not delegated to it (available: %s)",
				root, strings.Join(missing, ", "), plural(len(missing), "is", "are"), controllers),
			"Enable the missing controllers in the root cgroup. A limit that is set and not "+
				"enforced is worse than one that was refused.")
	}

	return passed("kernel.cgroups2", "cgroup v2 unified hierarchy", severityRequired,
		"mounted at "+root+" with "+controllers)
}

// parseKernelRelease takes the leading "6.8.0" out of "6.8.0-45-generic".
func parseKernelRelease(release string) (major, minor int, ok bool) {
	numeric := release
	if cut := strings.IndexAny(numeric, "-+ "); cut >= 0 {
		numeric = numeric[:cut]
	}
	parts := strings.Split(numeric, ".")
	if len(parts) < 2 {
		return 0, 0, false
	}
	major, err := strconv.Atoi(parts[0])
	if err != nil {
		return 0, 0, false
	}
	minor, err = strconv.Atoi(parts[1])
	if err != nil {
		return 0, 0, false
	}
	return major, minor, true
}

func minimumKernel() string {
	return strconv.Itoa(minimumKernelMajor) + "." + strconv.Itoa(minimumKernelMinor)
}

func contains(values []string, wanted string) bool {
	for _, value := range values {
		if value == wanted {
			return true
		}
	}
	return false
}

func plural(count int, one, many string) string {
	if count == 1 {
		return one
	}
	return many
}
