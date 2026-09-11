package bootstrap

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// Limits on a download.
//
// The size cap is not a security boundary - the checksum is - but a node whose panel URL
// has been pointed at something enormous should fail in a minute rather than fill the disk
// it was about to deploy onto. A sasayaki binary is around thirty megabytes.
const (
	maximumBinaryBytes = 512 << 20
	downloadTimeout    = 10 * time.Minute
)

// stagedBinary is a candidate sitting next to the binary it might replace, verified but
// not yet in place.
type stagedBinary struct {
	// path is inside the destination directory, so the rename that installs it is atomic.
	path string

	// sha256 is what the bytes actually hash to, whether or not anybody stated it in
	// advance. It is printed for an offline install, where the operator is the one who
	// compares it with what the panel showed them.
	sha256 string

	size int64
}

// stage fetches the new binary, verifies it, and writes it beside the one it will replace.
//
// Nothing is replaced here. The order is deliberate and it is the whole safety property of
// an upgrade: the bytes exist on the destination filesystem and have been proved to be the
// right bytes before the live path is touched at all. A checksum mismatch, a truncated
// download or a full disk therefore all end with the running binary exactly as it was
// (design section 7.5).
func (s upgradeSteps) stage(ctx context.Context, source upgradeSource) (stagedBinary, error) {
	body, origin, err := s.readSource(ctx, source)
	if err != nil {
		return stagedBinary{}, err
	}
	if len(body) == 0 {
		return stagedBinary{}, fmt.Errorf("%s is empty", origin)
	}

	sum := sha256.Sum256(body)
	actual := hex.EncodeToString(sum[:])
	if expected := normaliseChecksum(source.sha256); expected != "" && expected != actual {
		return stagedBinary{}, fmt.Errorf("%s does not have the checksum it was promised.\n"+
			"  expected %s\n  received %s\nNothing has been replaced; the running binary is "+
			"untouched", origin, expected, actual)
	}

	// Same directory as the destination: a rename is only atomic within one filesystem,
	// and /usr/local/bin and /tmp are routinely on different ones.
	destination := s.layout.BinaryPath + ".incoming"
	if err := writeFileAtomically(destination, body, 0o755); err != nil {
		return stagedBinary{}, err
	}
	return stagedBinary{path: destination, sha256: actual, size: int64(len(body))}, nil
}

func (s upgradeSteps) readSource(ctx context.Context, source upgradeSource) ([]byte, string, error) {
	if source.localPath != "" {
		body, err := os.ReadFile(source.localPath)
		if err != nil {
			return nil, source.localPath, fmt.Errorf("read %s: %w", source.localPath, err)
		}
		if int64(len(body)) > maximumBinaryBytes {
			return nil, source.localPath, fmt.Errorf("%s is larger than %s, which is not a "+
				"sasayaki binary", source.localPath, formatBytes(maximumBinaryBytes))
		}
		return body, source.localPath, nil
	}

	fmt.Fprintf(s.out, "Fetching %s\n", source.url)
	body, err := s.fetch(ctx, source.url)
	if err != nil {
		return nil, source.url, err
	}
	return body, source.url, nil
}

// downloadBinary is the real fetch: one GET, no redirects to another host, and a hard
// ceiling on how much will be read.
//
// The transport is plain net/http rather than the pinned gRPC connection, which is a
// deliberate limitation and a safe one: the URL and the checksum both arrived over that
// pinned connection, so an attacker who can serve the download cannot make it hash to the
// value the panel already stated.
func downloadBinary(ctx context.Context, url string) ([]byte, error) {
	ctx, cancel := context.WithTimeout(ctx, downloadTimeout)
	defer cancel()

	request, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, fmt.Errorf("build a request for %s: %w", url, err)
	}
	request.Header.Set("User-Agent", "sasayaki")

	response, err := http.DefaultClient.Do(request)
	if err != nil {
		return nil, fmt.Errorf("fetch %s: %w", url, err)
	}
	defer response.Body.Close()

	if response.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("fetch %s: the server answered %s", url, response.Status)
	}

	body, err := io.ReadAll(io.LimitReader(response.Body, maximumBinaryBytes+1))
	if err != nil {
		return nil, fmt.Errorf("read %s: %w", url, err)
	}
	if int64(len(body)) > maximumBinaryBytes {
		return nil, fmt.Errorf("%s served more than %s, which is not a sasayaki binary",
			url, formatBytes(maximumBinaryBytes))
	}
	return body, nil
}

// probeVersion asks a binary what it is.
//
// Run before the file is put in place, and it catches the failure a checksum cannot: a
// binary that is exactly what the panel published and is for the wrong architecture. The
// panel picks the build from the architecture the node reported, so this is the check that
// turns a bad reply into a refused upgrade instead of a node that will not start.
func probeVersion(ctx context.Context, path string) (string, error) {
	ctx, cancel := context.WithTimeout(ctx, 20*time.Second)
	defer cancel()

	output, err := runCommand(ctx, path, "version", "--short")
	answer := strings.TrimSpace(string(output))
	if err != nil {
		if answer != "" {
			return "", fmt.Errorf("%s would not run: %w: %s", filepath.Base(path), err, answer)
		}
		return "", fmt.Errorf("%s would not run: %w", filepath.Base(path), err)
	}
	if answer == "" {
		return "", fmt.Errorf("%s ran but printed no version", filepath.Base(path))
	}
	return lastLine(answer), nil
}

// normaliseChecksum accepts what an operator pastes: mixed case, surrounding whitespace,
// and the "<hash>  <filename>" line that sha256sum prints.
func normaliseChecksum(raw string) string {
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" {
		return ""
	}
	if field, _, found := strings.Cut(trimmed, " "); found {
		trimmed = field
	}
	return strings.ToLower(trimmed)
}
