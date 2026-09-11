package runtime

import (
	"bufio"
	"context"
	"fmt"
	"hash/fnv"
	"log/slog"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Disk quota, enforced by the filesystem rather than by polling.
//
// XFS project quotas are the only mechanism here that actually stops a customer filling a
// node's disk. A watcher that measures a directory every minute and then complains is not
// a limit: by the time it notices, the machine is full and every other customer on it is
// down. That is why the storage layout had to be settled before any code was written
// (design section 11.1) - a quota is attached to a directory tree, and moving the tree
// later means moving customer data.
//
// Where the filesystem cannot enforce it the limit is advisory, and that is a documented
// state rather than a failure: `sasayaki doctor` reports the filesystem, the panel stores
// it as node_status.quota_enforceable, and the customer's page says the number is not
// being applied (docs/contracts/node-spec.md section 3.4). Refusing to start a workload
// because the node is on ext4 would make every developer machine unusable and every
// existing installation unupgradeable, and it would protect nobody: the disk is exactly
// as unbounded either way.
const (
	// quotaTool is xfsprogs' own command. There is no library binding worth the
	// dependency: this is two invocations, and the tool is on every machine that has an
	// XFS filesystem worth quota-ing.
	quotaTool = "xfs_quota"

	// quotaTimeout bounds one invocation. xfs_quota talks to the kernel and answers
	// immediately or not at all; a hang here would stall a reconcile pass.
	quotaTimeout = 10 * time.Second
)

// filesystem is what the node's volume tree sits on, as the quota code needs it.
type filesystem struct {
	// mountPoint is the deepest mount containing the volumes directory, and the argument
	// xfs_quota takes to say which filesystem it is operating on.
	mountPoint string
	kind       string
	// projectQuota is whether the filesystem was mounted with prjquota (the kernel
	// sometimes reports it as pquota). Without it, xfs_quota's limits are accepted and
	// then ignored, which is the worst of the three possible outcomes.
	projectQuota bool
}

// enforceable reports whether a limit set on this filesystem will actually be applied.
func (f filesystem) enforceable() bool {
	return f.kind == "xfs" && f.projectQuota
}

// quotaState caches the filesystem answer and whether the advisory warning has been said.
type quotaState struct {
	sync.Mutex
	filesystem filesystem
	loaded     bool
	warned     bool
}

// QuotaEnforceable reports whether disk limits on this node are real.
//
// The heartbeat carries it to the panel so a customer is shown "20 GB (not enforced on
// this node)" rather than a number that means nothing.
func (d *Docker) QuotaEnforceable() bool {
	return d.volumeFilesystem().enforceable()
}

// applyQuota puts a hard byte ceiling on one volume directory.
//
// bytes of zero or less clears the limit rather than skipping the call, because a
// customer downgrading from a quota to none has to actually get none - a stale ceiling
// left behind by an early return is a workload that stops being able to write for a
// reason nobody can find.
func (d *Docker) applyQuota(ctx context.Context, directory string, bytes int64) error {
	volumes := d.volumeFilesystem()
	if !volumes.enforceable() {
		d.warnAdvisory(volumes, directory, bytes)
		return nil
	}

	ctx, cancel := context.WithTimeout(ctx, quotaTimeout)
	defer cancel()

	project := projectID(directory)
	if _, err := d.run(ctx, quotaTool, "-x", "-c",
		fmt.Sprintf("project -s -p %s %d", directory, project), volumes.mountPoint); err != nil {
		return fmt.Errorf("runtime: put %s into XFS project %d: %w", directory, project, err)
	}

	ceiling := "0"
	if bytes > 0 {
		ceiling = strconv.FormatInt(bytes, 10)
	}
	if _, err := d.run(ctx, quotaTool, "-x", "-c",
		fmt.Sprintf("limit -p bhard=%s %d", ceiling, project), volumes.mountPoint); err != nil {
		return fmt.Errorf("runtime: set the %s-byte ceiling on XFS project %d (%s): %w",
			ceiling, project, directory, err)
	}
	return nil
}

// warnAdvisory says once, and only once, that a limit is not being applied. Once because
// it is a property of the machine, not of the workload, and a line per volume per
// reconcile pass would bury everything else in the log.
func (d *Docker) warnAdvisory(fs filesystem, directory string, bytes int64) {
	if bytes <= 0 {
		return
	}
	d.quota.Lock()
	defer d.quota.Unlock()
	if d.quota.warned {
		return
	}
	d.quota.warned = true
	d.log.Warn("disk limits on this node are advisory: the filesystem holding the volumes "+
		"has no XFS project quota, so the ceilings in the spec are recorded and not enforced",
		slog.String("filesystem", fs.kind),
		slog.String("mount", fs.mountPoint),
		slog.String("example", directory),
		slog.Int64("requested_bytes", bytes))
}

// volumeFilesystem answers, once, what the volume tree is sitting on.
//
// Cached for the life of the process. A filesystem is not remounted with different quota
// options under a running daemon without that daemon being restarted too, and re-reading
// /proc/self/mountinfo for every volume of every workload on every pass is a syscall
// storm for an answer that does not move.
func (d *Docker) volumeFilesystem() filesystem {
	d.quota.Lock()
	defer d.quota.Unlock()
	if d.quota.loaded {
		return d.quota.filesystem
	}
	d.quota.filesystem = mountFor(filepath.Join(d.stateDir, volumesDirectory))
	d.quota.loaded = true
	return d.quota.filesystem
}

// mountFor finds the filesystem a path is on by reading /proc/self/mountinfo.
//
// Not statfs: statfs answers with a magic number that would need a table maintained here,
// and the mount options - where prjquota lives, and prjquota is the entire question - are
// not in statfs at all. A machine with no /proc, which includes every developer running
// the unit tests on Windows, reads as "unknown, not enforceable", which is the truthful
// answer rather than a failure.
func mountFor(path string) filesystem {
	resolved, err := filepath.Abs(path)
	if err != nil {
		resolved = path
	}
	resolved = filepath.ToSlash(resolved)

	file, err := os.Open("/proc/self/mountinfo")
	if err != nil {
		return filesystem{kind: "unknown"}
	}
	defer file.Close()

	best := filesystem{kind: "unknown"}
	scanner := bufio.NewScanner(file)
	for scanner.Scan() {
		candidate, ok := parseMountLine(scanner.Text())
		if !ok || !under(resolved, candidate.mountPoint) {
			continue
		}
		// The longest match wins: /var and /var/lib/wisper can both be mounts, and the
		// answer is the deeper one.
		if len(candidate.mountPoint) > len(best.mountPoint) {
			best = candidate
		}
	}
	return best
}

// parseMountLine reads one line of /proc/self/mountinfo:
//
//	36 35 98:0 / /var/lib/wisper rw,relatime shared:1 - xfs /dev/sdb1 rw,prjquota
//
// The number of optional fields before " - " varies, which is why the line is cut on that
// separator instead of being counted through by index.
func parseMountLine(line string) (filesystem, bool) {
	head, tail, found := strings.Cut(line, " - ")
	if !found {
		return filesystem{}, false
	}
	headFields := strings.Fields(head)
	tailFields := strings.Fields(tail)
	if len(headFields) < 6 || len(tailFields) < 1 {
		return filesystem{}, false
	}

	// Per-mount options, plus the per-superblock options, because xfs reports prjquota
	// in the second set and not the first.
	options := headFields[5]
	if len(tailFields) >= 3 {
		options += "," + tailFields[2]
	}

	mount := filesystem{mountPoint: headFields[4], kind: tailFields[0]}
	for _, option := range strings.Split(options, ",") {
		switch strings.TrimSpace(option) {
		case "prjquota", "pquota":
			mount.projectQuota = true
		}
	}
	return mount, true
}

// under reports whether path is inside mount, comparing whole components so that
// /var/lib/wisperfoo is not read as being under /var/lib/wisper.
func under(path, mount string) bool {
	if mount == "/" {
		return true
	}
	return path == mount || strings.HasPrefix(path, strings.TrimSuffix(mount, "/")+"/")
}

// projectID is the XFS project number for a directory.
//
// Derived from the path rather than allocated from a counter, so it survives the state
// database being rebuilt and is the same number on both sides of a daemon restart. Folded
// into the low 31 bits and never zero: project 0 is "no project", and assigning a
// directory to it removes the quota instead of setting one.
func projectID(directory string) uint32 {
	digest := fnv.New32a()
	digest.Write([]byte(filepath.ToSlash(directory)))
	value := digest.Sum32() & 0x7fffffff
	if value == 0 {
		return 1
	}
	return value
}
