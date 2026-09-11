package backup

import (
	"compress/gzip"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"hash"
	"io"
	"os"
	"path/filepath"
)

// The staged archive: the file a snapshot produces and an upload reads.
//
// Staging to local disk before the network is touched is what keeps the quiesce window
// short. The pause ends when the bytes have been read out of the volume, not when they have
// crossed somebody's uplink, and an upload that fails can be tried again against a file that
// is already there rather than by pausing the customer's application a second time.
//
// It is also what makes the digest mean anything. The hash is taken over the compressed
// bytes as they are written, so it covers exactly what is uploaded, and the same number is
// re-derived from the same file on a resume rather than remembered from a previous process
// that may have been killed halfway through writing it.

// ErrChecksumMismatch is what a restore refuses with, and the one error in this package that
// a caller is expected to branch on. Wrapped with both digests, because "it did not match" is
// not enough to tell a truncated download from a corrupted archive.
var ErrChecksumMismatch = errors.New("the archive does not match its recorded checksum")

// staged is one archive on local disk, with the two numbers that describe it.
type staged struct {
	// Path is the file. Under <state>/backup-work/, named after the run that owns it.
	Path string
	// Size is its length in bytes, compressed - which is what the destination stores and
	// what the sidecar records.
	Size int64
	// SHA256 is the hex digest of those same bytes.
	SHA256 string
}

// archiveManifest is written beside a staged archive so a resumed run knows what it should
// hash to before it decides to trust it.
type archiveManifest struct {
	SHA256 string `json:"sha256"`
	Size   int64  `json:"size"`
}

const (
	// manifestSuffix names the manifest of a staged archive. Appended to the archive's own
	// name rather than to the run id, so a run that stages two files - a restore downloads the
	// archive and dumps the database it is about to overwrite - gets a manifest for each
	// rather than two writers of one.
	manifestSuffix = ".json"
	// partialSuffix marks a file that is still being written. Renamed into place once it is
	// complete, so a staged archive that exists is always a whole one.
	partialSuffix = ".partial"
	// fileMode is every file this package creates. Root only, like the directories.
	fileMode os.FileMode = 0o600
)

// stageArchive builds an archive on local disk, compressing and hashing as it goes.
//
// fill writes the uncompressed content - a tar stream, or a database's SQL. Everything else
// is here: gzip, the digest over the compressed bytes, the length, the rename that makes the
// result visible only once it is whole, and the manifest that lets a later process recognise
// it.
func stageArchive(stateDir, runID, extension string, fill func(io.Writer) error) (staged, error) {
	return stageBytes(stateDir, runID, extension, func(out io.Writer) error {
		compressed := gzip.NewWriter(out)
		if err := fill(compressed); err != nil {
			return err
		}
		if err := compressed.Close(); err != nil {
			return fmt.Errorf("backup: finish compressing the archive of %s: %w", runID, err)
		}
		return nil
	})
}

// stageDownload is the same machinery for bytes that arrive already compressed.
//
// A restore downloads an archive somebody else's object store has been holding, so there is
// nothing to compress and everything to check: the digest is taken over what came off the
// wire, which is exactly the number the sidecar has to agree with.
func stageDownload(stateDir, runID, extension string, fill func(io.Writer) error) (staged, error) {
	return stageBytes(stateDir, runID, extension, fill)
}

// stageBytes writes whatever fill produces to a file in the work directory, hashing and
// counting it, and only makes it visible under its real name once it is whole.
func stageBytes(stateDir, runID, extension string, fill func(io.Writer) error) (staged, error) {
	target, err := workPath(stateDir, runID, extension)
	if err != nil {
		return staged{}, err
	}
	if err := makeDirectory(filepath.Dir(target)); err != nil {
		return staged{}, err
	}

	temporary := target + partialSuffix
	file, err := os.OpenFile(temporary, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, fileMode)
	if err != nil {
		return staged{}, fmt.Errorf("backup: open the staging archive %s: %w", temporary, err)
	}

	digest := sha256.New()
	counter := &counting{}

	if err := fill(io.MultiWriter(file, digest, counter)); err != nil {
		closeAndRemove(file, temporary)
		return staged{}, err
	}
	// Fsync before the rename. A backup whose bytes are still in the page cache when the
	// machine loses power is a file that exists, has the right length and contains zeroes.
	if err := file.Sync(); err != nil {
		closeAndRemove(file, temporary)
		return staged{}, fmt.Errorf("backup: flush %s: %w", temporary, err)
	}
	if err := file.Close(); err != nil {
		os.Remove(temporary)
		return staged{}, fmt.Errorf("backup: close %s: %w", temporary, err)
	}
	if err := os.Rename(temporary, target); err != nil {
		os.Remove(temporary)
		return staged{}, fmt.Errorf("backup: publish the staging archive %s: %w", target, err)
	}

	result := staged{Path: target, Size: counter.written, SHA256: hex.EncodeToString(digest.Sum(nil))}
	if err := writeManifest(stateDir, runID, extension, result); err != nil {
		return staged{}, err
	}
	return result, nil
}

// loadStagedArchive finds an archive a previous, killed run left behind, and re-hashes it
// before saying yes.
//
// Re-hashing rather than trusting the manifest costs one pass over a local file and answers
// the question the manifest cannot: the previous process may have been killed between the
// rename and the fsync of the directory, and a file of the right length full of the wrong
// bytes is precisely the input that must not become a customer's only backup. It runs once,
// after a crash, and never on the normal path.
func loadStagedArchive(stateDir, runID, extension string) (staged, bool, error) {
	target, err := workPath(stateDir, runID, extension)
	if err != nil {
		return staged{}, false, err
	}
	manifest, found, err := readManifest(stateDir, runID, extension)
	if err != nil || !found {
		return staged{}, false, err
	}
	info, err := os.Stat(target)
	if errors.Is(err, os.ErrNotExist) {
		return staged{}, false, nil
	}
	if err != nil {
		return staged{}, false, fmt.Errorf("backup: look at the staged archive %s: %w", target, err)
	}
	if info.Size() != manifest.Size {
		return staged{}, false, nil
	}

	digest, err := digestOf(target)
	if err != nil {
		return staged{}, false, err
	}
	if digest != manifest.SHA256 {
		return staged{}, false, nil
	}
	return staged{Path: target, Size: manifest.Size, SHA256: manifest.SHA256}, true, nil
}

// discardStaged removes an archive and its manifest. Called once a run has finished, whatever
// the outcome: the archive is either at the destination or not worth keeping, and a work
// directory that accumulates forty-gigabyte files is a node that fills its own disk.
func discardStaged(stateDir, runID, extension string) error {
	target, err := workPath(stateDir, runID, extension)
	if err != nil {
		return err
	}
	manifest, err := workPath(stateDir, runID, extension+manifestSuffix)
	if err != nil {
		return err
	}
	var failure error
	for _, path := range []string{target, target + partialSuffix, manifest} {
		if err := os.Remove(path); err != nil && !errors.Is(err, os.ErrNotExist) {
			failure = fmt.Errorf("backup: remove %s: %w", path, err)
		}
	}
	return failure
}

// digestOf is the SHA-256 of a file on disk, hex encoded.
func digestOf(path string) (string, error) {
	file, err := os.Open(path)
	if err != nil {
		return "", fmt.Errorf("backup: open %s: %w", path, err)
	}
	defer file.Close()

	digest := sha256.New()
	if _, err := io.Copy(digest, file); err != nil {
		return "", fmt.Errorf("backup: read %s: %w", path, err)
	}
	return hex.EncodeToString(digest.Sum(nil)), nil
}

func writeManifest(stateDir, runID, extension string, result staged) error {
	path, err := workPath(stateDir, runID, extension+manifestSuffix)
	if err != nil {
		return err
	}
	encoded, err := json.Marshal(archiveManifest{SHA256: result.SHA256, Size: result.Size})
	if err != nil {
		return fmt.Errorf("backup: encode the manifest of %s: %w", runID, err)
	}
	if err := os.WriteFile(path, encoded, fileMode); err != nil {
		return fmt.Errorf("backup: write the manifest of %s: %w", runID, err)
	}
	return nil
}

func readManifest(stateDir, runID, extension string) (archiveManifest, bool, error) {
	path, err := workPath(stateDir, runID, extension+manifestSuffix)
	if err != nil {
		return archiveManifest{}, false, err
	}
	encoded, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return archiveManifest{}, false, nil
	}
	if err != nil {
		return archiveManifest{}, false, fmt.Errorf("backup: read the manifest of %s: %w", runID, err)
	}
	var manifest archiveManifest
	if err := json.Unmarshal(encoded, &manifest); err != nil {
		// A manifest that will not parse is treated as absent rather than as a failure: the
		// answer either way is to take the snapshot again, and refusing the whole backup
		// because a fifty-byte file got mangled would be a strange way to lose a night's data.
		return archiveManifest{}, false, nil
	}
	if manifest.SHA256 == "" || manifest.Size < 0 {
		return archiveManifest{}, false, nil
	}
	return manifest, true, nil
}

// counting is an io.Writer that only measures. Used rather than the file's own offset because
// the length that matters is the one the destination will store, and the two differ the
// moment anything is retried.
type counting struct{ written int64 }

func (c *counting) Write(p []byte) (int, error) {
	c.written += int64(len(p))
	return len(p), nil
}

// hashingReader wraps a reader and accumulates the digest of everything read through it, for
// a download that has to be checked before it is trusted.
type hashingReader struct {
	inner  io.Reader
	digest hash.Hash
	read   int64
}

func newHashingReader(inner io.Reader) *hashingReader {
	return &hashingReader{inner: inner, digest: sha256.New()}
}

func (h *hashingReader) Read(p []byte) (int, error) {
	n, err := h.inner.Read(p)
	h.read += int64(n)
	h.digest.Write(p[:n])
	return n, err
}

func (h *hashingReader) sum() string { return hex.EncodeToString(h.digest.Sum(nil)) }

func closeAndRemove(file *os.File, path string) {
	file.Close()
	os.Remove(path)
}
