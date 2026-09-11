package state

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// A corrupt state database is not a reason to refuse to start. The node's job is to keep
// customers' containers running, and it can rebuild everything in here from the next spec
// the panel sends; refusing to boot would turn one bad file into an outage.

func TestOpenRecoversFromAFileThatIsNotADatabaseAtAll(t *testing.T) {
	directory := t.TempDir()
	path := filepath.Join(directory, FileName)
	rubbish := []byte("this is a log file somebody put here by mistake\n")
	if err := os.WriteFile(path, rubbish, 0o600); err != nil {
		t.Fatalf("write the rubbish file: %v", err)
	}

	store := openStoreAt(t, path)
	quarantined := store.RecoveredFrom()
	if quarantined == "" {
		t.Fatal("a file that is not a database was adopted silently")
	}

	// Renamed, never removed: the file is the only evidence of what happened to a node's
	// memory, and deleting it makes the fault unreproducible the moment it recovers.
	kept, err := os.ReadFile(quarantined)
	if err != nil {
		t.Fatalf("the quarantined file is not where the daemon says it is: %v", err)
	}
	if string(kept) != string(rubbish) {
		t.Fatal("the quarantined file is not the one that was moved aside")
	}
	if !strings.HasPrefix(filepath.Base(quarantined), FileName+".corrupt-") {
		t.Fatalf("the quarantined file is named %q, which an operator will not recognise", quarantined)
	}

	// And the replacement is a working database at the original path.
	ctx := context.Background()
	if err := store.SaveSpec(ctx, sampleSpec(1), "the first spec after recovery", noon); err != nil {
		t.Fatalf("the replacement database does not work: %v", err)
	}
	if _, err := store.LoadSpec(ctx); err != nil {
		t.Fatalf("read back from the replacement: %v", err)
	}
}

func TestOpenRecoversFromACorruptDatabase(t *testing.T) {
	// A real SQLite file whose pages have been overwritten: the header still says "SQLite",
	// so this is the case the integrity check exists for rather than the one the header
	// catches.
	path := filepath.Join(t.TempDir(), FileName)
	ctx := context.Background()

	first := openStoreAt(t, path)
	if err := first.SaveSpec(ctx, sampleSpec(3), "publish", noon); err != nil {
		t.Fatalf("save a spec: %v", err)
	}
	if err := first.Close(); err != nil {
		t.Fatalf("close: %v", err)
	}
	// The write-ahead log holds the committed pages; both it and the shared-memory index have
	// to go with the file, or SQLite replays another database's transactions into the new one.
	corruptPagesAfterTheHeader(t, path)

	second := openStoreAt(t, path)
	if second.RecoveredFrom() == "" {
		t.Fatal("a corrupt database was adopted silently")
	}
	if _, err := second.LoadSpec(ctx); !errors.Is(err, ErrNoSpec) {
		t.Fatalf("the replacement database is not empty: %v", err)
	}
	if err := second.SaveSpec(ctx, sampleSpec(1), "the first spec after recovery", noon); err != nil {
		t.Fatalf("the replacement database does not work: %v", err)
	}

	for _, suffix := range []string{"-wal", "-shm"} {
		if _, err := os.Stat(second.RecoveredFrom() + suffix); err == nil {
			continue
		} else if !errors.Is(err, os.ErrNotExist) {
			t.Fatalf("inspect the quarantined %s file: %v", suffix, err)
		}
	}
}

// corruptPagesAfterTheHeader overwrites the b-tree pages of a closed database while leaving
// the first hundred bytes - the file header SQLite reads to decide whether this is a
// database at all - untouched.
func corruptPagesAfterTheHeader(t *testing.T, path string) {
	t.Helper()

	// Any WAL is a separate copy of the truth and would be replayed over the damage.
	for _, suffix := range []string{"-wal", "-shm"} {
		if err := os.Remove(path + suffix); err != nil && !errors.Is(err, os.ErrNotExist) {
			t.Fatalf("remove %s: %v", path+suffix, err)
		}
	}

	contents, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read the database: %v", err)
	}
	if len(contents) < 200 {
		t.Fatalf("the database is only %d bytes, which is too small to damage meaningfully", len(contents))
	}
	for i := 100; i < len(contents); i++ {
		contents[i] = 0x5a
	}
	if err := os.WriteFile(path, contents, 0o600); err != nil {
		t.Fatalf("write the damaged database: %v", err)
	}
}

func TestRecoveryDoesNotFireForAnOrdinaryFailure(t *testing.T) {
	// A full disk, a read-only mount, a directory where the file should be. Moving a file
	// aside for one of these would destroy a perfectly good database because a disk was
	// briefly full, so only real corruption may trigger it.
	directory := t.TempDir()
	path := filepath.Join(directory, FileName)
	if err := os.Mkdir(path, 0o700); err != nil {
		t.Fatalf("put a directory where the database goes: %v", err)
	}

	if _, err := Open(context.Background(), path); err == nil {
		t.Fatal("a directory was opened as a database")
	}
	if _, err := os.Stat(path); err != nil {
		t.Fatalf("the directory was moved aside as if it were a corrupt database: %v", err)
	}
	entries, err := os.ReadDir(directory)
	if err != nil {
		t.Fatalf("list the state directory: %v", err)
	}
	if len(entries) != 1 {
		t.Fatalf("the failed open left %d entries behind, want only the directory", len(entries))
	}
}

func TestIsCorruptionRecognisesOnlyTheTwoRealCases(t *testing.T) {
	failedCheck := &corruptFileError{detail: "*** in database main *** page 3 is never used"}
	if !isCorruption(failedCheck) {
		t.Fatal("a failed integrity check was not recognised as corruption")
	}
	// The message reaches an operator's log, so it has to carry what SQLite actually said.
	if !strings.Contains(failedCheck.Error(), "page 3 is never used") {
		t.Fatalf("the integrity failure does not report the finding: %s", failedCheck)
	}
	if !isCorruption(errors.New("sqlite: file is not a database (26)")) {
		t.Fatal("SQLITE_NOTADB reported as text was not recognised as corruption")
	}
	if !isCorruption(errors.New("database disk image is malformed")) {
		t.Fatal("SQLITE_CORRUPT reported as text was not recognised as corruption")
	}
	for _, ordinary := range []string{
		"disk I/O error",
		"attempt to write a readonly database",
		"database or disk is full",
		"no such file or directory",
	} {
		if isCorruption(errors.New(ordinary)) {
			t.Fatalf("%q was treated as corruption, which would move a healthy database aside", ordinary)
		}
	}
}

func TestQuarantineNeverOverwritesAnEarlierOne(t *testing.T) {
	// A node that crash-loops fast enough to corrupt and quarantine twice inside one second
	// would otherwise lose the first piece of evidence, and on Windows the second rename
	// fails outright.
	directory := t.TempDir()
	path := filepath.Join(directory, FileName)

	if err := os.WriteFile(path, []byte("first"), 0o600); err != nil {
		t.Fatalf("write the first file: %v", err)
	}
	first, err := quarantine(path)
	if err != nil {
		t.Fatalf("first quarantine: %v", err)
	}
	if err := os.WriteFile(path, []byte("second"), 0o600); err != nil {
		t.Fatalf("write the second file: %v", err)
	}
	second, err := quarantine(path)
	if err != nil {
		t.Fatalf("second quarantine: %v", err)
	}
	if first == second {
		t.Fatalf("both quarantines chose %q, so the first file was overwritten", first)
	}

	kept, err := os.ReadFile(first)
	if err != nil {
		t.Fatalf("read the first quarantined file: %v", err)
	}
	if string(kept) != "first" {
		t.Fatalf("the first quarantined file now contains %q", kept)
	}
}

func TestQuarantineTakesTheWalAndTheSharedMemoryIndexWithIt(t *testing.T) {
	// A WAL left next to a fresh database is a different database's transactions, and SQLite
	// would either refuse the file or replay them into it.
	directory := t.TempDir()
	path := filepath.Join(directory, FileName)
	for _, suffix := range []string{"", "-wal", "-shm"} {
		if err := os.WriteFile(path+suffix, []byte("contents of "+suffix), 0o600); err != nil {
			t.Fatalf("write %s: %v", path+suffix, err)
		}
	}

	target, err := quarantine(path)
	if err != nil {
		t.Fatalf("quarantine: %v", err)
	}
	for _, suffix := range []string{"", "-wal", "-shm"} {
		if _, err := os.Stat(path + suffix); !errors.Is(err, os.ErrNotExist) {
			t.Fatalf("%s was left behind: %v", path+suffix, err)
		}
		if _, err := os.Stat(target + suffix); err != nil {
			t.Fatalf("%s did not arrive at the quarantine: %v", target+suffix, err)
		}
	}
}

func TestQuarantineIsHappyWithNoSidecars(t *testing.T) {
	// A database closed cleanly has checkpointed its WAL away. Missing sidecars are the
	// normal case, not a failure.
	path := filepath.Join(t.TempDir(), FileName)
	if err := os.WriteFile(path, []byte("only the database"), 0o600); err != nil {
		t.Fatalf("write the database: %v", err)
	}
	if _, err := quarantine(path); err != nil {
		t.Fatalf("quarantine a database with no sidecars: %v", err)
	}
}
