package files

import (
	"archive/zip"
	"io"
	"io/fs"
	"os"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Reading a zip, which needs the whole file rather than a stream: the index is at the end,
// so there is no way to know what is in one without seeking. That is why the extraction
// opens the archive through the root as a file and hands it here as a ReaderAt.

type zipEntries struct {
	reader *zip.Reader
}

func openZipEntries(file *os.File, size int64, archivePath string) (entrySource, *failure) {
	reader, err := zip.NewReader(file, size)
	if err != nil {
		return nil, refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_UNSUPPORTED_ARCHIVE, archivePath,
			"this file starts like a zip and is not a readable one: %v", err)
	}
	return &zipEntries{reader: reader}, nil
}

func (z *zipEntries) each(visit func(archiveEntry) error) error {
	for _, file := range z.reader.File {
		mode := file.Mode()
		entry := archiveEntry{
			name:      file.Name,
			mode:      mode,
			directory: file.FileInfo().IsDir(),
			symlink:   mode&fs.ModeSymlink != 0,
			open:      func() (io.ReadCloser, error) { return file.Open() },
			// Declared, and therefore checked rather than believed: the copy in
			// extract.go stops at the budget regardless of what this says.
			declaredSize: int64(file.UncompressedSize64),
		}
		if entry.symlink {
			target, err := readZipLink(file)
			if err != nil {
				return err
			}
			entry.linkTarget = target
		}
		if err := visit(entry); err != nil {
			return err
		}
	}
	return nil
}

// readZipLink reads a symlink's target, which a zip stores as the entry's content.
//
// Bounded, because the target is read into memory and a "link" whose body is a gigabyte is
// not a link. A path longer than this cannot exist on any filesystem the node supports.
func readZipLink(file *zip.File) (string, error) {
	content, err := file.Open()
	if err != nil {
		return "", err
	}
	defer content.Close()

	target, err := io.ReadAll(io.LimitReader(content, maxPathLength))
	if err != nil {
		return "", err
	}
	return string(target), nil
}

// Close is nothing: the zip reader does not own the file, the extraction does.
func (z *zipEntries) Close() error { return nil }
