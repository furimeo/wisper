package files

import (
	"archive/zip"
	"io"
	"io/fs"
)

// Zip, which is what a customer on Windows or a phone can open without installing
// anything. It is the default the file manager offers for that reason alone.

type zipArchive struct {
	writer *zip.Writer
}

func newZipArchive(target io.Writer) archiveWriter {
	return &zipArchive{writer: zip.NewWriter(target)}
}

func (z *zipArchive) addDirectory(name string, info fs.FileInfo) error {
	header, err := zip.FileInfoHeader(info)
	if err != nil {
		return err
	}
	// The trailing slash is how a zip says "directory". Without it an empty folder
	// disappears from the archive and the extraction produces a different tree from the
	// one that was packed.
	header.Name = name + "/"
	_, err = z.writer.CreateHeader(header)
	return err
}

func (z *zipArchive) addFile(name string, info fs.FileInfo, source io.Reader) error {
	header, err := zip.FileInfoHeader(info)
	if err != nil {
		return err
	}
	header.Name = name
	// Deflate rather than store. The alternative saves CPU on a node that is shared by
	// every customer on it, and costs the customer the download it is waiting for.
	header.Method = zip.Deflate
	entry, err := z.writer.CreateHeader(header)
	if err != nil {
		return err
	}
	_, err = io.Copy(entry, source)
	return err
}

// addSymlink writes the link itself, the way Info-ZIP does: the mode carries the symlink
// bit and the body is the target.
//
// Kept rather than dereferenced. Dereferencing would write the target's bytes twice - once
// here and once where the target itself is packed - and would follow a link out of the
// root, which is the traversal this package refuses everywhere else.
func (z *zipArchive) addSymlink(name string, info fs.FileInfo, target string) error {
	header, err := zip.FileInfoHeader(info)
	if err != nil {
		return err
	}
	header.Name = name
	header.SetMode(info.Mode() | fs.ModeSymlink)
	entry, err := z.writer.CreateHeader(header)
	if err != nil {
		return err
	}
	_, err = io.WriteString(entry, target)
	return err
}

func (z *zipArchive) Close() error {
	return z.writer.Close()
}
