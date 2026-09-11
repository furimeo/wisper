//go:build !unix

package backup

// Windows has no O_NOFOLLOW, and this file exists so `go test ./...` runs on the machine the
// panel is written on.
//
// Zero is not a silent downgrade of the protection: the two checks that matter - entryPath
// refusing a name that leaves the volume, and refuseExistingLink refusing to write through a
// link that is already there - are pure Go and run everywhere, and they are what the tests
// assert on. The flag is the kernel-level backstop for the node, which is Linux.
const noFollow = 0
