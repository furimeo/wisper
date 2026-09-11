package bootstrap

import (
	"context"
	"fmt"
	"os/exec"
	"strings"
)

// The states systemctl prints, named so the code that branches on them reads like the
// question it is asking rather than like string comparison.
const (
	unitActive     = "active"
	unitActivating = "activating"
	unitFailed     = "failed"
	unitInactive   = "inactive"
	unitAbsent     = "absent"
)

// systemd is every conversation this package has with the init system.
//
// A struct with two function fields rather than direct calls to exec, because the
// properties this package exists to hold up are all statements about what it did and did
// not do to a machine - "a failed preflight installed nothing", "uninstall left the state
// directory alone", "a broken upgrade put the old binary back and restarted" - and a test
// can only assert those against a machine it controls. The real implementation is four
// lines; the seam is what makes the other four hundred provable.
type systemd struct {
	// run executes systemctl with an argument slice and returns its combined output.
	// Never a shell string: nothing here interpolates anything into a command line.
	run func(ctx context.Context, args ...string) (string, error)

	// booted reports whether systemd is the init system on this machine. It is false in
	// a container and in a WSL distribution started without systemd, both of which are
	// places an operator legitimately runs `install` - so it is a branch rather than a
	// failure.
	booted func() bool
}

// hostSystemd talks to the real init system.
func hostSystemd() systemd {
	return systemd{
		run: func(ctx context.Context, args ...string) (string, error) {
			output, err := exec.CommandContext(ctx, "systemctl", args...).CombinedOutput()
			return strings.TrimSpace(string(output)), err
		},
		// The presence of this directory is what sd_booted() checks, and it is true only
		// when systemd is PID 1.
		booted: func() bool { return exists("/run/systemd/system") },
	}
}

// reload makes systemd read a unit file this package has just written. Every change to a
// unit is invisible until this runs, which is why it is called even when the unit was
// only rewritten with the same contents - working out whether it changed is more code
// than doing it.
func (s systemd) reload(ctx context.Context) error {
	return s.command(ctx, "reload the systemd unit files", "daemon-reload")
}

// enable makes the unit start at boot. Not --now: install restarts explicitly afterwards,
// because `enable --now` on a unit that is already running does nothing at all and the
// whole point of re-running the installer is to pick up a new binary.
func (s systemd) enable(ctx context.Context, unit string) error {
	return s.command(ctx, "enable "+unit, "enable", unit)
}

// disable stops the unit starting at boot. A unit that was never enabled is not an error:
// uninstall runs this on a half-installed node and has nothing to complain about.
func (s systemd) disable(ctx context.Context, unit string) error {
	if _, err := s.run(ctx, "disable", unit); err != nil {
		return nil
	}
	return nil
}

// restart starts the unit, whether or not it was running. `restart` rather than `start`
// because both callers - install after placing a new binary, upgrade after swapping one -
// want the process that comes out of it to be running the file that is on disk now.
func (s systemd) restart(ctx context.Context, unit string) error {
	return s.command(ctx, "restart "+unit, "restart", unit)
}

// resetFailed clears a unit's failure counter.
//
// Without it a rollback cannot work. The unit's StartLimitBurst is what turns a
// crash-looping daemon into a failed unit and fires the rollback in the first place, and
// while that limit is tripped systemd refuses to start the unit again - "start request
// repeated too quickly" - however good the binary now on disk is. A rollback that put the
// right file back and then could not start it would be the most infuriating possible
// outcome, so this runs before every restart in that path.
//
// Not an error when the unit was never failed: that is the ordinary case.
func (s systemd) resetFailed(ctx context.Context, unit string) {
	_, _ = s.run(ctx, "reset-failed", unit)
}

// stop halts the unit and waits for it. A unit that is not running is not an error.
func (s systemd) stop(ctx context.Context, unit string) error {
	if _, err := s.run(ctx, "stop", unit); err != nil {
		return nil
	}
	return nil
}

// state is what systemctl is-active says, normalised.
//
// The exit code is deliberately ignored: is-active exits 3 for an inactive unit and 4 for
// one that does not exist, and treating those as errors would turn "the service is not
// running", which is the answer, into "the check failed", which is not. An empty output
// with an error means systemctl itself could not run, and that is reported as absent
// rather than guessed at.
func (s systemd) state(ctx context.Context, unit string) string {
	output, err := s.run(ctx, "is-active", unit)
	answer := strings.TrimSpace(lastLine(output))
	switch answer {
	case unitActive, unitActivating, unitFailed, unitInactive:
		return answer
	case "":
		if err != nil {
			return unitAbsent
		}
		return unitInactive
	default:
		// "deactivating", "reloading", "unknown" and anything a future systemd invents.
		return answer
	}
}

// installed reports whether systemd knows about the unit at all, which is what uninstall
// needs before it decides there is something to stop.
func (s systemd) installed(ctx context.Context, unit string) bool {
	output, err := s.run(ctx, "cat", unit)
	return err == nil && strings.TrimSpace(output) != ""
}

func (s systemd) command(ctx context.Context, what string, args ...string) error {
	output, err := s.run(ctx, args...)
	if err == nil {
		return nil
	}
	if output == "" {
		return fmt.Errorf("could not %s: %w", what, err)
	}
	return fmt.Errorf("could not %s: %w: %s", what, err, output)
}

// lastLine is the answer when systemctl has prefixed its output with a warning, which it
// does for a unit whose file changed since it was loaded.
func lastLine(output string) string {
	lines := strings.Split(strings.TrimSpace(output), "\n")
	return lines[len(lines)-1]
}
