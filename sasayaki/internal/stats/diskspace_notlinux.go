//go:build !linux

package stats

import (
	"fmt"
	"runtime"
)

// diskSpace has no answer off Linux.
//
// A wisper node is a Linux machine. This build exists so that `go test ./...` runs on the
// developer's Windows or macOS machine, and there the honest answer to "how full is the
// filesystem under /var/lib/wisper" is that there is no such filesystem. A pass that cannot
// measure the disk reports the rest of the machine and leaves the disk figures at zero,
// which reads as no pressure - the truthful answer when nothing was measured, and the one
// that does not block deployments on a machine that is not a node.
func diskSpace(path string) (total, available int64, err error) {
	return 0, 0, fmt.Errorf("stats: the filesystem under %s cannot be measured on %s: "+
		"a wisper node is a Linux machine", path, runtime.GOOS)
}
