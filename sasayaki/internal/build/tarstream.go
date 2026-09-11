package build

import (
	"archive/tar"
	"context"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
)

// The build context, in the form the Engine API takes it: a tar stream on the request
// body.
//
// Streamed through a pipe rather than assembled in memory or in a temporary file. A build
// context is a whole repository, the engine reads it as fast as it can unpack it, and
// holding a second copy of a customer's source on a node that is already holding the first
// is a cost with nothing to show for it.
//
// Everything the walk refuses is refused for the same reason the extractor refuses it: a
// tar entry is a filename the daemon at the other end will create, and a link out of the
// context is a way to reach the rest of the node.

// tarDirectory streams root as a tar archive, honouring .dockerignore.
//
// The returned reader is closed by the caller. An error during the walk is delivered
// through the pipe, so ImageBuild fails with it rather than sending a truncated context
// the engine would try to build.
func tarDirectory(ctx context.Context, root string, ignore *ignoreList) io.ReadCloser {
	reader, writer := io.Pipe()

	go func() {
		archive := tar.NewWriter(writer)
		if err := walkIntoTar(ctx, archive, root, ignore); err != nil {
			// Deliberately without closing the archive. Close writes tar's end-of-archive
			// marker, and a context that ends with a valid marker is one the engine reads
			// as complete and builds - so a walk that stopped at a link pointing out of the
			// tree would produce an image from half a repository instead of a failure. The
			// reader gets the error where the marker would have been.
			_ = writer.CloseWithError(err)
			return
		}
		_ = writer.CloseWithError(archive.Close())
	}()

	return reader
}

func walkIntoTar(ctx context.Context, archive *tar.Writer, root string, ignore *ignoreList) error {
	return filepath.WalkDir(root, func(source string, entry os.DirEntry, err error) error {
		if err != nil {
			return fmt.Errorf("build: read %s for the build context: %w", source, err)
		}
		if err := ctx.Err(); err != nil {
			return err
		}

		name, err := filepath.Rel(root, source)
		if err != nil {
			return fmt.Errorf("build: place %s in the build context: %w", source, err)
		}
		if name == "." {
			return nil
		}
		name = filepath.ToSlash(name)

		if ignore.excludes(name, entry.IsDir()) {
			if entry.IsDir() {
				return filepath.SkipDir
			}
			return nil
		}

		info, err := entry.Info()
		if err != nil {
			return fmt.Errorf("build: look at %s: %w", source, err)
		}

		link := ""
		if entry.Type()&os.ModeSymlink != 0 {
			link, err = os.Readlink(source)
			if err != nil {
				return fmt.Errorf("build: read the link %s: %w", source, err)
			}
			if filepath.IsAbs(link) ||
				!insideTree(root, filepath.Join(filepath.Dir(source), filepath.FromSlash(link))) {
				return fmt.Errorf("build: the build context holds the link %s, which points at "+
					"%q, outside the context", name, link)
			}
		} else if !entry.IsDir() && !info.Mode().IsRegular() {
			// A socket or a device node. The engine has no use for one and tar has no
			// honest way to carry it.
			return nil
		}

		header, err := tar.FileInfoHeader(info, link)
		if err != nil {
			return fmt.Errorf("build: describe %s for the build context: %w", source, err)
		}
		header.Name = name
		if entry.IsDir() {
			header.Name = name + "/"
		}
		// Ownership is dropped deliberately. The uid a file happens to have on this node
		// means nothing inside the image, and carrying it makes two builds of the same
		// commit on two nodes produce different layers.
		header.Uid, header.Gid = 0, 0
		header.Uname, header.Gname = "", ""

		if err := archive.WriteHeader(header); err != nil {
			return fmt.Errorf("build: write %s into the build context: %w", name, err)
		}
		if !info.Mode().IsRegular() {
			return nil
		}

		file, err := os.Open(source)
		if err != nil {
			return fmt.Errorf("build: read %s: %w", source, err)
		}
		defer file.Close()
		if _, err := io.CopyN(archive, file, info.Size()); err != nil {
			return fmt.Errorf("build: write %s into the build context: %w", name, err)
		}
		return nil
	})
}

// dockerfileName is the path inside the context the engine is told to build from.
//
// It has to be relative to the context and expressed with forward slashes whatever the
// daemon that produced it runs on, and it may not climb out: the engine resolves it inside
// the tar it was sent, so a name with ".." in it is either a mistake or an attempt to have
// the engine read something it was not sent.
func dockerfileName(contextRoot, dockerfile string) (string, error) {
	wanted := strings.TrimSpace(dockerfile)
	if wanted == "" {
		return "Dockerfile", nil
	}
	resolved, err := resolveInside(contextRoot, wanted, "dockerfile path")
	if err != nil {
		return "", err
	}
	relative, err := filepath.Rel(contextRoot, resolved)
	if err != nil {
		return "", fmt.Errorf("build: the dockerfile %q is not inside the build context", dockerfile)
	}
	return filepath.ToSlash(relative), nil
}
