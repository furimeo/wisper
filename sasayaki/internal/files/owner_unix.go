//go:build unix

package files

import (
	"io/fs"
	"syscall"
)

// Who owns a file, on the platform sasayaki actually runs on.
//
// The file manager shows the numbers rather than resolving them to names: the uid inside
// a customer's container has no entry in the node's /etc/passwd, and inventing one would
// be a lie about who can read the file. A customer comparing "1000" here with "1000" in
// their container is doing the only comparison that means anything.
func ownerOf(info fs.FileInfo) (uid, gid uint32) {
	stat, ok := info.Sys().(*syscall.Stat_t)
	if !ok {
		return 0, 0
	}
	return uint32(stat.Uid), uint32(stat.Gid)
}
