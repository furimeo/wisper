package bootstrap

import (
	"fmt"
	"syscall"
)

// diskSpace reports the total and available bytes of the filesystem holding path.
//
// Available rather than free: Bavail excludes the blocks reserved for root, which is what
// a workload actually gets. Reporting Bfree would tell the panel there is room for a
// deployment that then fails to write.
func diskSpace(path string) (total, free int64, err error) {
	var statistics syscall.Statfs_t
	if err := syscall.Statfs(path, &statistics); err != nil {
		return 0, 0, fmt.Errorf("statfs %s: %w", path, err)
	}
	blockSize := int64(statistics.Bsize)
	return int64(statistics.Blocks) * blockSize, int64(statistics.Bavail) * blockSize, nil
}
