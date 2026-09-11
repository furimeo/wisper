package build

import (
	"archive/zip"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"os"
	"path"
	"path/filepath"
	"strings"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// The other way source reaches a node: a zip the customer uploaded through the panel, for
// the project that is not in git (design section 5.5).
//
// Unpacked here rather than in a container, unlike the clone, and the difference is worth
// justifying. A clone runs somebody else's program - git, its hooks, its submodule
// resolution - over the network. Unpacking a zip runs this file's own loop over bytes that
// are already on this node's disk, using the standard library's reader. There is no
// program to confine, so putting it in a container would buy nothing and cost a pull.
//
// What it does need is the discipline a container would otherwise provide, and all of it
// is in extract below: an entry named ../../etc/shadow is refused, a symlink pointing out
// of the tree is refused, a hard limit on the unpacked size stops a zip bomb, and nothing
// is followed that the extractor did not create itself.

const (
	// unpackedLimit bounds what one archive may become on disk. A zip compresses text at
	// better than ten to one, so a small upload can be an enormous tree; the limit is what
	// stops a customer's upload from filling the disk the other customers are on.
	unpackedLimit = 4 << 30
	// entryLimit bounds how many files an archive may hold. A hundred thousand is more
	// than any site and few enough that the extraction loop cannot be turned into one.
	entryLimit = 100_000
	// fileMode is what an extracted file gets. The zip's own mode bits are deliberately
	// ignored: a setuid bit in an archive is not something anybody needs to honour, and a
	// mode of zero in a badly written archiver produces files nothing can read.
	fileMode os.FileMode = 0o644
)

// fetchArchive unpacks a completed upload into the workspace's checkout.
//
// The checkout is always emptied first, even when the build asked to reuse the cache. A zip
// carries no notion of which files it tracks, so extracting one over a previous build's
// tree would publish whatever that build left behind - and a deployment that ships a file
// the customer deleted is worse than one that reinstalls its dependencies.
func (b *Builder) fetchArchive(ctx context.Context, space workspace,
	archive *wisperpb.ArchiveSource, log *buildLog) (source, error) {

	session := strings.TrimSpace(archive.GetUploadSessionId())
	if session == "" {
		return source{}, errors.New("build: the archive source names no upload session")
	}

	location, err := b.uploads.ArchivePath(ctx, session)
	if err != nil {
		return source{}, fmt.Errorf("build: find the uploaded archive for session %s: %w", session, err)
	}

	log.say("checking the uploaded archive")
	if err := verifyDigest(location, archive.GetSha256()); err != nil {
		return source{}, err
	}
	if err := emptyCheckout(space.Checkout); err != nil {
		return source{}, err
	}

	log.say("unpacking the archive")
	files, err := extract(ctx, location, space.Checkout)
	if err != nil {
		return source{}, err
	}
	log.say("unpacked %d files", files)

	// No commit and no message: an archive is not a revision, and inventing one would put
	// a plausible-looking hash next to a deployment that has none.
	return source{}, nil
}

// verifyDigest re-checks the archive against the hash the panel recorded.
//
// The upload path already checked it once, on the way in. It is checked again here because
// the two events can be days apart, the file has been sitting on a disk in between, and the
// cost of hashing a zip is nothing next to the cost of building the wrong bytes and not
// being able to explain why.
func verifyDigest(location, expected string) error {
	wanted := strings.ToLower(strings.TrimSpace(expected))
	if wanted == "" {
		return errors.New("build: the archive source carries no sha256, so what was uploaded " +
			"cannot be told apart from what was meant to be")
	}

	file, err := os.Open(location)
	if err != nil {
		return fmt.Errorf("build: open the uploaded archive: %w", err)
	}
	defer file.Close()

	digest := sha256.New()
	if _, err := io.Copy(digest, file); err != nil {
		return fmt.Errorf("build: read the uploaded archive: %w", err)
	}
	got := hex.EncodeToString(digest.Sum(nil))
	if got != wanted {
		return fmt.Errorf("build: the uploaded archive hashes to %s and the deployment expects "+
			"%s, so it is not the file the customer uploaded", got, wanted)
	}
	return nil
}

// extract unpacks a zip into a directory and returns how many files it wrote.
func extract(ctx context.Context, location, destination string) (int, error) {
	reader, err := zip.OpenReader(location)
	if err != nil {
		return 0, fmt.Errorf("build: read the uploaded archive: %w", err)
	}
	defer reader.Close()

	if len(reader.File) > entryLimit {
		return 0, fmt.Errorf("build: the archive holds %d entries, which is past the %d this "+
			"node will unpack", len(reader.File), entryLimit)
	}

	var (
		written int
		total   int64
	)
	for _, entry := range reader.File {
		if err := ctx.Err(); err != nil {
			return written, err
		}

		target, err := entryPath(destination, entry.Name)
		if err != nil {
			return written, err
		}
		info := entry.FileInfo()

		switch {
		case info.IsDir():
			if err := os.MkdirAll(target, directoryMode); err != nil {
				return written, fmt.Errorf("build: create %s from the archive: %w", entry.Name, err)
			}

		case info.Mode()&os.ModeSymlink != 0:
			if err := extractSymlink(entry, destination, target); err != nil {
				return written, err
			}
			written++

		case info.Mode().IsRegular():
			size, err := extractFile(entry, target, unpackedLimit-total)
			if err != nil {
				return written, err
			}
			total += size
			written++

		default:
			// A device node, a socket, a fifo. Nothing a site is built from, and each of
			// them is a way to make a build container interact with the host.
			return written, fmt.Errorf("build: the archive holds %s, which is neither a file, a "+
				"directory nor a symbolic link", entry.Name)
		}
	}
	return written, nil
}

// entryPath resolves one archive entry against the destination and refuses anything that
// would land outside it.
//
// Zip stores names with forward slashes, and an archiver is free to write "..", an absolute
// path or a Windows separator into one. Two different answers, deliberately:
//
//   - A leading "/" is dropped. Several archivers emit absolute names for entries that were
//     never meant to be absolute, and every unpacker in the world treats them as relative
//     to where it is unpacking.
//   - A ".." that climbs above the root is refused, not normalised away. Normalising is the
//     safe-looking option and it is what most unpackers do, but it silently renames the
//     customer's file and hides the fact that something produced an archive that tried to
//     write outside itself. This package refuses rather than sanitises everywhere else, and
//     this is the place where that habit matters most.
func entryPath(destination, name string) (string, error) {
	raw := strings.TrimLeft(strings.ReplaceAll(name, `\`, "/"), "/")
	if strings.TrimSpace(raw) == "" {
		return "", fmt.Errorf("build: the archive holds an entry with no name")
	}

	cleaned := path.Clean(raw)
	if cleaned == "." || cleaned == ".." || strings.HasPrefix(cleaned, "../") {
		return "", fmt.Errorf("build: the archive holds %q, which unpacks outside the checkout", name)
	}

	target := filepath.Join(destination, filepath.FromSlash(cleaned))
	if !insideTree(destination, target) {
		return "", fmt.Errorf("build: the archive holds %q, which unpacks outside the checkout", name)
	}
	return target, nil
}

// extractFile writes one regular file, refusing to write more than the archive has room
// left for.
func extractFile(entry *zip.File, target string, remaining int64) (int64, error) {
	if remaining <= 0 {
		return 0, fmt.Errorf("build: the archive unpacks to more than %d bytes", int64(unpackedLimit))
	}
	if err := os.MkdirAll(filepath.Dir(target), directoryMode); err != nil {
		return 0, fmt.Errorf("build: create the directory for %s: %w", entry.Name, err)
	}

	source, err := entry.Open()
	if err != nil {
		return 0, fmt.Errorf("build: read %s from the archive: %w", entry.Name, err)
	}
	defer source.Close()

	// O_EXCL, so an entry that appears twice is a failure rather than a file quietly
	// overwritten by the second copy - and so that a symlink written by an earlier entry
	// cannot be the thing this write follows.
	file, err := os.OpenFile(target, os.O_WRONLY|os.O_CREATE|os.O_EXCL, fileMode)
	if err != nil {
		return 0, fmt.Errorf("build: write %s from the archive: %w", entry.Name, err)
	}
	defer file.Close()

	// One byte past the budget, so a declared size that lies about the compressed content
	// is caught by what actually arrives rather than by what the header claimed.
	size, err := io.Copy(file, io.LimitReader(source, remaining+1))
	if err != nil {
		return 0, fmt.Errorf("build: write %s from the archive: %w", entry.Name, err)
	}
	if size > remaining {
		return 0, fmt.Errorf("build: the archive unpacks to more than %d bytes", int64(unpackedLimit))
	}
	return size, nil
}

// extractSymlink writes one link, after checking where it points.
//
// A link is allowed to point inside the tree - a site generator's output often has them -
// and never outside it. Without this check an archive containing `config -> /etc` gives the
// build container, and then the published release, a door into the host through the bind
// mount.
func extractSymlink(entry *zip.File, destination, target string) error {
	source, err := entry.Open()
	if err != nil {
		return fmt.Errorf("build: read the link %s from the archive: %w", entry.Name, err)
	}
	defer source.Close()

	// A symlink's target is its content, and no legitimate one is longer than a path.
	raw, err := io.ReadAll(io.LimitReader(source, 4096))
	if err != nil {
		return fmt.Errorf("build: read the link %s from the archive: %w", entry.Name, err)
	}
	link := strings.TrimSpace(string(raw))
	if link == "" {
		return fmt.Errorf("build: the archive holds the link %s, which points nowhere", entry.Name)
	}
	if filepath.IsAbs(link) || strings.HasPrefix(link, "/") {
		return fmt.Errorf("build: the archive holds the link %s, which points at the absolute "+
			"path %q and would reach outside the checkout", entry.Name, link)
	}
	resolved := filepath.Join(filepath.Dir(target), filepath.FromSlash(link))
	if !insideTree(destination, resolved) {
		return fmt.Errorf("build: the archive holds the link %s, which points at %q, outside "+
			"the checkout", entry.Name, link)
	}

	if err := os.MkdirAll(filepath.Dir(target), directoryMode); err != nil {
		return fmt.Errorf("build: create the directory for %s: %w", entry.Name, err)
	}
	if err := os.Symlink(filepath.FromSlash(link), target); err != nil {
		return fmt.Errorf("build: write the link %s from the archive: %w", entry.Name, err)
	}
	return nil
}
