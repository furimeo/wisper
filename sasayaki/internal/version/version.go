// Package version is what this build calls itself.
//
// Four things need the answer and they must all agree: the `version` command, the
// handshake on the control stream, the upgrade path deciding whether the binary on disk
// is newer than the one running, and the doctor report the panel stores against the
// node. A constant in each of them would drift the first time somebody forgot one.
package version

import (
	"fmt"
	"runtime"
	"strings"
)

// Set at link time by the Makefile:
//
//	-X github.com/furimeo/wisper/sasayaki/internal/version.Number=v0.1.0
//
// The defaults are what a plain `go build` produces, and they say so rather than
// pretending to be a release.
var (
	// Number is the release, as a semantic version with a leading "v".
	Number = "dev"

	// Commit is the short git hash the binary was built from.
	Commit = "unknown"

	// BuildDate is RFC 3339, in UTC.
	BuildDate = "unknown"
)

// Protocol is the version of the panel/node control stream this binary speaks.
//
// It is negotiated in the first frame of Connect() and it is deliberately not the
// release number: a daemon can be three releases behind and still speak the same
// protocol perfectly well. Bump it only when a message changes in a way an older peer
// cannot interpret, and expect the panel to refuse the stream and show "node needs
// upgrading" rather than let two versions misunderstand each other quietly.
const Protocol = 1

// Short is what goes in a log line or a status column: "v0.1.0 (a1b2c3d)".
func Short() string {
	if Commit == "unknown" {
		return Number
	}
	return fmt.Sprintf("%s (%s)", Number, Commit)
}

// Full is what `sasayaki version` prints. Every field a bug report needs, so nobody has
// to ask "which build is that, exactly?".
func Full() string {
	var out strings.Builder
	fmt.Fprintf(&out, "sasayaki %s\n", Number)
	fmt.Fprintf(&out, "  commit:   %s\n", Commit)
	fmt.Fprintf(&out, "  built:    %s\n", BuildDate)
	fmt.Fprintf(&out, "  go:       %s\n", runtime.Version())
	fmt.Fprintf(&out, "  platform: %s/%s\n", runtime.GOOS, runtime.GOARCH)
	fmt.Fprintf(&out, "  protocol: %d\n", Protocol)
	return out.String()
}
