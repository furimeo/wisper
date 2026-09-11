package files

import (
	"io/fs"
	"os"
	"path/filepath"
	"runtime"
	"testing"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// The derivation is pure arithmetic and is tested everywhere. What the filesystem then does
// with the number is only meaningful on a platform with POSIX permissions, so the tests
// that read a mode back off the disk say so rather than being written to pass on Windows by
// asserting nothing.

func TestDirectoriesGainTheExecuteBitFromTheReadBit(t *testing.T) {
	cases := []struct {
		requested fs.FileMode
		file      fs.FileMode
		directory fs.FileMode
	}{
		{0o644, 0o644, 0o755},
		{0o600, 0o600, 0o700},
		{0o664, 0o664, 0o775},
		{0o444, 0o444, 0o555},
		{0o000, 0o000, 0o000},
		{0o777, 0o777, 0o777},
	}
	for _, want := range cases {
		if got := modeFor(want.requested, false); got != want.file {
			t.Errorf("modeFor(%#o, file) = %#o, want %#o", want.requested, got, want.file)
		}
		if got := modeFor(want.requested, true); got != want.directory {
			t.Errorf("modeFor(%#o, directory) = %#o, want %#o", want.requested, got, want.directory)
		}
	}
}

func TestChangingModeRefusesBitsAboveThePermissionBits(t *testing.T) {
	test := newHarness(t)
	test.write(t, "script.sh", "#!/bin/sh\n")

	// 04755: setuid. Offering it in a file manager is offering a privilege escalation
	// inside the customer's own tenancy.
	events := test.run(t, &wisperpb.FileRequest{
		Op: &wisperpb.FileRequest_ChangeMode{ChangeMode: &wisperpb.ChangeMode{
			Path: "script.sh", Mode: 0o4755,
		}},
	})
	wantError(t, events, wisperpb.FileErrorCode_FILE_ERROR_CODE_PERMISSION_DENIED)
}

func TestChangingModeRecursivelyLeavesDirectoriesTraversable(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("Windows has no POSIX permission bits to read back; modeFor is tested above")
	}
	test := newHarness(t)
	test.write(t, "tree/nested/file.txt", "x")

	done := wantDone(t, test.run(t, &wisperpb.FileRequest{
		Op: &wisperpb.FileRequest_ChangeMode{ChangeMode: &wisperpb.ChangeMode{
			Path: "tree", Mode: 0o640, Recursive: true,
		}},
	}))
	if done.GetAffected() != 3 {
		t.Errorf("the chmod reported %d entries, want 3", done.GetAffected())
	}

	directory, err := os.Stat(filepath.Join(test.volume, "tree", "nested"))
	if err != nil {
		t.Fatalf("stat the directory: %v", err)
	}
	if got := directory.Mode().Perm(); got != 0o750 {
		t.Errorf("the directory is %#o, want 0750: a directory without its execute bit "+
			"cannot be opened, and the application stops working", got)
	}
	file, err := os.Stat(filepath.Join(test.volume, "tree", "nested", "file.txt"))
	if err != nil {
		t.Fatalf("stat the file: %v", err)
	}
	if got := file.Mode().Perm(); got != 0o640 {
		t.Errorf("the file is %#o, want 0640", got)
	}
}

func TestChangingModeThroughASymlinkIsRefused(t *testing.T) {
	test := newHarness(t)
	test.write(t, "real.txt", "x")
	test.symlinkOrSkip(t, "real.txt", "alias.txt")

	events := test.run(t, &wisperpb.FileRequest{
		Op: &wisperpb.FileRequest_ChangeMode{ChangeMode: &wisperpb.ChangeMode{
			Path: "alias.txt", Mode: 0o600,
		}},
	})
	wantError(t, events, wisperpb.FileErrorCode_FILE_ERROR_CODE_PATH_ESCAPES_ROOT)
}
