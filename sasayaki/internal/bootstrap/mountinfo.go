package bootstrap

import (
	"bufio"
	"os"
	"path/filepath"
	"strings"
)

// mountPoint is one line of /proc/self/mountinfo, reduced to what the storage check
// judges: where the filesystem is mounted, what kind it is, and whether project quotas
// are turned on for it.
type mountPoint struct {
	point        string
	filesystem   string
	projectQuota bool

	// quotaOption is the option that granted it, for the detail line: xfs spells it
	// prjquota and the kernel sometimes reports pquota.
	quotaOption string
}

// mountFor finds the filesystem a path is on.
//
// /proc/self/mountinfo rather than statfs, for two reasons. statfs answers with a magic
// number that has to be mapped back to a name through a table this code would then own,
// and the mount options - which is where prjquota lives, and prjquota is the entire
// question - are not in statfs at all.
//
// The longest matching mount point wins: /var and /var/lib/wisper can both be mounts, and
// the answer is the deeper one.
func mountFor(m *machine, path string) (mountPoint, bool) {
	resolved, err := filepath.Abs(path)
	if err != nil {
		resolved = path
	}
	resolved = filepath.ToSlash(resolved)

	file, err := os.Open(filepath.Join(m.procRoot, "self", "mountinfo"))
	if err != nil {
		return mountPoint{filesystem: "unknown"}, false
	}
	defer file.Close()

	best := mountPoint{filesystem: "unknown"}
	found := false
	scanner := bufio.NewScanner(file)
	for scanner.Scan() {
		candidate, ok := parseMountinfoLine(scanner.Text())
		if !ok || !underMount(resolved, candidate.point) {
			continue
		}
		if !found || len(candidate.point) > len(best.point) {
			best = candidate
			found = true
		}
	}
	return best, found
}

// parseMountinfoLine reads one line of /proc/self/mountinfo:
//
//	36 35 98:0 / /var/lib/wisper rw,relatime shared:1 - xfs /dev/sdb1 rw,prjquota
//
// There is a variable number of optional fields before the " - " separator, which is why
// the line is cut on that separator rather than counted through by index.
func parseMountinfoLine(line string) (mountPoint, bool) {
	head, tail, found := strings.Cut(line, " - ")
	if !found {
		return mountPoint{}, false
	}
	headFields := strings.Fields(head)
	tailFields := strings.Fields(tail)
	if len(headFields) < 6 || len(tailFields) < 1 {
		return mountPoint{}, false
	}

	// Per-mount options, plus the per-superblock options where xfs actually reports
	// prjquota.
	options := headFields[5]
	if len(tailFields) >= 3 {
		options += "," + tailFields[2]
	}

	mount := mountPoint{
		point:      unescapeMountPath(headFields[4]),
		filesystem: tailFields[0],
	}
	for _, option := range strings.Split(options, ",") {
		switch strings.TrimSpace(option) {
		case "prjquota", "pquota":
			mount.projectQuota = true
			mount.quotaOption = strings.TrimSpace(option)
		}
	}
	return mount, true
}

// underMount reports whether path is inside mount, comparing whole path components so
// that /var/lib/wisperfoo is not read as being under /var/lib/wisper.
func underMount(path, mount string) bool {
	if mount == "/" {
		return true
	}
	return path == mount || strings.HasPrefix(path, strings.TrimSuffix(mount, "/")+"/")
}

// unescapeMountPath undoes the octal escaping the kernel applies to space, tab, newline
// and backslash in a mount point.
func unescapeMountPath(raw string) string {
	if !strings.Contains(raw, `\`) {
		return raw
	}
	var out strings.Builder
	for index := 0; index < len(raw); index++ {
		if raw[index] == '\\' && index+3 < len(raw) {
			value := 0
			valid := true
			for _, digit := range raw[index+1 : index+4] {
				if digit < '0' || digit > '7' {
					valid = false
					break
				}
				value = value*8 + int(digit-'0')
			}
			if valid {
				out.WriteByte(byte(value))
				index += 3
				continue
			}
		}
		out.WriteByte(raw[index])
	}
	return out.String()
}

// nearestExistingAncestor walks up until something exists, so the filesystem of a state
// directory that has not been created yet can still be judged. Doctor runs before the
// installer creates anything, and refusing to answer until after the thing being judged
// exists would make the check useless exactly when it matters.
func nearestExistingAncestor(path string) string {
	candidate := path
	for {
		if exists(candidate) {
			return candidate
		}
		parent := filepath.Dir(candidate)
		if parent == candidate {
			return candidate
		}
		candidate = parent
	}
}
