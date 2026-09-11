package runtime

import (
	"path/filepath"
	"strconv"
	"strings"
	"testing"
)

func TestParseMountLineFindsTheQuotaOption(t *testing.T) {
	// xfs reports prjquota in the per-superblock options after the " - " separator, and
	// the number of optional fields before it varies - which is why the line is cut on
	// the separator rather than counted through by index.
	xfs, ok := parseMountLine("36 35 98:0 / /var/lib/wisper rw,relatime shared:1 - xfs /dev/sdb1 rw,prjquota")
	if !ok {
		t.Fatal("a well-formed mountinfo line was not parsed")
	}
	if xfs.mountPoint != "/var/lib/wisper" || xfs.kind != "xfs" || !xfs.projectQuota {
		t.Errorf("mount = %+v, want /var/lib/wisper on xfs with project quotas", xfs)
	}
	if !xfs.enforceable() {
		t.Error("an xfs filesystem mounted with prjquota was not considered enforceable")
	}

	ext4, ok := parseMountLine("24 23 8:1 / /var ro,noatime - ext4 /dev/sda1 rw")
	if !ok {
		t.Fatal("a well-formed mountinfo line was not parsed")
	}
	if ext4.enforceable() {
		t.Error("ext4 was reported as able to enforce a project quota")
	}

	// The kernel spells it pquota on some releases.
	other, _ := parseMountLine("36 35 98:0 / /data rw - xfs /dev/sdb1 rw,pquota")
	if !other.enforceable() {
		t.Error("pquota was not recognised as the same thing as prjquota")
	}

	if _, ok := parseMountLine("nonsense"); ok {
		t.Error("a line with no separator was parsed")
	}
}

func TestUnderComparesWholePathComponents(t *testing.T) {
	if !under("/var/lib/wisper/volumes", "/var/lib/wisper") {
		t.Error("a path inside a mount was not recognised")
	}
	if under("/var/lib/wisperfoo", "/var/lib/wisper") {
		t.Error("/var/lib/wisperfoo was read as being under /var/lib/wisper")
	}
	if !under("/anything", "/") {
		t.Error("the root mount does not contain everything")
	}
}

func TestAProjectIdIsStableAndNeverZero(t *testing.T) {
	first := projectID("/var/lib/wisper/volumes/42/7")
	if first != projectID("/var/lib/wisper/volumes/42/7") {
		t.Error("the same directory produced two project ids, so a restart would lose the quota")
	}
	if first == projectID("/var/lib/wisper/volumes/42/8") {
		t.Error("two volumes share a project id, so they share a quota")
	}
	if first == 0 {
		t.Error("project 0 means 'no project', and assigning a directory to it removes the " +
			"quota instead of setting one")
	}
	if first > 0x7fffffff {
		t.Errorf("project id %d does not fit in the 31 bits xfs allows", first)
	}
}

// The node this test runs on is not XFS - nor is any developer machine - and that is a
// documented state rather than a failure. The panel is told, the customer's page says the
// number is not being applied, and the workload still runs.
func TestAQuotaOnAFilesystemThatCannotEnforceItIsAdvisory(t *testing.T) {
	host := newHost()
	docker := newDocker(t, readyEngine(), host)

	if docker.QuotaEnforceable() {
		t.Skip("this machine really does have an XFS project quota on its temporary directory")
	}
	if err := docker.applyQuota(t.Context(), filepath.Join(docker.stateDir, "volumes", "42", "7"), 20<<30); err != nil {
		t.Fatalf("applyQuota: %v", err)
	}
	for _, call := range host.invocations() {
		if strings.HasPrefix(call, quotaTool) {
			t.Errorf("ran %q on a filesystem that would have ignored it", call)
		}
	}

	isolation, err := docker.Isolation(t.Context())
	if err != nil {
		t.Fatalf("Isolation: %v", err)
	}
	if isolation.QuotaEnforceable {
		t.Error("the panel would be told this node enforces disk limits when it does not")
	}
}

func TestAnEnforceableQuotaIsSetOnTheDirectoryAndTheProject(t *testing.T) {
	host := newHost()
	docker := newDocker(t, readyEngine(), host)
	// Stand in for an XFS mount, which no machine running these tests has.
	docker.quota.filesystem = filesystem{mountPoint: "/var/lib/wisper", kind: "xfs", projectQuota: true}
	docker.quota.loaded = true

	directory := "/var/lib/wisper/volumes/42/7"
	if err := docker.applyQuota(t.Context(), directory, 20<<30); err != nil {
		t.Fatalf("applyQuota: %v", err)
	}

	calls := host.invocations()
	if len(calls) != 2 {
		t.Fatalf("made %d invocations, want the project assignment and the limit:\n%s",
			len(calls), strings.Join(calls, "\n"))
	}
	project := projectID(directory)
	wantAssign := "xfs_quota -x -c project -s -p " + directory + " " +
		strconv.FormatUint(uint64(project), 10) + " /var/lib/wisper"
	if calls[0] != wantAssign {
		t.Errorf("first call = %q, want %q", calls[0], wantAssign)
	}
	if !strings.Contains(calls[1], "bhard=21474836480") {
		t.Errorf("second call = %q, want the byte ceiling", calls[1])
	}

	// Clearing a quota has to actually clear it: a stale ceiling left behind by an early
	// return is a workload that stops being able to write for a reason nobody can find.
	host.calls = nil
	if err := docker.applyQuota(t.Context(), directory, 0); err != nil {
		t.Fatalf("applyQuota(0): %v", err)
	}
	if !strings.Contains(host.invocations()[1], "bhard=0") {
		t.Errorf("clearing a quota = %q, want bhard=0", host.invocations()[1])
	}
}
