//go:build !unix

package files

import "io/fs"

// Ownership on the platforms sasayaki is only ever built for on a developer's machine.
//
// A node is Linux, and this file exists so `go test ./...` runs on Windows where the
// panel and the daemon are written. Windows has no uid or gid to report, and reporting
// zero is honest here in a way it would not be on a node: the file manager shows the
// numbers next to a mode that Windows also does not really have, and the developer
// running the tests is not deciding anything based on either.
func ownerOf(fs.FileInfo) (uid, gid uint32) {
	return 0, 0
}
