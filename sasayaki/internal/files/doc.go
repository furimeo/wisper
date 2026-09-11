// Package files is the web file manager, seen from the node.
//
// There is no SSH, no SFTP and no WebDAV in wisper, so everything a customer will ever
// do to their own disk arrives here as a FileRequest on the stream the node opened to
// the panel (design section 8.2). That makes this package the largest attack surface in
// v1, and the two properties below are what the rest of the code is arranged around.
//
// # Containment
//
// A request names a FileRoot by id and a path relative to it. The panel never learns and
// never sends an absolute path, so there is no field in which to ask for /etc/shadow -
// and roots.go resolves the id against the current NodeSpec, so a root the panel has not
// published cannot be reached even if the directory exists.
//
// Containment itself is enforced twice, deliberately:
//
//  1. path.go rejects the request before any syscall happens. Absolute paths, `..` in any
//     position, empty and NUL-bearing components are refused with
//     FILE_ERROR_CODE_PATH_ESCAPES_ROOT, which the panel writes to the audit log rather
//     than showing as a friendly hint.
//  2. Every filesystem call in this package goes through an *os.Root opened on the root
//     directory. On Linux that is openat2 with RESOLVE_BENEATH: the kernel refuses a
//     traversal that leaves the tree, including one that arrives through a symlink
//     swapped in after the check above. Nothing here ever builds an absolute path and
//     hands it to os.Open.
//
// The first layer is policy and produces a message a person can read. The second is the
// security boundary, and it holds even when the first is wrong. A symlink is reported by
// a listing and may be renamed or deleted, but no operation is performed *through* one:
// FileInfo.symlink exists precisely so the UI can show the link without the node having
// to follow it (files.proto, FileInfo).
//
// # Resumable uploads
//
// Most customers upload from a phone on mobile data, so an upload is a sequence of
// acknowledged chunks against a session that survives losing signal - and survives the
// daemon being restarted underneath it, which is why the session lives in SQLite
// (internal/state, upload.go) and not in a map here.
//
//	<state>/uploads/parts/<session-id>    the bytes as they arrive, sparse, offset-addressed
//	<root>/<path>                         where the finished file lands, atomically
//
// The parts file is outside every root on purpose: a half-uploaded file must not appear
// in the customer's directory listing, must not be servable by the edge, and must not be
// picked up by a backup. Completion copies it into the destination directory under a
// temporary name, checksums it on the way past, and renames - so the destination either
// does not exist or is the whole verified file, and never anything in between.
//
// # One terminal event per request
//
// Every operation finishes with exactly one of: OperationDone, FileError, or the payload
// that answers it - a DirectoryListing, a FileInfo, an UploadAck, an UploadState, a
// DirectorySize, or, for a download, the FileChunk carrying last = true. The panel cannot
// tell "finished" from "still working" otherwise, and a spinner that never stops is the
// bug this rule exists to prevent (handle.go enforces it).
//
// Cancellation arrives as a cancelled context, because the stream owns the registry of
// running operations (internal/rpc, file_stream.go). Anything that can run long - a
// download, a tree walk, an extraction - checks it and finishes with
// FILE_ERROR_CODE_CANCELLED.
package files
