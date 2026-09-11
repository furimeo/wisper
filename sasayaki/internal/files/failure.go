package files

import (
	"context"
	"errors"
	"fmt"
	"io/fs"
	"syscall"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// How a failed operation becomes something the panel can act on.
//
// Every path a request can take out of this package ends in one FileError, and the code
// on it is load-bearing rather than decorative: the panel shows NOT_FOUND to the customer
// as a missing file, and writes PATH_ESCAPES_ROOT to the audit log as an attack. Guessing
// IO_ERROR for everything would collapse that distinction, so the classification lives
// here, once, and every operation reports through it.

// failure is one FileError before it becomes a protobuf message.
type failure struct {
	code   wisperpb.FileErrorCode
	detail string
	// Relative to the root, and the whole reason this type exists rather than a bare
	// error: an extraction that fails on entry 400 of 900 has to say which one.
	path string
	// What actually went wrong underneath, kept for the daemon's log. It is deliberately
	// not part of detail: `detail` is shown to a customer, and an errno with a host path
	// in it tells them nothing and tells an attacker something.
	cause error
}

func (f *failure) Error() string {
	if f.path == "" {
		return f.detail
	}
	return f.path + ": " + f.detail
}

func (f *failure) Unwrap() error { return f.cause }

// message is the wire form.
func (f *failure) message() *wisperpb.FileError {
	return &wisperpb.FileError{Code: f.code, Detail: f.detail, Path: f.path}
}

// refuse builds a failure this package decided on itself, with no underlying error.
func refuse(code wisperpb.FileErrorCode, path, format string, args ...any) *failure {
	return &failure{code: code, detail: fmt.Sprintf(format, args...), path: path}
}

// classify turns whatever the filesystem said into a code the panel understands.
//
// The order matters. Context cancellation is checked first because a read interrupted
// half way through surfaces as a generic I/O error on some platforms, and reporting a
// customer closing a download as a disk fault would send somebody looking at SMART
// counters. Path escape is checked next, before ErrNotExist, because *os.Root reports a
// traversal that left the tree without saying whether the target existed - which is the
// right answer to give an attacker and the wrong one to record as "not found".
func classify(ctx context.Context, err error, path, what string) *failure {
	if err == nil {
		return nil
	}
	if ctx != nil && ctx.Err() != nil {
		return &failure{
			code:   wisperpb.FileErrorCode_FILE_ERROR_CODE_CANCELLED,
			detail: what + " was cancelled",
			path:   path,
			cause:  err,
		}
	}

	code := codeFor(err)
	return &failure{code: code, detail: explain(code, what), path: path, cause: err}
}

func codeFor(err error) wisperpb.FileErrorCode {
	switch {
	case escapedRoot(err):
		return wisperpb.FileErrorCode_FILE_ERROR_CODE_PATH_ESCAPES_ROOT
	case errors.Is(err, fs.ErrNotExist):
		return wisperpb.FileErrorCode_FILE_ERROR_CODE_NOT_FOUND
	case errors.Is(err, syscall.ENOTEMPTY):
		return wisperpb.FileErrorCode_FILE_ERROR_CODE_DIRECTORY_NOT_EMPTY
	case errors.Is(err, fs.ErrExist):
		return wisperpb.FileErrorCode_FILE_ERROR_CODE_ALREADY_EXISTS
	case errors.Is(err, fs.ErrPermission):
		return wisperpb.FileErrorCode_FILE_ERROR_CODE_PERMISSION_DENIED
	case errors.Is(err, syscall.EISDIR):
		return wisperpb.FileErrorCode_FILE_ERROR_CODE_IS_A_DIRECTORY
	case errors.Is(err, syscall.ENOTDIR):
		return wisperpb.FileErrorCode_FILE_ERROR_CODE_NOT_A_DIRECTORY
	case errors.Is(err, syscall.ENOSPC), errors.Is(err, syscall.EDQUOT):
		return wisperpb.FileErrorCode_FILE_ERROR_CODE_QUOTA_EXCEEDED
	default:
		return wisperpb.FileErrorCode_FILE_ERROR_CODE_IO_ERROR
	}
}

// pathEscapesMessage is what the standard library says when a name resolved outside the
// *os.Root it was used on.
//
// Matched on the text because os.errPathEscapes is not exported and there is no sentinel
// to compare against. That is worth doing rather than skipping: the refusal has already
// happened - the kernel or the runtime declined the syscall - and this only decides which
// code the panel records. If a future Go release renames it the operation still fails,
// just as FILE_ERROR_CODE_IO_ERROR, and escape_test.go fails first so somebody notices.
const pathEscapesMessage = "path escapes from parent"

func escapedRoot(err error) bool {
	var pathErr *fs.PathError
	if errors.As(err, &pathErr) && pathErr.Err != nil {
		return pathErr.Err.Error() == pathEscapesMessage
	}
	return err.Error() == pathEscapesMessage
}

// explain writes the sentence a customer reads.
//
// One sentence per code rather than the errno's own text, because "openat
// /var/lib/wisper/volumes/41/7/x: no such file or directory" leaks the node's layout and
// still does not say what to do about it. `what` is the operation in progress, so the
// message reads as a whole: "read the file: there is nothing at this path".
func explain(code wisperpb.FileErrorCode, what string) string {
	switch code {
	case wisperpb.FileErrorCode_FILE_ERROR_CODE_NOT_FOUND:
		return what + ": there is nothing at this path"
	case wisperpb.FileErrorCode_FILE_ERROR_CODE_ALREADY_EXISTS:
		return what + ": something is already there"
	case wisperpb.FileErrorCode_FILE_ERROR_CODE_PERMISSION_DENIED:
		return what + ": the node is not allowed to touch this path"
	case wisperpb.FileErrorCode_FILE_ERROR_CODE_PATH_ESCAPES_ROOT:
		return what + ": the path resolves outside this file root and was refused"
	case wisperpb.FileErrorCode_FILE_ERROR_CODE_NOT_A_DIRECTORY:
		return what + ": a component of this path is a file, not a directory"
	case wisperpb.FileErrorCode_FILE_ERROR_CODE_IS_A_DIRECTORY:
		return what + ": this path is a directory"
	case wisperpb.FileErrorCode_FILE_ERROR_CODE_DIRECTORY_NOT_EMPTY:
		return what + ": the directory still has something in it"
	case wisperpb.FileErrorCode_FILE_ERROR_CODE_QUOTA_EXCEEDED:
		return what + ": there is no room left on this disk"
	default:
		return what + ": the node could not complete this operation"
	}
}
