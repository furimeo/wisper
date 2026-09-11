package files

import (
	"errors"
	"io/fs"
	"os"
	"path/filepath"
	"strings"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// The first of the two containment layers (doc.go): what a path from the panel is allowed
// to look like, decided before anything touches the disk.
//
// Everything on the wire is slash-separated and relative to a root. That is not a
// convention this file assumes and hopes for - it is checked, element by element, and a
// request that does not fit is refused rather than repaired. Repairing is what turns two
// different requests into one path, and the file manager is the one place in wisper where
// that is worth a refusal a customer occasionally has to read.

const (
	// maxPathLength is the whole relative path. Linux allows 4096 including the mount
	// point, so a root-relative path this long cannot be created anyway; the limit exists
	// so a megabyte of slashes is rejected in a comparison rather than in a syscall.
	maxPathLength = 4000
	// maxNameLength is one element. NAME_MAX on every filesystem this daemon supports.
	maxNameLength = 255
)

// windowsHost is whether the daemon is running where a backslash separates path elements
// and a colon introduces a drive or a stream.
//
// sasayaki ships for Linux, where both characters are ordinary bytes in a filename and
// refusing them would refuse legitimate files. The tests run on the developer's Windows
// machine, though, and a name like `C:\Windows` there is not a filename - so the check is
// switched on by the platform rather than compiled in for everyone.
var windowsHost = filepath.Separator == '\\'

// cleanPath validates a path from the panel and returns its canonical form: elements
// joined by single slashes, with no leading or trailing slash. The empty string is the
// root itself, which is what an empty request path and "/" both mean.
//
// A leading slash is tolerated and stripped, because a file manager UI naturally shows
// the root as "/" and the path in a breadcrumb starts there. It never means an absolute
// path on the node: there is no field in this protocol that can carry one, and the
// *os.Root every operation goes through would refuse it a second time if there were.
//
// `..` is refused outright rather than resolved. A lexically resolvable `a/../b` is
// indistinguishable from an attack when `a` is a symlink, and no legitimate client needs
// to send one: every path the panel has came from a FileInfo this node produced.
func cleanPath(raw string) (string, *failure) {
	if len(raw) > maxPathLength {
		return "", refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_PATH_ESCAPES_ROOT, "",
			"the path is %d characters long, and no path in a file root may exceed %d",
			len(raw), maxPathLength)
	}
	if strings.ContainsRune(raw, 0) {
		return "", refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_PATH_ESCAPES_ROOT, "",
			"the path contains a NUL byte, which no filename may contain and which truncates "+
				"the name every layer below this one would see")
	}

	elements := make([]string, 0, 8)
	for _, element := range strings.Split(raw, "/") {
		switch element {
		case "", ".":
			// A doubled slash, a leading one, or an explicit "this directory". None of
			// them changes where the path points.
			continue
		case "..":
			return "", refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_PATH_ESCAPES_ROOT, raw,
				"the path contains \"..\", which is refused rather than resolved")
		}
		if err := checkElement(element, raw); err != nil {
			return "", err
		}
		elements = append(elements, element)
	}
	return strings.Join(elements, "/"), nil
}

func checkElement(element, raw string) *failure {
	if len(element) > maxNameLength {
		return refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_PATH_ESCAPES_ROOT, raw,
			"the name %q is %d bytes long, and no filename may exceed %d",
			element, len(element), maxNameLength)
	}
	if windowsHost && strings.ContainsAny(element, `\:`) {
		return refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_PATH_ESCAPES_ROOT, raw,
			"the name %q contains a character that separates path elements on this host", element)
	}
	return nil
}

// systemName is the name to hand an *os.Root method. The root itself is ".", which is what
// the standard library wants and what an empty relative path means here.
func systemName(relative string) string {
	if relative == "" {
		return "."
	}
	return filepath.FromSlash(relative)
}

// childPath is a path one level below another, in the slash form the wire uses.
func childPath(parent, name string) string {
	if parent == "" {
		return name
	}
	return parent + "/" + name
}

// parentPath is everything above the last element, and the last element. Both empty for
// the root, which has neither.
func parentPath(relative string) (parent, name string) {
	index := strings.LastIndex(relative, "/")
	if index < 0 {
		return "", relative
	}
	return relative[:index], relative[index+1:]
}

// insideSlashTree reports whether candidate is root or sits under it, both in wire form.
// Used where one path is checked against another rather than against the filesystem: a
// directory cannot be moved into itself, and an archive cannot contain the file it is
// being written to.
func insideSlashTree(root, candidate string) bool {
	if root == "" {
		return true
	}
	return candidate == root || strings.HasPrefix(candidate, root+"/")
}

// ensureTraversable refuses a path that would be reached *through* a symlink.
//
// files.proto is explicit that a symlink is reported and never followed: a listing shows
// it, and it can be renamed or deleted because those act on the link itself, but no
// operation is performed through one. Enforcing that here rather than relying on where
// the link points is what makes the rule the same on every kernel - a link that happens to
// stay inside the root is refused for the same reason as one that does not, so there is no
// second case whose safety depends on the *os.Root implementation underneath.
//
// leaf says whether the last element must be checked too. An operation that acts on the
// link - Lstat, Remove, Rename - passes false; one that acts on what the link points at
// passes true.
//
// A component that does not exist is not a failure: mkdir -p is allowed to name a parent
// that is not there yet, and the operation itself will report a missing path if it minds.
func ensureTraversable(root *os.Root, relative string, leaf bool) *failure {
	if relative == "" {
		return nil
	}
	elements := strings.Split(relative, "/")
	if !leaf {
		elements = elements[:len(elements)-1]
	}

	prefix := ""
	for _, element := range elements {
		prefix = childPath(prefix, element)
		info, err := root.Lstat(systemName(prefix))
		if err != nil {
			if errors.Is(err, fs.ErrNotExist) {
				// Nothing here to follow, and nothing below it either.
				return nil
			}
			return classify(nil, err, prefix, "look at the path")
		}
		if info.Mode()&fs.ModeSymlink != 0 {
			return refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_PATH_ESCAPES_ROOT, prefix,
				"%q is a symbolic link, and the file manager reports links rather than "+
					"following them", prefix)
		}
	}
	return nil
}
