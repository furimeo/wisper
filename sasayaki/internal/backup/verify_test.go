package backup

import (
	"context"
	"errors"
	"io"
	"maps"
	"os"
	"strings"
	"testing"
)

// The checks that stand between a corrupt archive and a customer's only copy of their data.

// corrupt flips a byte in the middle of an object without changing its length, which is the
// shape of the damage a checksum exists to catch - a wrong length would be caught by anything.
func corrupt(t *testing.T, stateDir, key string) {
	t.Helper()
	path, err := localObjectPath(stateDir, key)
	if err != nil {
		t.Fatalf("resolve %s: %v", key, err)
	}
	contents, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read %s: %v", path, err)
	}
	if len(contents) < 8 {
		t.Fatalf("%s is only %d bytes; there is nothing to corrupt", path, len(contents))
	}
	contents[len(contents)/2] ^= 0xff
	if err := os.WriteFile(path, contents, 0o600); err != nil {
		t.Fatalf("write %s: %v", path, err)
	}
}

func TestRestoreRefusesAnArchiveThatDoesNotMatchItsChecksum(t *testing.T) {
	h := newHarness(t)
	h.Workloads.running["w1"] = true
	original := map[string]string{"app.db": "monday", "cache/one.txt": "warm"}
	key := takeVolumeBackup(t, h, original)

	live, err := volumePath(h.StateDir, "w1", "vol1")
	if err != nil {
		t.Fatalf("resolve the volume: %v", err)
	}
	before := readTree(t, live)
	seenBefore := h.Workloads.seen()

	corrupt(t, h.StateDir, key)

	completed, err := h.Runner.RestoreBackup(context.Background(), volumeRestore(key))
	if err != nil {
		t.Fatalf("the command itself should succeed and report a failed restore: %v", err)
	}
	if completed.GetSuccess() {
		t.Fatal("a corrupt archive was restored over the customer's data")
	}
	if !strings.Contains(completed.GetDetail(), "nothing was stopped and nothing was overwritten") {
		t.Errorf("the failure says %q, and the one thing a customer needs to be told is that "+
			"their data was left alone", completed.GetDetail())
	}

	if got := readTree(t, live); !maps.Equal(got, before) {
		t.Errorf("the volume changed: it holds %v, was %v", got, before)
	}
	if got := h.Workloads.seen(); len(got) != len(seenBefore) {
		t.Errorf("the refused restore still touched the workload: %v", got)
	}
}

// An archive with no sidecar cannot be shown to be intact, and "cannot be shown to be intact"
// is not a thing to restore over live data.
func TestRestoreRefusesAnArchiveWithNoRecordedChecksum(t *testing.T) {
	h := newHarness(t)
	key := takeVolumeBackup(t, h, map[string]string{"app.db": "monday"})

	sidecar, err := localObjectPath(h.StateDir, digestKey(key))
	if err != nil {
		t.Fatalf("resolve the sidecar: %v", err)
	}
	if err := os.Remove(sidecar); err != nil {
		t.Fatalf("remove the sidecar: %v", err)
	}

	completed, err := h.Runner.RestoreBackup(context.Background(), volumeRestore(key))
	if err != nil {
		t.Fatalf("the command itself should succeed and report a failed restore: %v", err)
	}
	if completed.GetSuccess() {
		t.Fatal("an archive with no recorded checksum was restored")
	}
	if !strings.Contains(completed.GetDetail(), "no recorded checksum") {
		t.Errorf("the failure says %q, which does not explain that there is nothing to check "+
			"the archive against", completed.GetDetail())
	}
}

// The verify stage of a backup, on its own: what it catches is an object store that answered
// 200 and stored something else.
func TestVerifyRefusesAnArchiveTheDestinationChanged(t *testing.T) {
	h := newHarness(t)
	ctx := context.Background()
	destination := newLocalDestination(h.StateDir)

	archive, err := stageArchive(h.StateDir, "b1", volumeExtension, func(out io.Writer) error {
		_, err := out.Write([]byte("the bytes of an archive"))
		return err
	})
	if err != nil {
		t.Fatalf("stage the archive: %v", err)
	}
	journal, err := openJournal(h.StateDir, "b1")
	if err != nil {
		t.Fatalf("open the journal: %v", err)
	}

	const key = "nightly/vol1/20260911T100001Z-b1.tar.gz"
	if err := h.Runner.upload(ctx, destination, key, archive, journal); err != nil {
		t.Fatalf("upload: %v", err)
	}
	if err := h.Runner.verify(ctx, destination, key, archive); err != nil {
		t.Fatalf("an untouched archive failed verification: %v", err)
	}

	corrupt(t, h.StateDir, key)

	err = h.Runner.verify(ctx, destination, key, archive)
	if !errors.Is(err, ErrChecksumMismatch) {
		t.Fatalf("verifying a corrupted archive gave %v, want a checksum mismatch", err)
	}
}
