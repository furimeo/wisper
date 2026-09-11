package terminal

import (
	"errors"
	"slices"
	"testing"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Which shell a session gets when the panel does not say.
//
// A missing command does not fail the attach: the Engine API hijacks the connection first
// and then writes "OCI runtime exec failed" into it as though the shell had said it. So a
// node that did not check would hand an Alpine customer a terminal containing an error
// message and immediately close it.

func TestAnImageWithBashGetsBash(t *testing.T) {
	test := newHarness(t)
	test.engine.executable = map[string]bool{"/bin/bash": true}

	finished := test.start(t, nil)
	test.pty.stop()
	if err := finish(t, finished); err != nil {
		t.Fatalf("the session failed: %v", err)
	}

	command := test.engine.attachments()[0].Command
	if !slices.Equal(command, []string{"/bin/bash"}) {
		t.Fatalf("opened %v, expected /bin/bash", command)
	}
	probes := test.engine.probed()
	if len(probes) != 1 || !slices.Equal(probes[0], []string{"/bin/bash", "-c", "exit 0"}) {
		t.Fatalf("probed %v, expected one probe of bash", probes)
	}
}

func TestAnImageWithoutBashFallsBackToSh(t *testing.T) {
	test := newHarness(t)
	test.engine.executable = map[string]bool{}

	finished := test.start(t, nil)
	test.pty.stop()
	if err := finish(t, finished); err != nil {
		t.Fatalf("the session failed: %v", err)
	}

	command := test.engine.attachments()[0].Command
	if !slices.Equal(command, []string{"/bin/sh"}) {
		t.Fatalf("opened %v, expected /bin/sh", command)
	}
	// /bin/sh is not probed: it is the shell every image with a shell has, so asking would
	// spend an exec to learn nothing.
	if probes := test.engine.probed(); len(probes) != 1 {
		t.Fatalf("made %d probes, expected only the one for bash: %v", len(probes), probes)
	}
}

// An engine that cannot answer says nothing about the image, so the session takes the
// shell that is always there rather than spending another failed exec on a second guess.
func TestAnUnanswerableEngineFallsBackToSh(t *testing.T) {
	test := newHarness(t)
	test.engine.probeErr = errors.New("docker is not answering")

	finished := test.start(t, nil)
	test.pty.stop()
	if err := finish(t, finished); err != nil {
		t.Fatalf("the session failed: %v", err)
	}

	command := test.engine.attachments()[0].Command
	if !slices.Equal(command, []string{"/bin/sh"}) {
		t.Fatalf("opened %v, expected /bin/sh", command)
	}
}

// A command the panel sent is run as it stands. Substituting a different program because
// this one looked absent would be worse than the error the customer would otherwise see.
func TestACommandFromThePanelIsNeverSecondGuessed(t *testing.T) {
	test := newHarness(t)
	test.engine.executable = map[string]bool{}

	finished := test.start(t, &wisperpb.StartTerminal{Command: []string{"/usr/bin/psql", "-U", "app"}})
	test.pty.stop()
	if err := finish(t, finished); err != nil {
		t.Fatalf("the session failed: %v", err)
	}

	command := test.engine.attachments()[0].Command
	if !slices.Equal(command, []string{"/usr/bin/psql", "-U", "app"}) {
		t.Fatalf("opened %v, expected the panel's own command", command)
	}
	if probes := test.engine.probed(); len(probes) != 0 {
		t.Fatalf("probed %v for a command the panel had already chosen", probes)
	}
}

func TestTheEnvironmentIsSortedAndCarriesATerm(t *testing.T) {
	got := environmentFor(&wisperpb.StartTerminal{Env: map[string]string{
		"LANG":    "C.UTF-8",
		"COLUMNS": "120",
		"":        "nameless",
	}})
	want := []string{"COLUMNS=120", "LANG=C.UTF-8", "TERM=" + defaultTerm}
	if !slices.Equal(got, want) {
		t.Fatalf("the session's environment is %v, expected %v", got, want)
	}
}

func TestATermFromThePanelWins(t *testing.T) {
	got := environmentFor(&wisperpb.StartTerminal{Env: map[string]string{"TERM": "dumb"}})
	if !slices.Equal(got, []string{"TERM=dumb"}) {
		t.Fatalf("the session's environment is %v, expected the panel's own TERM", got)
	}
}

func TestASessionWithNoEnvironmentStillHasATerm(t *testing.T) {
	got := environmentFor(&wisperpb.StartTerminal{})
	if !slices.Equal(got, []string{"TERM=" + defaultTerm}) {
		t.Fatalf("the session's environment is %v, expected only a TERM", got)
	}
}
