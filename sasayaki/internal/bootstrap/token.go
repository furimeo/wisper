package bootstrap

import (
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"strings"
)

// stdinToken is the --token-file value that means "read it from stdin". A single dash is
// the convention every unix tool uses for this and it is the only way to pass a token
// that never touches a filesystem at all.
const stdinToken = "-"

// The longest a bootstrap token may be. Not a security boundary - it is there so that
// pointing --token-file at a disk image produces an error rather than a gigabyte in
// memory and a confusing failure at the panel.
const maximumTokenBytes = 4096

// errTokenInArgv is what any attempt to pass a token as an argument gets.
var errTokenInArgv = errors.New(
	"a bootstrap token is never accepted on the command line: argv is readable by every " +
		"user on this machine through ps. Use --token-file <path>, or --token-file - to " +
		"read it from stdin")

// bootstrapToken is a single-use token and where it came from.
type bootstrapToken struct {
	// Value is the token itself.
	Value string

	// Origin is what to say about it in a log line: a path, or "stdin".
	Origin string

	// WasExposed records that the file was readable by somebody other than its owner.
	// Not fatal - the file has been destroyed by the time this is read, and the token
	// dies at first use anyway - but worth saying, because it is usually a sign that
	// whatever produced the file will produce the next one the same way.
	WasExposed bool
}

// readBootstrapToken reads the token and destroys the file it came from.
//
// Both halves matter. The token has a fifteen-minute life and enrols exactly one machine,
// so a copy left behind on disk is not a lasting danger - but it is a copy of a
// credential sitting in whatever directory an administrator happened to scp it into, and
// leaving it there is the habit rather than the incident. Reading from stdin leaves
// nothing to destroy, which is why the installer prefers it.
//
// The file is overwritten before it is unlinked. On a journalling or copy-on-write
// filesystem that does not guarantee the old bytes are gone, and this does not pretend
// otherwise: it guarantees the token is not sitting in a file anybody can open.
func readBootstrapToken(path string, stdin io.Reader) (bootstrapToken, error) {
	if path == "" {
		return bootstrapToken{}, errors.New("no --token-file. " + errTokenInArgv.Error())
	}

	if path == stdinToken {
		raw, err := io.ReadAll(io.LimitReader(stdin, maximumTokenBytes+1))
		if err != nil {
			return bootstrapToken{}, fmt.Errorf("read the bootstrap token from stdin: %w", err)
		}
		value, err := cleanToken(raw, "stdin")
		if err != nil {
			return bootstrapToken{}, err
		}
		return bootstrapToken{Value: value, Origin: "stdin"}, nil
	}

	file, err := os.OpenFile(path, os.O_RDWR, 0)
	if err != nil {
		return bootstrapToken{}, fmt.Errorf("open the bootstrap token file: %w", err)
	}

	exposed := false
	if info, statErr := file.Stat(); statErr == nil {
		exposed = info.Mode().Perm()&0o077 != 0
	}

	raw, err := io.ReadAll(io.LimitReader(file, maximumTokenBytes+1))
	if err != nil {
		file.Close()
		return bootstrapToken{}, fmt.Errorf("read %s: %w", path, err)
	}

	// Destroy it whatever the contents turned out to be. A file that could not be parsed
	// is still a file with a credential in it.
	destroyErr := destroyFile(file, path, len(raw))

	value, err := cleanToken(raw, path)
	if err != nil {
		return bootstrapToken{}, err
	}
	if destroyErr != nil {
		return bootstrapToken{}, destroyErr
	}
	return bootstrapToken{Value: value, Origin: path, WasExposed: exposed}, nil
}

// destroyFile overwrites the token and unlinks the file.
//
// The handle is closed before the unlink rather than after, and not only for tidiness: a
// developer's machine is Windows, where a file that is still open cannot be deleted at
// all, and a test that cannot prove the token was destroyed is a test of nothing. Closing
// first is correct on both.
func destroyFile(file *os.File, path string, length int) error {
	if length > 0 {
		if _, err := file.Seek(0, io.SeekStart); err == nil {
			zeroes := make([]byte, length)
			if _, err := file.Write(zeroes); err == nil {
				// Best effort: a failed sync means the zeroes may still be in the page
				// cache when the unlink happens, which changes nothing about the outcome.
				_ = file.Sync()
			}
		}
	}

	if err := file.Close(); err != nil {
		return fmt.Errorf("close the bootstrap token file %s: %w", path, err)
	}

	if err := os.Remove(path); err != nil {
		return fmt.Errorf("the bootstrap token was read but %s could not be deleted, so a "+
			"credential is still on disk: %w", path, err)
	}
	return nil
}

// cleanToken turns the bytes of a file into a token, or explains why they are not one.
func cleanToken(raw []byte, origin string) (string, error) {
	if len(raw) > maximumTokenBytes {
		return "", fmt.Errorf("%s holds more than %d bytes, which is not a bootstrap token",
			origin, maximumTokenBytes)
	}

	value := strings.TrimSpace(string(raw))
	if value == "" {
		return "", fmt.Errorf("%s is empty. Issue a bootstrap token from the node's page in "+
			"the panel and write it there", origin)
	}
	if strings.ContainsAny(value, "\r\n") {
		return "", fmt.Errorf("%s holds more than one line. A bootstrap token is a single "+
			"line; a file with several is usually a copy-and-paste of the whole install "+
			"command", origin)
	}
	if strings.ContainsAny(value, " \t") {
		return "", fmt.Errorf("%s holds whitespace inside the token, so it is not one token. "+
			"Check what was pasted", origin)
	}
	return value, nil
}

// refuseTokenFlag is registered on every command that takes a token, so `--token=abc`
// produces the explanation rather than "flag provided but not defined". Somebody typing
// it has a reason to expect it to work, and telling them why it does not is the point.
type refuseTokenFlag struct{}

func (refuseTokenFlag) String() string { return "" }

func (refuseTokenFlag) Set(string) error { return errTokenInArgv }

// tokenFlags gives a command --token-file and the refusal of --token together, so a new
// command cannot pick up one without the other.
func tokenFlags(flags *flag.FlagSet, tokenFile *string) {
	flags.StringVar(tokenFile, "token-file", "",
		"file holding the single-use bootstrap token, or - for stdin. Deleted after it is read")
	flags.Var(refuseTokenFlag{}, "token",
		"refused: argv is readable by every user on this machine through ps")
}
