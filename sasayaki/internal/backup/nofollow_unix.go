//go:build unix

package backup

import "syscall"

// O_NOFOLLOW on the platform sasayaki actually runs on.
//
// The last line of the extractor's defence. entryPath already refuses a name that resolves
// outside the volume and refuseExistingLink already refuses to overwrite a link that is
// there, but both are checks made a moment before the open, and a restore into a tree that
// something else can write is a race in principle. Asking the kernel to refuse the open
// itself closes it, at the cost of one constant.
const noFollow = syscall.O_NOFOLLOW
