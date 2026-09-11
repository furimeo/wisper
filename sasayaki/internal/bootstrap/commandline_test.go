package bootstrap

import (
	"context"
	"errors"
	"flag"
	"io"
	"strings"
	"testing"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// docs/contracts/sasayaki-commands.md is binding on all five of these: the signature, the
// exit codes, and the promise that -h is answered without doing anything. cmd/sasayaki
// turns flag.ErrHelp into exit 0 and everything else into exit 1, so a command that
// swallowed ErrHelp would make `sasayaki install -h` an installation failure.

func commands() map[string]func(context.Context, []string, io.Writer, io.Writer) error {
	return map[string]func(context.Context, []string, io.Writer, io.Writer) error{
		"doctor":    Doctor,
		"enroll":    Enroll,
		"install":   Install,
		"uninstall": Uninstall,
		"upgrade":   Upgrade,
	}
}

func TestEveryCommandAnswersMinusHWithoutTouchingTheMachine(t *testing.T) {
	for name, command := range commands() {
		var out, errOut strings.Builder
		err := command(context.Background(), []string{"-h"}, &out, &errOut)

		if !errors.Is(err, flag.ErrHelp) {
			t.Errorf("%s -h returned %v, want flag.ErrHelp so the process exits 0", name, err)
		}
		if !strings.Contains(errOut.String(), "Usage:") {
			t.Errorf("%s -h printed no usage:\n%s", name, errOut.String())
		}
	}
}

// A positional argument is a mistyped flag, and it is caught before anything runs rather
// than being ignored.
func TestEveryCommandRefusesAPositionalArgument(t *testing.T) {
	for name, command := range commands() {
		var out, errOut strings.Builder
		err := command(context.Background(), []string{"whoops"}, &out, &errOut)

		if err == nil {
			t.Errorf("%s accepted a positional argument", name)
			continue
		}
		if !strings.Contains(err.Error(), "whoops") {
			t.Errorf("%s complained about something other than the argument: %v", name, err)
		}
	}
}

// Every command that takes a token refuses --token, and says why: somebody typing it
// expected it to work.
func TestEveryEnrollingCommandRefusesTheTokenFlag(t *testing.T) {
	for _, name := range []string{"enroll", "install"} {
		command := commands()[name]
		var out, errOut strings.Builder

		err := command(context.Background(), []string{"--token=wsp_secret"}, &out, &errOut)

		if err == nil {
			t.Errorf("%s accepted --token", name)
			continue
		}
		if !strings.Contains(err.Error(), "ps") {
			t.Errorf("%s refused --token without explaining why: %v", name, err)
		}
	}
}

// `upgrade` with nothing to install must not go looking. It is the one command an
// operator runs on a node that is already working, so a mistake has to cost nothing.
func TestUpgradeWithNoSourceInstallsNothing(t *testing.T) {
	var out, errOut strings.Builder

	err := Upgrade(context.Background(), []string{}, &out, &errOut)

	if err == nil {
		t.Fatal("upgrade with no source reported success")
	}
	mustContain(t, err.Error(), "--url")
	mustContain(t, err.Error(), "--binary")
}

// The summary line is what an operator reads instead of the whole report, so it has to
// carry the one decision the exit code is made from.
func TestTheReportSummaryStatesTheVerdict(t *testing.T) {
	var out strings.Builder
	writeReportText(&out, &wisperpb.DoctorReport{
		RequiredChecksPassed: true,
		AgentVersion:         "v0.4.0",
		Checks: []*wisperpb.DoctorCheck{
			passed("kernel.cgroups2", "cgroup v2", severityRequired, "mounted at /sys/fs/cgroup"),
			check("runtime.runsc", "gVisor", severityAdvisory, outcomeWarn,
				"runsc is not installed", "Install gVisor."),
		},
	})

	mustContain(t, out.String(), "0 failures, 1 warning.")
	mustContain(t, out.String(), "This machine can host workloads.")
	// A warning prints its remedy underneath it; a pass has nothing to remedy.
	mustContain(t, out.String(), "Install gVisor.")
}

// Sizes are read off a terminal by a person deciding whether a node is big enough. An
// off-by-one in the unit turns sixteen gibibytes of RAM into sixteen tebibytes.
func TestSizesAreFormattedInTheUnitTheyAreIn(t *testing.T) {
	for _, item := range []struct {
		bytes int64
		want  string
	}{
		{-1, "unknown"},
		{512, "512 B"},
		{2048, "2.0 KiB"},
		{16 << 20, "16.0 MiB"},
		{16318664 * 1024, "15.6 GiB"},
		{2 << 40, "2.0 TiB"},
	} {
		if got := formatBytes(item.bytes); got != item.want {
			t.Errorf("formatBytes(%d) = %q, want %q", item.bytes, got, item.want)
		}
	}
}
