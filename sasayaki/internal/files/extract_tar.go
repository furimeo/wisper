package files

import (
	"archive/tar"
	"compress/gzip"
	"errors"
	"io"
	"io/fs"
	"os"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Reading a tar.gz, which is a stream: one pass, forwards, and the reader for an entry is
// only valid until the next header. That is why open() below hands back the tar reader
// itself rather than something that can be kept.

type tarEntries struct {
	compressor *gzip.Reader
	reader     *tar.Reader
}

func openTarEntries(file *os.File, archivePath string) (entrySource, *failure) {
	if _, err := file.Seek(0, io.SeekStart); err != nil {
		return nil, classify(nil, err, archivePath, "rewind the archive")
	}
	compressor, err := gzip.NewReader(file)
	if err != nil {
		return nil, refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_UNSUPPORTED_ARCHIVE, archivePath,
			"this file starts like a gzip and is not a readable one: %v", err)
	}
	return &tarEntries{compressor: compressor, reader: tar.NewReader(compressor)}, nil
}

func (t *tarEntries) each(visit func(archiveEntry) error) error {
	for {
		header, err := t.reader.Next()
		if errors.Is(err, io.EOF) {
			return nil
		}
		if err != nil {
			return err
		}

		entry := archiveEntry{
			name:         header.Name,
			mode:         fs.FileMode(header.Mode).Perm(),
			directory:    header.Typeflag == tar.TypeDir,
			symlink:      header.Typeflag == tar.TypeSymlink,
			linkTarget:   header.Linkname,
			declaredSize: header.Size,
			// The tar reader is positioned at this entry's body and stops at its end, so
			// it is the entry's content. NopCloser because closing it would end the whole
			// archive, not this entry.
			open: func() (io.ReadCloser, error) { return io.NopCloser(t.reader), nil },
		}
		// A hard link points at another entry of the same archive. Re-creating one would
		// mean two paths in a customer's volume sharing an inode, which nothing in the
		// file manager can then account for separately, so it is skipped like the rest.
		//
		// Anything else - a device node, a fifo, a global header - is marked irregular and
		// skipped rather than refused: one of them must not make an otherwise good archive
		// unextractable. tar.Reader normalises the historical TypeRegA to TypeReg before
		// this sees it, so there is one value to compare against.
		if !entry.directory && !entry.symlink && header.Typeflag != tar.TypeReg {
			entry.mode |= fs.ModeIrregular
		}

		if err := visit(entry); err != nil {
			return err
		}
	}
}

func (t *tarEntries) Close() error {
	return t.compressor.Close()
}
