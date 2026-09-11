package stats

import (
	"fmt"
	"syscall"
)

// diskSpace reports the total and available bytes of the filesystem holding path.
//
// Available rather than free: Bavail excludes the blocks reserved for root, and a workload
// is not root. Reporting Bfree would tell the panel there is room for a deployment that
// then fails to write, which is precisely the failure disk pressure exists to prevent.
func diskSpace(path string) (total, available int64, err error) {
	var statistics syscall.Statfs_t
	if err := syscall.Statfs(path, &statistics); err != nil {
		return 0, 0, fmt.Errorf("stats: measure the filesystem under %s: %w", path, err)
	}
	blockSize := int64(statistics.Bsize)
	return int64(statistics.Blocks) * blockSize, int64(statistics.Bavail) * blockSize, nil
}
