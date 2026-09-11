package files

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// The tests this package exists for.
//
// Path traversal in the file manager is the largest attack surface in v1 (design section
// 9), and the three ways out of a root are `..`, an absolute path and a symbolic link.
// Each of them is tried below against a real tree with a real secret planted outside the
// root, and the assertion is always the same: the secret does not come back.

const secretContent = "the node's private key would be here"

// plantSecret puts a file where no customer may ever read it: in the state directory, one
// level above the volumes tree.
func plantSecret(t *testing.T, test *harness) string {
	t.Helper()
	path := filepath.Join(test.stateDir, "secret.txt")
	if err := os.WriteFile(path, []byte(secretContent), 0o600); err != nil {
		t.Fatalf("plant the secret: %v", err)
	}
	return path
}

func TestReadingThroughDotDotIsRefused(t *testing.T) {
	test := newHarness(t)
	plantSecret(t, test)

	for _, attempt := range []string{
		"../../secret.txt",
		"../../../secret.txt",
		"subdir/../../../secret.txt",
		"..",
	} {
		events := test.run(t, &wisperpb.FileRequest{
			Op: &wisperpb.FileRequest_Read{Read: &wisperpb.ReadFile{Path: attempt}},
		})
		wantError(t, events, wisperpb.FileErrorCode_FILE_ERROR_CODE_PATH_ESCAPES_ROOT)
		assertNoSecret(t, events, attempt)
	}
}

func TestAnAbsolutePathCannotNameAHostFile(t *testing.T) {
	test := newHarness(t)
	secret := plantSecret(t, test)

	// Every shape a client could send: a POSIX absolute path, the real host path of the
	// secret, and on Windows the drive-lettered form. None may reach the file.
	attempts := []string{
		"/secret.txt",
		secret,
		filepath.ToSlash(secret),
		"/etc/passwd",
	}
	for _, attempt := range attempts {
		events := test.run(t, &wisperpb.FileRequest{
			Op: &wisperpb.FileRequest_Read{Read: &wisperpb.ReadFile{Path: attempt}},
		})
		if events[len(events)-1].GetError() == nil {
			t.Fatalf("reading %q succeeded, and nothing outside the root may be readable", attempt)
		}
		assertNoSecret(t, events, attempt)
	}
}

func TestASymlinkOutOfTheRootIsNeverFollowed(t *testing.T) {
	test := newHarness(t)
	plantSecret(t, test)
	// The classic escape: a link a customer's own container created inside their volume,
	// pointing at the node's state directory.
	test.symlinkOrSkip(t, test.stateDir, "escape")

	read := test.run(t, &wisperpb.FileRequest{
		Op: &wisperpb.FileRequest_Read{Read: &wisperpb.ReadFile{Path: "escape/secret.txt"}},
	})
	wantError(t, read, wisperpb.FileErrorCode_FILE_ERROR_CODE_PATH_ESCAPES_ROOT)
	assertNoSecret(t, read, "escape/secret.txt")

	list := test.run(t, &wisperpb.FileRequest{
		Op: &wisperpb.FileRequest_List{List: &wisperpb.ListDirectory{Path: "escape"}},
	})
	wantError(t, list, wisperpb.FileErrorCode_FILE_ERROR_CODE_PATH_ESCAPES_ROOT)

	measure := test.run(t, &wisperpb.FileRequest{
		Op: &wisperpb.FileRequest_Measure{Measure: &wisperpb.MeasureDirectory{Path: "escape"}},
	})
	wantError(t, measure, wisperpb.FileErrorCode_FILE_ERROR_CODE_PATH_ESCAPES_ROOT)
}

// A link is reported, so the customer can see it and remove it. That is the other half of
// "never followed": refusing to describe it would leave a link nobody can get rid of.
func TestASymlinkIsReportedAndRemovable(t *testing.T) {
	test := newHarness(t)
	test.symlinkOrSkip(t, test.stateDir, "escape")

	stat := test.run(t, &wisperpb.FileRequest{
		Op: &wisperpb.FileRequest_Stat{Stat: &wisperpb.StatPath{Path: "escape"}},
	})
	info := only(t, stat).GetInfo()
	if info == nil {
		t.Fatalf("a symlink could not be stat'ed: %v", stat)
	}
	if !info.GetSymlink() {
		t.Error("the entry was not reported as a symlink, so the UI would draw it as a folder")
	}
	if info.GetSymlinkTarget() == "" {
		t.Error("the link's target was not reported, and a customer cannot judge a link they cannot see")
	}

	deleted := test.run(t, &wisperpb.FileRequest{
		Op: &wisperpb.FileRequest_Delete{Delete: &wisperpb.DeletePath{Path: "escape"}},
	})
	wantDone(t, deleted)

	// The link is gone and what it pointed at is untouched.
	if _, err := os.Lstat(filepath.Join(test.volume, "escape")); !os.IsNotExist(err) {
		t.Errorf("the link is still there: %v", err)
	}
	if _, err := os.Stat(test.stateDir); err != nil {
		t.Fatalf("deleting a link removed its target: %v", err)
	}
}

// Even a link that stays inside the root is not traversed. The rule is the same wherever
// it points, so there is no second case whose safety depends on what os.Root does
// underneath (path.go, ensureTraversable).
func TestASymlinkInsideTheRootIsAlsoNotTraversed(t *testing.T) {
	test := newHarness(t)
	test.write(t, "real/data.txt", "hello")
	test.symlinkOrSkip(t, "real", "alias")

	events := test.run(t, &wisperpb.FileRequest{
		Op: &wisperpb.FileRequest_Read{Read: &wisperpb.ReadFile{Path: "alias/data.txt"}},
	})
	wantError(t, events, wisperpb.FileErrorCode_FILE_ERROR_CODE_PATH_ESCAPES_ROOT)
}

func TestAnUnpublishedRootIsRefused(t *testing.T) {
	test := newHarness(t)

	events := test.run(t, &wisperpb.FileRequest{
		RootId: "root-that-was-never-published",
		Op:     &wisperpb.FileRequest_List{List: &wisperpb.ListDirectory{}},
	})
	wantError(t, events, wisperpb.FileErrorCode_FILE_ERROR_CODE_UNKNOWN_ROOT)
}

func TestAReadOnlyRootRefusesEveryChange(t *testing.T) {
	test := newHarness(t)
	if err := os.MkdirAll(filepath.Join(test.stateDir, "sites", "wl-site", "releases"), 0o755); err != nil {
		t.Fatalf("create the site tree: %v", err)
	}

	changes := []*wisperpb.FileRequest{
		{Op: &wisperpb.FileRequest_CreateDirectory{CreateDirectory: &wisperpb.CreateDirectory{Path: "new"}}},
		{Op: &wisperpb.FileRequest_Delete{Delete: &wisperpb.DeletePath{Path: "anything"}}},
		{Op: &wisperpb.FileRequest_Move{Move: &wisperpb.MovePath{From: "a", To: "b"}}},
		{Op: &wisperpb.FileRequest_ChangeMode{ChangeMode: &wisperpb.ChangeMode{Path: "a", Mode: 0o644}}},
		{Op: &wisperpb.FileRequest_Archive{Archive: &wisperpb.CreateArchive{
			Paths:       []string{"a"},
			Destination: "out.zip",
			Format:      wisperpb.ArchiveFormat_ARCHIVE_FORMAT_ZIP,
		}}},
	}
	for _, change := range changes {
		change.RootId = siteRootID
		events := test.run(t, change)
		wantError(t, events, wisperpb.FileErrorCode_FILE_ERROR_CODE_PERMISSION_DENIED)
	}
}

// assertNoSecret is the assertion that actually matters: whatever the node said, it did not
// say the contents of a file outside the root.
func assertNoSecret(t *testing.T, events []*wisperpb.FileEvent, attempt string) {
	t.Helper()
	for _, event := range events {
		if chunk := event.GetChunk(); chunk != nil {
			if strings.Contains(string(chunk.GetData()), secretContent) {
				t.Fatalf("%q read a file outside the root", attempt)
			}
		}
	}
}
