package bootstrap

import (
	"fmt"
	"io"
	"os"
	"path/filepath"
)

// writeFileAtomically puts contents at path, or leaves whatever was there untouched.
//
// Every file this package writes goes through here, and the reason is the same one that
// made the predecessor's daemon lose its mind on restart: a process interrupted halfway
// through a write leaves a file that exists, parses as far as it goes, and is wrong. A
// node credential missing its last brace and a systemd unit missing its ExecStart are
// both worse than the file not being there at all, because the absent file is a state the
// installer knows how to recover from.
//
// The temporary file is created in the destination directory so the rename is within one
// filesystem, which is what makes it atomic. It carries the final permissions from the
// moment it exists rather than being chmod-ed afterwards, so there is no window in which
// a key is world-readable.
func writeFileAtomically(path string, contents []byte, mode os.FileMode) error {
	directory := filepath.Dir(path)
	temporary, err := os.CreateTemp(directory, "."+filepath.Base(path)+".*")
	if err != nil {
		return fmt.Errorf("create a temporary file in %s: %w", directory, err)
	}
	name := temporary.Name()
	// Runs on every path including success, where the rename has already made it a no-op.
	defer os.Remove(name)

	if err := temporary.Chmod(mode); err != nil {
		temporary.Close()
		return fmt.Errorf("set the mode on %s: %w", name, err)
	}
	if _, err := temporary.Write(contents); err != nil {
		temporary.Close()
		return fmt.Errorf("write %s: %w", name, err)
	}
	if err := temporary.Sync(); err != nil {
		temporary.Close()
		return fmt.Errorf("flush %s: %w", name, err)
	}
	if err := temporary.Close(); err != nil {
		return fmt.Errorf("close %s: %w", name, err)
	}
	if err := os.Rename(name, path); err != nil {
		return fmt.Errorf("install %s: %w", path, err)
	}
	return nil
}

// copyFile duplicates a file with an explicit mode, used to keep the previous binary
// beside the live one before an upgrade replaces it.
//
// A copy rather than a rename, so that the live path never stops existing: a rename away
// followed by a rename in leaves a window in which /usr/local/bin/sasayaki is missing,
// and a systemd restart that lands in that window fails for a reason nobody will find.
func copyFile(source, destination string, mode os.FileMode) error {
	in, err := os.Open(source)
	if err != nil {
		return fmt.Errorf("open %s: %w", source, err)
	}
	defer in.Close()

	contents, err := io.ReadAll(in)
	if err != nil {
		return fmt.Errorf("read %s: %w", source, err)
	}
	return writeFileAtomically(destination, contents, mode)
}
