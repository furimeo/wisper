package files

import (
	"os"
	"path/filepath"
	"strconv"
	"testing"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Changing things: the operations with no undo, and the one - measure - a customer runs
// just before deciding to use them.

func TestCreatingADirectory(t *testing.T) {
	test := newHarness(t)

	wantDone(t, test.run(t, &wisperpb.FileRequest{
		Op: &wisperpb.FileRequest_CreateDirectory{CreateDirectory: &wisperpb.CreateDirectory{Path: "uploads"}},
	}))
	if info, err := os.Stat(filepath.Join(test.volume, "uploads")); err != nil || !info.IsDir() {
		t.Fatalf("the directory was not created: %v", err)
	}

	// Twice, without parents, is an error the customer sees rather than a silent no-op.
	again := test.run(t, &wisperpb.FileRequest{
		Op: &wisperpb.FileRequest_CreateDirectory{CreateDirectory: &wisperpb.CreateDirectory{Path: "uploads"}},
	})
	wantError(t, again, wisperpb.FileErrorCode_FILE_ERROR_CODE_ALREADY_EXISTS)

	// With parents it is idempotent, which is what an extraction needs.
	wantDone(t, test.run(t, &wisperpb.FileRequest{
		Op: &wisperpb.FileRequest_CreateDirectory{CreateDirectory: &wisperpb.CreateDirectory{
			Path: "uploads/2026/march", Parents: true,
		}},
	}))
}

func TestMovingAndRenamingAreTheSameOperation(t *testing.T) {
	test := newHarness(t)
	test.write(t, "draft.txt", "hello")
	test.mkdir(t, "archive")

	wantDone(t, test.run(t, &wisperpb.FileRequest{
		Op: &wisperpb.FileRequest_Move{Move: &wisperpb.MovePath{From: "draft.txt", To: "archive/final.txt"}},
	}))
	content, err := os.ReadFile(filepath.Join(test.volume, "archive", "final.txt"))
	if err != nil || string(content) != "hello" {
		t.Fatalf("the file did not arrive: %v", err)
	}
}

func TestMovingOntoSomethingNeedsOverwrite(t *testing.T) {
	test := newHarness(t)
	test.write(t, "a.txt", "first")
	test.write(t, "b.txt", "second")

	wantError(t, test.run(t, &wisperpb.FileRequest{
		Op: &wisperpb.FileRequest_Move{Move: &wisperpb.MovePath{From: "a.txt", To: "b.txt"}},
	}), wisperpb.FileErrorCode_FILE_ERROR_CODE_ALREADY_EXISTS)

	wantDone(t, test.run(t, &wisperpb.FileRequest{
		Op: &wisperpb.FileRequest_Move{Move: &wisperpb.MovePath{From: "a.txt", To: "b.txt", Overwrite: true}},
	}))
	content, _ := os.ReadFile(filepath.Join(test.volume, "b.txt"))
	if string(content) != "first" {
		t.Fatalf("b.txt holds %q after an overwriting move", content)
	}
}

func TestADirectoryCannotBeMovedIntoItself(t *testing.T) {
	test := newHarness(t)
	test.mkdir(t, "project/src")

	events := test.run(t, &wisperpb.FileRequest{
		Op: &wisperpb.FileRequest_Move{Move: &wisperpb.MovePath{From: "project", To: "project/src/project"}},
	})
	wantError(t, events, wisperpb.FileErrorCode_FILE_ERROR_CODE_IO_ERROR)
}

func TestDeletingANonEmptyDirectoryNeedsRecursive(t *testing.T) {
	test := newHarness(t)
	test.write(t, "tree/a.txt", "a")
	test.write(t, "tree/nested/b.txt", "b")

	wantError(t, test.run(t, &wisperpb.FileRequest{
		Op: &wisperpb.FileRequest_Delete{Delete: &wisperpb.DeletePath{Path: "tree"}},
	}), wisperpb.FileErrorCode_FILE_ERROR_CODE_DIRECTORY_NOT_EMPTY)

	done := wantDone(t, test.run(t, &wisperpb.FileRequest{
		Op: &wisperpb.FileRequest_Delete{Delete: &wisperpb.DeletePath{Path: "tree", Recursive: true}},
	}))
	// a.txt, nested/b.txt, nested and tree itself.
	if done.GetAffected() != 4 {
		t.Errorf("the delete reported %d entries, want 4", done.GetAffected())
	}
	if _, err := os.Stat(filepath.Join(test.volume, "tree")); !os.IsNotExist(err) {
		t.Fatalf("the tree is still there: %v", err)
	}
}

func TestTheRootItselfCannotBeDeleted(t *testing.T) {
	test := newHarness(t)
	events := test.run(t, &wisperpb.FileRequest{
		Op: &wisperpb.FileRequest_Delete{Delete: &wisperpb.DeletePath{Path: "/", Recursive: true}},
	})
	wantError(t, events, wisperpb.FileErrorCode_FILE_ERROR_CODE_PERMISSION_DENIED)
}

func TestMeasuringADirectoryWalksTheWholeTree(t *testing.T) {
	test := newHarness(t)
	test.write(t, "tree/a.txt", "12345")
	test.write(t, "tree/nested/b.txt", "1234567890")
	test.mkdir(t, "tree/empty")

	events := test.run(t, &wisperpb.FileRequest{
		Op: &wisperpb.FileRequest_Measure{Measure: &wisperpb.MeasureDirectory{Path: "tree"}},
	})
	size := only(t, events).GetSize()
	if size == nil {
		t.Fatalf("expected a DirectorySize, got %v", events)
	}
	if size.GetBytes() != 15 {
		t.Errorf("bytes = %d, want 15", size.GetBytes())
	}
	if size.GetFileCount() != 2 {
		t.Errorf("file_count = %d, want 2", size.GetFileCount())
	}
	if size.GetDirectoryCount() != 2 {
		t.Errorf("directory_count = %d, want 2 (nested and empty)", size.GetDirectoryCount())
	}
	if size.GetApproximate() {
		t.Error("a three-entry tree was measured approximately")
	}
}

func TestMeasuringStopsAtItsBudgetAndSaysSo(t *testing.T) {
	test := newHarness(t)
	for index := range 20 {
		test.write(t, "tree/file"+strconv.Itoa(index)+".txt", "12345")
	}
	test.host.limits.MeasureEntryBudget = 5

	events := test.run(t, &wisperpb.FileRequest{
		Op: &wisperpb.FileRequest_Measure{Measure: &wisperpb.MeasureDirectory{Path: "tree"}},
	})
	size := only(t, events).GetSize()
	if !size.GetApproximate() {
		t.Fatal("a walk that stopped at its budget reported an exact answer")
	}
	if size.GetFileCount() >= 20 {
		t.Fatalf("the walk visited %d files despite a budget of 5", size.GetFileCount())
	}
}

func names(listing *wisperpb.DirectoryListing) []string {
	out := make([]string, 0, len(listing.GetEntries()))
	for _, entry := range listing.GetEntries() {
		out = append(out, entry.GetName())
	}
	return out
}
