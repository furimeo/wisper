package bootstrap

import (
	"bytes"
	"context"
	"fmt"
	"os"
	"path/filepath"
)

// Putting the daemon where the systemd unit expects to find it.
//
// Separate from install.go because it is the step with the property the rest of the
// installation depends on: it never destroys the binary that is working. The one it
// replaces is copied aside first, the new one is verified by being run, and a source that
// turns out to be the file already installed is left alone rather than copied onto itself.

// binaryChange is what placing the binary did.
//
// `replaced` is the field that matters afterwards: it decides whether a service that will
// not start is worth rolling back. A node that will not start on a binary it was already
// running has a problem an older binary will not fix, and reinstating one would only add a
// downgrade to whatever is actually wrong.
type binaryChange struct {
	replaced bool
	from     string
	to       string
}

// placeBinary puts the daemon where the unit expects it.
func (i installation) placeBinary(ctx context.Context) (binaryChange, error) {
	source, err := i.sourceBinary()
	if err != nil {
		return binaryChange{}, err
	}

	if sameFile(source, i.layout.BinaryPath) {
		// The usual case for install.sh, which puts the binary in place and then runs it
		// from there. Copying a file onto itself truncates it.
		return binaryChange{}, nil
	}

	body, err := os.ReadFile(source)
	if err != nil {
		return binaryChange{}, fmt.Errorf("read %s: %w", source, err)
	}
	if existing, readErr := os.ReadFile(i.layout.BinaryPath); readErr == nil && bytes.Equal(existing, body) {
		fmt.Fprintf(i.out, "\n%s is already this binary.\n", i.layout.BinaryPath)
		return binaryChange{}, nil
	}

	// A binary that cannot say what it is will not run as a service either, and finding
	// that out here means the node keeps the one it has.
	incoming, err := i.probeVersion(ctx, source)
	if err != nil {
		return binaryChange{}, fmt.Errorf("%s is not a binary this machine can run: %w", source, err)
	}

	change := binaryChange{replaced: exists(i.layout.BinaryPath), to: incoming}
	if change.replaced {
		change.from, _ = i.probeVersion(ctx, i.layout.BinaryPath)
		if err := copyFile(i.layout.BinaryPath, i.layout.previousBinaryPath(), 0o755); err != nil {
			return binaryChange{}, err
		}
	}
	if err := writeFileAtomically(i.layout.BinaryPath, body, 0o755); err != nil {
		return binaryChange{}, err
	}

	if change.replaced {
		fmt.Fprintf(i.out, "\nReplaced %s with %s, keeping %s to go back to.\n",
			i.layout.BinaryPath, incoming, i.layout.previousBinaryPath())
	} else {
		fmt.Fprintf(i.out, "\nInstalled %s %s\n", i.layout.BinaryPath, incoming)
	}
	return change, nil
}

func (i installation) sourceBinary() (string, error) {
	if i.source != "" {
		return filepath.Abs(i.source)
	}
	executable := os.Executable
	if i.executable != nil {
		executable = i.executable
	}
	path, err := executable()
	if err != nil {
		return "", fmt.Errorf("find the binary running this command: %w. Pass --binary", err)
	}
	return path, nil
}

func (i installation) probeVersion(ctx context.Context, path string) (string, error) {
	if i.probe != nil {
		return i.probe(ctx, path)
	}
	return probeVersion(ctx, path)
}

// sameFile answers whether two paths are the same file on disk, which a string comparison
// cannot: /usr/local/bin/sasayaki and a symlink to it are the same bytes and copying one
// onto the other truncates it to nothing.
func sameFile(left, right string) bool {
	leftInfo, err := os.Stat(left)
	if err != nil {
		return false
	}
	rightInfo, err := os.Stat(right)
	if err != nil {
		return false
	}
	return os.SameFile(leftInfo, rightInfo)
}
