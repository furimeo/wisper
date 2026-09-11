package files

import (
	"archive/tar"
	"compress/gzip"
	"errors"
	"io"
	"io/fs"
)

// tar.gz, which is what a customer moving a Linux application somewhere else asks for.
//
// It keeps the permission bits and the ownership layout a zip only approximates, so a site
// that was archived here and unpacked on another host comes back with its executables
// still executable.

type tarArchive struct {
	compressor *gzip.Writer
	writer     *tar.Writer
}

func newTarArchive(target io.Writer) archiveWriter {
	compressor := gzip.NewWriter(target)
	return &tarArchive{compressor: compressor, writer: tar.NewWriter(compressor)}
}

func (t *tarArchive) addDirectory(name string, info fs.FileInfo) error {
	header, err := tar.FileInfoHeader(info, "")
	if err != nil {
		return err
	}
	header.Name = name + "/"
	return t.writer.WriteHeader(header)
}

func (t *tarArchive) addFile(name string, info fs.FileInfo, source io.Reader) error {
	header, err := tar.FileInfoHeader(info, "")
	if err != nil {
		return err
	}
	header.Name = name
	if err := t.writer.WriteHeader(header); err != nil {
		return err
	}
	// Exactly header.Size bytes, because that is what the header promised and a tar whose
	// body is longer than its header is corrupt. A file that grew while it was being
	// archived is truncated to the length it had when it was measured, which is the same
	// thing every other tar does.
	written, err := io.Copy(t.writer, io.LimitReader(source, header.Size))
	if err != nil {
		return err
	}
	if written < header.Size {
		// Shrunk instead. Pad, or the next header lands at the wrong offset and the whole
		// archive after this entry is unreadable.
		if _, err := io.Copy(t.writer, zeroes(header.Size-written)); err != nil {
			return err
		}
	}
	return nil
}

func (t *tarArchive) addSymlink(name string, info fs.FileInfo, target string) error {
	header, err := tar.FileInfoHeader(info, target)
	if err != nil {
		return err
	}
	header.Name = name
	return t.writer.WriteHeader(header)
}

// Close finishes both layers. The tar trailer has to be written before the gzip footer, so
// this is an order and not a pair of defers.
func (t *tarArchive) Close() error {
	return errors.Join(t.writer.Close(), t.compressor.Close())
}

// zeroes is a reader of n zero bytes, for padding a file that shrank underneath the walk.
func zeroes(n int64) io.Reader {
	return io.LimitReader(zeroReader{}, n)
}

type zeroReader struct{}

func (zeroReader) Read(buffer []byte) (int, error) {
	for index := range buffer {
		buffer[index] = 0
	}
	return len(buffer), nil
}
