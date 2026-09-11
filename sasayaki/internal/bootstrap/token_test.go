package bootstrap

import (
	"flag"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// Design section 7.1 and AGENTS.md section 5: a bootstrap token is accepted from a file or
// from stdin and never from argv, because /proc/<pid>/cmdline is readable by every user on
// the machine for as long as the process lives.

func TestATokenFileIsReadAndThenDestroyed(t *testing.T) {
	path := filepath.Join(t.TempDir(), "token.txt")
	writeFile(t, path, "wsp_9f2c4e6a8b0d\n")

	token, err := readBootstrapToken(path, strings.NewReader(""))
	if err != nil {
		t.Fatalf("read the token: %v", err)
	}

	if token.Value != "wsp_9f2c4e6a8b0d" {
		t.Fatalf("token is %q, want the trimmed contents of the file", token.Value)
	}
	mustNotExist(t, path)
}

// A file that could not be parsed still had a credential in it.
func TestATokenFileIsDestroyedEvenWhenItsContentsAreNonsense(t *testing.T) {
	path := filepath.Join(t.TempDir(), "token.txt")
	writeFile(t, path, "wsp_one\nwsp_two\n")

	if _, err := readBootstrapToken(path, strings.NewReader("")); err == nil {
		t.Fatal("two lines is not one token and should have been refused")
	}
	mustNotExist(t, path)
}

func TestAnEmptyTokenFileSaysWhereToGetOne(t *testing.T) {
	path := filepath.Join(t.TempDir(), "token.txt")
	writeFile(t, path, "   \n")

	_, err := readBootstrapToken(path, strings.NewReader(""))
	if err == nil {
		t.Fatal("an empty file is not a token")
	}
	mustContain(t, err.Error(), "node's page")
}

// The path that leaves nothing behind at all, which is why the installer prefers it.
func TestATokenOnStdinLeavesNothingOnDisk(t *testing.T) {
	token, err := readBootstrapToken("-", strings.NewReader("wsp_from_stdin\n"))
	if err != nil {
		t.Fatalf("read the token: %v", err)
	}
	if token.Value != "wsp_from_stdin" {
		t.Fatalf("token is %q", token.Value)
	}
	if token.Origin != "stdin" {
		t.Fatalf("origin is %q, want stdin", token.Origin)
	}
}

func TestAHugeFileIsRefusedRatherThanRead(t *testing.T) {
	path := filepath.Join(t.TempDir(), "disk.img")
	writeFile(t, path, strings.Repeat("x", maximumTokenBytes+10))

	_, err := readBootstrapToken(path, strings.NewReader(""))
	if err == nil {
		t.Fatal("a file larger than a token was accepted as one")
	}
	mustContain(t, err.Error(), "bootstrap token")
}

func TestNoTokenFileExplainsWhyThereIsNoTokenFlag(t *testing.T) {
	_, err := readBootstrapToken("", strings.NewReader(""))
	if err == nil {
		t.Fatal("a missing --token-file has to be an error")
	}
	mustContain(t, err.Error(), "--token-file")
	mustContain(t, err.Error(), "ps")
}

// --token is refused with an explanation rather than "flag provided but not defined",
// because somebody typing it expected it to work and needs to know why it does not.
func TestTheTokenFlagIsRefusedWithAnExplanation(t *testing.T) {
	flags := flag.NewFlagSet("install", flag.ContinueOnError)
	flags.SetOutput(io.Discard)
	var tokenFile string
	tokenFlags(flags, &tokenFile)

	err := flags.Parse([]string{"--token=wsp_secret"})
	if err == nil {
		t.Fatal("--token was accepted")
	}
	mustContain(t, err.Error(), "ps")
	mustContain(t, err.Error(), "--token-file")
}

// Not fatal - the file is gone by the time this is read and the token dies at first use -
// but the habit that produced a world-readable credential will produce the next one.
func TestAWorldReadableTokenFileIsReportedAsExposed(t *testing.T) {
	if !permissionsAreReal() {
		t.Skip("this filesystem invents the permission bits it reports")
	}
	directory := t.TempDir()
	path := filepath.Join(directory, "token.txt")
	if err := os.WriteFile(path, []byte("wsp_exposed"), 0o644); err != nil {
		t.Fatalf("write the token: %v", err)
	}

	token, err := readBootstrapToken(path, strings.NewReader(""))
	if err != nil {
		t.Fatalf("read the token: %v", err)
	}
	if !token.WasExposed {
		t.Fatal("a 0644 token file was not reported as exposed")
	}
}

// A token file with whitespace in the middle is a paste of the whole install command.
func TestATokenWithWhitespaceInsideItIsRefused(t *testing.T) {
	path := filepath.Join(t.TempDir(), "token.txt")
	writeFile(t, path, "--token-file wsp_9f2c")

	_, err := readBootstrapToken(path, strings.NewReader(""))
	if err == nil {
		t.Fatal("a pasted command line is not a token")
	}
	mustContain(t, err.Error(), "one token")
}

// permissionsAreReal is false on the platforms where a mode read back from the filesystem
// was invented by the operating system rather than stored.
func permissionsAreReal() bool {
	directory, err := os.MkdirTemp("", "modes")
	if err != nil {
		return false
	}
	defer os.RemoveAll(directory)

	path := filepath.Join(directory, "probe")
	if err := os.WriteFile(path, []byte("x"), 0o600); err != nil {
		return false
	}
	info, err := os.Stat(path)
	if err != nil {
		return false
	}
	return info.Mode().Perm() == 0o600
}
