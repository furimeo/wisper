//go:build !linux

package bootstrap

import (
	"fmt"
	"runtime"
)

// diskSpace has no answer off Linux.
//
// A wisper node is a Linux machine: it needs cgroups v2, a Docker socket and, for quotas
// to mean anything, XFS project quotas. This build exists so the tests run on the
// developer's Windows or macOS machine, and there the honest answer to "how big is the
// filesystem under /var/lib/wisper" is that there is no such filesystem. The storage
// check turns this error into a warning naming the platform, rather than a figure
// invented to fill the field.
func diskSpace(path string) (total, free int64, err error) {
	return 0, 0, fmt.Errorf("disk space under %s cannot be measured on %s: a wisper node is "+
		"a Linux machine", path, runtime.GOOS)
}
