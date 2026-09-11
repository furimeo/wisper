package files

import (
	"context"
	"encoding/base64"
	"io/fs"
	"sort"
	"strings"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// One page of a directory.
//
// Paged because a customer with a node_modules directory has a hundred thousand entries,
// and a phone that is sent all of them renders nothing at all. The order is fixed -
// directories first, then by name, byte for byte - because that is what makes a cursor
// mean anything: the panel asks for "everything after this entry", and two pages taken a
// second apart have to agree on what "after" is.
//
// The whole directory is read to produce one page. That is deliberate: total_entries is
// part of the contract, so the UI can say "showing 200 of 40,312" rather than implying the
// list is complete, and there is no way to know that number without reading the directory.
// One readdir and a sort of a hundred thousand names is a few milliseconds; the alternative
// is a lie in the interface.

// listDirectory answers a ListDirectory with exactly one page.
func (h *Host) listDirectory(ctx context.Context, root *openRoot, request *wisperpb.ListDirectory, out *replies) error {
	// followLeaf is true: listing enters the directory, and entering one through a symlink
	// is the traversal this package does not do.
	target, failed := resolve(root, request.GetPath(), true)
	if failed != nil {
		return failed
	}

	after, failed := decodeCursor(request.GetCursor())
	if failed != nil {
		return failed
	}
	size := h.pageSize(request.GetPageSize())

	directory, err := root.root.Open(systemName(target))
	if err != nil {
		return classify(ctx, err, target, "open the directory")
	}
	defer directory.Close()

	entries, err := directory.ReadDir(-1)
	if err != nil {
		return classify(ctx, err, target, "read the directory")
	}
	if failed := interrupted(ctx, target, "reading the directory"); failed != nil {
		return failed
	}

	visible := make([]fs.DirEntry, 0, len(entries))
	for _, entry := range entries {
		if !request.GetIncludeHidden() && strings.HasPrefix(entry.Name(), ".") {
			continue
		}
		visible = append(visible, entry)
	}
	sort.Slice(visible, func(i, j int) bool { return sortsBefore(visible[i], visible[j]) })

	listing := &wisperpb.DirectoryListing{
		Path:         target,
		TotalEntries: int32(len(visible)),
		Entries:      make([]*wisperpb.FileInfo, 0, size),
	}
	lastKey := ""
	truncated := false
	for _, entry := range visible {
		key := cursorKey(entry)
		if after != "" && key <= after {
			continue
		}
		if len(listing.Entries) == size {
			truncated = true
			break
		}

		info, err := entry.Info()
		if err != nil {
			// Removed between the readdir and the lstat. A customer deleting a file while
			// the listing is being built is not an error worth failing the page for.
			continue
		}
		child := childPath(target, entry.Name())
		listing.Entries = append(listing.Entries, describe(root, child, entry.Name(), info))
		lastKey = key
	}
	if truncated && lastKey != "" {
		listing.NextCursor = encodeCursor(lastKey)
	}

	return out.listing(listing)
}

// pageSize is what the panel asked for, bounded at both ends.
func (h *Host) pageSize(requested int32) int {
	switch {
	case requested <= 0:
		return h.limits.ListPageSize
	case int(requested) > h.limits.MaxListPageSize:
		return h.limits.MaxListPageSize
	default:
		return int(requested)
	}
}

// sortsBefore is the one ordering this package uses: directories first, then by name.
//
// Directories first because that is how a person reads a folder, and byte order rather
// than a locale-aware collation because the cursor is compared with the same operator on
// the next request and a collation that depends on the node's environment would page
// differently on two nodes.
func sortsBefore(left, right fs.DirEntry) bool {
	if left.IsDir() != right.IsDir() {
		return left.IsDir()
	}
	return left.Name() < right.Name()
}

// cursorKey is the sortable identity of an entry: the same two facts sortsBefore uses,
// in an order that compares correctly as a plain string.
func cursorKey(entry fs.DirEntry) string {
	if entry.IsDir() {
		return "0/" + entry.Name()
	}
	return "1/" + entry.Name()
}

// encodeCursor makes the key opaque. Not for secrecy - it is a filename the customer
// already has - but so that nothing in the panel or the browser starts parsing it and
// turns the paging scheme into a contract of its own.
func encodeCursor(key string) string {
	return base64.RawURLEncoding.EncodeToString([]byte(key))
}

func decodeCursor(cursor string) (string, *failure) {
	if cursor == "" {
		return "", nil
	}
	decoded, err := base64.RawURLEncoding.DecodeString(cursor)
	if err != nil {
		return "", refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_IO_ERROR, "",
			"the page cursor is not one this node issued")
	}
	key := string(decoded)
	if !strings.HasPrefix(key, "0/") && !strings.HasPrefix(key, "1/") {
		return "", refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_IO_ERROR, "",
			"the page cursor is not one this node issued")
	}
	return key, nil
}
