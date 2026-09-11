package files

import (
	"context"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Refusing a write that cannot fit, before the first byte of it is on disk.
//
// This is not the enforcement. XFS project quota on the volume directory is
// (internal/runtime, quota.go), and it is what actually stops a customer overrunning their
// allocation - including from inside their container, where nothing here can see. What
// this adds is the difference between "upload failed after twenty minutes on a phone" and
// "this file is 3 GB and you have 1.2 GB left", which is the whole reason FileRoot carries
// quota_bytes and UploadSession carries total_bytes (files.proto, UploadSession).
//
// It is measured once per upload session and once per extraction, never per chunk: the
// walk costs a readdir per directory, and a two-gigabyte upload arrives as five hundred
// chunks.

// headroom is how many bytes may still be written into the root.
//
// The second return is false when the root has no quota, in which case the first is
// meaningless and the caller writes whatever it was asked to: a volume with no limit is a
// volume the panel deliberately did not limit.
func (h *Host) headroom(ctx context.Context, root *openRoot) (int64, bool, *failure) {
	if root.quotaBytes <= 0 {
		return 0, false, nil
	}

	walk := &measurement{budget: h.limits.MeasureEntryBudget}
	if failed := h.measure(ctx, root, "", walk, 0); failed != nil {
		return 0, false, failed
	}
	// An approximate measurement is a floor, so the headroom derived from it is a ceiling.
	// Erring that way lets a write through that the filesystem quota will then refuse,
	// which is the right direction: this check exists to give a good message, not to be
	// the last line of defence.
	remaining := root.quotaBytes - walk.bytes
	if remaining < 0 {
		remaining = 0
	}
	return remaining, true, nil
}

// ensureRoom refuses a write that would not fit, naming both numbers so the message is
// actionable rather than "quota exceeded".
func (h *Host) ensureRoom(ctx context.Context, root *openRoot, path string, wanted int64) *failure {
	if wanted <= 0 {
		return nil
	}
	remaining, limited, failed := h.headroom(ctx, root)
	if failed != nil {
		return failed
	}
	if !limited || wanted <= remaining {
		return nil
	}
	return refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_QUOTA_EXCEEDED, path,
		"this needs %d bytes and %q has %d of its %d left",
		wanted, root.label, remaining, root.quotaBytes)
}
