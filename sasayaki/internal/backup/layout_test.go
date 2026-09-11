package backup

import (
	"context"
	"strings"
	"testing"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Every id in a command reaches a filesystem path or an object key. These are the refusals
// that make that safe, and they are refusals rather than rewrites: sanitising maps two
// different ids onto one directory, which loses a customer's backup instead of rejecting a
// request nobody sent.

func TestIdentifiersThatCouldBecomeAPathAreRefused(t *testing.T) {
	for name, value := range map[string]string{
		"empty":              "",
		"a parent directory": "..",
		"a leading dot":      ".hidden",
		"a separator":        "a/b",
		"a backslash":        `a\b`,
		"a null byte":        "a\x00b",
		"too long":           strings.Repeat("a", 65),
	} {
		t.Run(name, func(t *testing.T) {
			if err := checkIdentifier("volume id", value); err == nil {
				t.Errorf("%q was accepted as an identifier", value)
			}
		})
	}
}

func TestALocalPrefixCannotClimbOutOfTheBackupDirectory(t *testing.T) {
	h := newHarness(t)

	_, _, err := h.Runner.resolveDestination(&wisperpb.BackupDestination{
		Kind:        wisperpb.DestinationKind_DESTINATION_KIND_LOCAL,
		LocalPrefix: "../../etc",
	})
	if err == nil {
		t.Fatal("a prefix pointing outside the state directory was accepted")
	}
}

func TestAnObjectKeyRefusesASubjectThatWouldBecomeAPath(t *testing.T) {
	at := time.Date(2026, 9, 11, 10, 0, 0, 0, time.UTC)

	if _, err := objectKey("nightly", "../../etc", at, "b1",
		wisperpb.BackupTargetKind_BACKUP_TARGET_KIND_VOLUME); err == nil {
		t.Error("a subject id containing a separator produced a key")
	}
	if _, err := objectKey("nightly", "vol1", at, ".hidden",
		wisperpb.BackupTargetKind_BACKUP_TARGET_KIND_VOLUME); err == nil {
		t.Error("a backup id starting with a dot produced a key")
	}
}

func TestAnObjectKeyCarriesTheTimeItWasTaken(t *testing.T) {
	at := time.Date(2026, 9, 11, 10, 30, 15, 0, time.UTC)

	key, err := objectKey("nightly", "vol1", at, "b7",
		wisperpb.BackupTargetKind_BACKUP_TARGET_KIND_VOLUME)
	if err != nil {
		t.Fatalf("build the key: %v", err)
	}
	if want := "nightly/vol1/20260911T103015Z-b7.tar.gz"; key != want {
		t.Fatalf("key is %q, want %q", key, want)
	}

	parsed, ok := parseGeneration(key, 1234)
	if !ok {
		t.Fatal("the key this package writes is not one it can read back")
	}
	if !parsed.Taken.Equal(at) {
		t.Errorf("read the time back as %s, want %s", parsed.Taken, at)
	}
}

// Retention only ever considers objects this package wrote. A bucket shared with a customer's
// own tooling must produce "not mine" rather than a generation with an unparseable date that
// sorts to the epoch and gets deleted first.
func TestObjectsThisPackageDidNotWriteAreNotGenerations(t *testing.T) {
	for _, key := range []string{
		"nightly/vol1/20260911T103015Z-b7.tar.gz.sha256",
		"nightly/vol1/notes.txt",
		"nightly/vol1/backup.tar.gz",
		"nightly/vol1/yesterday-b7.tar.gz",
		"nightly/vol1/",
	} {
		if _, ok := parseGeneration(key, 1); ok {
			t.Errorf("%q was read as a generation", key)
		}
	}
}

// A command that does not make sense is refused as a command rather than recorded as a failed
// backup, because a failed backup in front of a customer is a claim about their data.
func TestCommandsThatCannotBeCarriedOutAreRefusedOutright(t *testing.T) {
	h := newHarness(t)
	ctx := context.Background()

	t.Run("a volume backup with no owning workload", func(t *testing.T) {
		request := volumeBackup(localTarget("nightly"))
		request.WorkloadId = ""
		if _, err := h.Runner.RunBackup(ctx, request); err == nil {
			t.Error("a volume backup with nothing to pause was accepted")
		}
	})

	t.Run("a database backup with no engine", func(t *testing.T) {
		request := databaseBackup(localTarget("nightly"))
		request.Engine = wisperpb.DatabaseEngine_DATABASE_ENGINE_UNSPECIFIED
		if _, err := h.Runner.RunBackup(ctx, request); err == nil {
			t.Error("a database backup with no engine was accepted")
		}
	})

	t.Run("a backup with no destination", func(t *testing.T) {
		request := volumeBackup(nil)
		if _, err := h.Runner.RunBackup(ctx, request); err == nil {
			t.Error("a backup with nowhere to go was accepted")
		}
	})

	t.Run("a restore whose location is not an object key", func(t *testing.T) {
		request := volumeRestore("s3://bucket/../../etc/passwd")
		if _, err := h.Runner.RestoreBackup(ctx, request); err == nil {
			t.Error("a restore from outside the destination was accepted")
		}
	})
}

// The timeout has a floor and a ceiling. The point of the field is to stop a stuck backup from
// running into the next scheduled one, and a value large enough to defeat that is one nobody
// meant to send.
func TestTheTimeoutIsBoundedAtBothEnds(t *testing.T) {
	if got := timeoutFor(0); got != defaultTimeout {
		t.Errorf("no timeout became %s, want %s", got, defaultTimeout)
	}
	if got := timeoutFor(90); got != 90*time.Second {
		t.Errorf("90 seconds became %s", got)
	}
	if got, ceiling := timeoutFor(999999999), 24*time.Hour; got != ceiling {
		t.Errorf("an absurd timeout became %s, want %s", got, ceiling)
	}
}
