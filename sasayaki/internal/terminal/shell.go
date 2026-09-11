package terminal

import (
	"context"
	"log/slog"
	"slices"
	"strings"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Choosing what to run, and finding out whether it is there.
//
// The panel resolves the command and sends it, so the node does not guess: a customer who
// opens two terminals gets the same shell twice (terminal.proto, StartTerminal.command).
// What the node still has to answer is the request that names no command, which is what
// the panel sends when nobody has configured one - and the right answer is a property of
// the image rather than of the panel's database. Debian has bash. Alpine has ash at
// /bin/sh and no bash at all. A distroless image has neither.
//
// Hence the ladder, and hence the probe. The probe matters more than it looks: a missing
// command does not fail the attach. The Engine API hijacks the connection first and then
// writes `OCI runtime exec failed: ... "/bin/bash": stat /bin/bash: no such file or
// directory` into it as though the shell had said it, and sets the exit code to 126. A
// node that skipped the probe would therefore hand the customer a terminal containing a
// runtime error and immediately close it, which is exactly the class of experience this
// project exists to stop shipping.

const (
	// How long the probe may take. It is one exec of `-c exit 0` against a container that
	// is already running, so it is milliseconds; the ceiling is here because a wedged
	// engine must not turn "open a terminal" into a request that never returns.
	probeTimeout = 5 * time.Second

	// What TERM becomes when the request does not carry one. The browser is xterm.js and
	// it is xterm-compatible, and a shell with no TERM at all makes curses applications
	// refuse to start - which a customer reads as "the terminal is broken" rather than as
	// "an environment variable is missing".
	defaultTerm = "xterm-256color"
)

// shells is the ladder, most pleasant first.
//
// Only the entries before the last one are probed. /bin/sh is POSIX and exists in every
// image that has a shell at all, so probing it would spend an exec to learn nothing; if it
// is missing too then the image has no shell, and the attach reports that with the engine's
// own words rather than this package inventing a diagnosis.
var shells = [][]string{
	{"/bin/bash"},
	{"/bin/sh"},
}

// commandFor is the argv this session will run.
//
// A command the panel sent is used as it stands and is never probed: the panel asked for
// something specific, and quietly substituting a different program because the first one
// looked absent would be worse than the error the customer would otherwise see.
func (h *Host) commandFor(ctx context.Context, containerID string, request *wisperpb.StartTerminal, log *slog.Logger) []string {
	if command := request.GetCommand(); len(command) > 0 {
		return command
	}

	for index, candidate := range shells {
		if index == len(shells)-1 {
			return candidate
		}

		probe := append(append([]string{}, candidate...), "-c", "exit 0")
		attempt, cancel := context.WithTimeout(ctx, probeTimeout)
		usable, err := h.engine.CanExecute(attempt, containerID, probe)
		cancel()

		switch {
		case err != nil:
			// The engine could not answer, which says nothing about the image. Stop
			// probing and take the shell every image has: spending another failed exec to
			// ask a second question of an engine that did not answer the first one only
			// delays the terminal.
			log.Debug("could not check which shells this image has, falling back to the POSIX one",
				slog.String("container", containerID),
				slog.String("shell", candidate[0]),
				slog.String("error", err.Error()))
			return shells[len(shells)-1]
		case usable:
			return candidate
		default:
			log.Debug("this image does not have that shell, trying the next one",
				slog.String("container", containerID),
				slog.String("shell", candidate[0]))
		}
	}

	// Unreachable while shells is non-empty, and the loop above returns on its last
	// iteration regardless. Written out rather than left to a zero value because an empty
	// argv means "the image's entrypoint" to the Engine API, which is not what anybody
	// asked for.
	return []string{"/bin/sh"}
}

// environmentFor is what this session adds on top of the workload's own environment.
//
// Sorted, because a map's iteration order is deliberately random and an exec whose
// arguments change between two identical requests cannot be reasoned about - or asserted
// on. TERM is filled in when the panel did not send one; anything the panel did send wins,
// including a TERM the customer configured themselves.
func environmentFor(request *wisperpb.StartTerminal) []string {
	extra := request.GetEnv()
	environment := make([]string, 0, len(extra)+1)

	names := make([]string, 0, len(extra))
	for name := range extra {
		if strings.TrimSpace(name) == "" {
			// A variable with no name is not one. The Engine API would pass "=value"
			// through to the process, where it is at best ignored.
			continue
		}
		names = append(names, name)
	}
	slices.Sort(names)

	for _, name := range names {
		environment = append(environment, name+"="+extra[name])
	}
	if _, set := extra["TERM"]; !set {
		environment = append(environment, "TERM="+defaultTerm)
	}
	return environment
}
