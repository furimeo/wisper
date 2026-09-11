package version

import (
	"runtime"
	"strings"
	"testing"
)

func TestShortOmitsAnUnknownCommit(t *testing.T) {
	restoreAfter(t)
	Number, Commit = "v1.2.3", "unknown"

	if got := Short(); got != "v1.2.3" {
		t.Fatalf("Short() = %q, want %q", got, "v1.2.3")
	}
}

func TestShortNamesTheCommitWhenThereIsOne(t *testing.T) {
	restoreAfter(t)
	Number, Commit = "v1.2.3", "a1b2c3d"

	if got := Short(); got != "v1.2.3 (a1b2c3d)" {
		t.Fatalf("Short() = %q, want %q", got, "v1.2.3 (a1b2c3d)")
	}
}

// A bug report is useless without the build it came from, so every field the `version`
// command promises has to actually be in its output.
func TestFullReportsEverythingABugReportNeeds(t *testing.T) {
	restoreAfter(t)
	Number, Commit, BuildDate = "v1.2.3", "a1b2c3d", "2026-09-10T12:00:00Z"

	out := Full()
	for _, want := range []string{
		"sasayaki v1.2.3",
		"a1b2c3d",
		"2026-09-10T12:00:00Z",
		runtime.Version(),
		runtime.GOOS + "/" + runtime.GOARCH,
		"protocol: 1",
	} {
		if !strings.Contains(out, want) {
			t.Errorf("Full() is missing %q:\n%s", want, out)
		}
	}
}

// The build stamps are package variables, so a test that changes one has to put it back
// or the next test reads whatever the last one left behind.
func restoreAfter(t *testing.T) {
	t.Helper()
	number, commit, date := Number, Commit, BuildDate
	t.Cleanup(func() {
		Number, Commit, BuildDate = number, commit, date
	})
}
