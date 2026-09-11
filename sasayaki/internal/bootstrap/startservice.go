package bootstrap

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
)

// Writing the systemd units and bringing the service up on the binary that is now on
// disk.
//
// The last third of an installation, and the only part that can fail after something has
// been written - which is why the rollback lives here too: a re-run of the installer that
// replaced the binary and then could not start it puts the old one back rather than
// leaving an operator with a node that is down and a machine they have to unpick.

// writeUnits writes both unit files, every time.
//
// Unconditionally, because working out whether the rendered text differs from what is on
// disk costs more code than writing it, and because an operator who edited the unit by
// hand is told in its first comment that a re-install overwrites it. Drop-ins are the
// supported way to change it and they survive this.
func (i installation) writeUnits() error {
	if err := os.MkdirAll(i.layout.UnitDir, 0o755); err != nil {
		return fmt.Errorf("create %s: %w", i.layout.UnitDir, err)
	}

	parameters := i.layout.unitParameters()
	units := map[string]string{
		i.layout.unitPath(): renderUnit(parameters),
		filepath.Join(i.layout.UnitDir, rollbackUnitName): renderRollbackUnit(parameters),
	}
	for path, contents := range units {
		if err := writeFileAtomically(path, []byte(contents), 0o644); err != nil {
			return err
		}
	}
	fmt.Fprintf(i.out, "Wrote %s and %s\n", i.layout.unitPath(),
		filepath.Join(i.layout.UnitDir, rollbackUnitName))
	return nil
}

// startService enables the unit and brings it up on the binary that is now on disk.
//
// A machine without systemd is not a failure. Containers and WSL distributions started
// without it are both places an operator legitimately runs this, and the node they get is
// a working one that they start themselves - so the installer says exactly that rather
// than pretending it succeeded or refusing to have tried.
func (i installation) startService(ctx context.Context, change binaryChange) error {
	if !i.start {
		fmt.Fprintf(i.out, "Not starting %s: --start=false.\n", unitName)
		return nil
	}
	if i.systemd.booted != nil && !i.systemd.booted() {
		fmt.Fprintf(i.out, "\nThis machine is not running systemd, so %s was written but not "+
			"started.\nRun the daemon with:\n  %s run --config %s --state-dir %s\n",
			unitName, i.layout.BinaryPath, i.layout.ConfigPath, i.layout.StateDir)
		return nil
	}

	if err := i.systemd.reload(ctx); err != nil {
		return err
	}
	if err := i.systemd.enable(ctx, unitName); err != nil {
		return err
	}
	// A previous run may have left the unit failed; the start limit would then refuse this.
	i.systemd.resetFailed(ctx, unitName)
	if err := i.systemd.restart(ctx, unitName); err != nil {
		return err
	}

	watch := newRestartWatch(i.systemd, unitName)
	if i.watch != nil {
		watch = *i.watch
	}
	healthErr := watch.healthy(ctx)
	if healthErr == nil {
		return nil
	}
	if !change.replaced {
		return fmt.Errorf("%s did not come up: %w.\nThe binary was not changed by this run, "+
			"so an older one will not help. `journalctl -u %s -n 50` says why",
			unitName, healthErr, unitName)
	}

	// The binary changed and the node will not start on it: put back the one that worked.
	fmt.Fprintf(i.out, "%s did not come up on the new binary: %v\nRolling back.\n",
		unitName, healthErr)
	steps := newUpgradeSteps(i.layout, i.systemd, i.out)
	if i.watch != nil {
		steps.watch = *i.watch
	}
	if i.probe != nil {
		steps.probe = i.probe
	}
	result, rollbackErr := steps.rollBack(ctx, upgradeMarker{
		PreviousVersion: change.from,
		NewVersion:      change.to,
		Target:          i.layout.BinaryPath,
		PreviousBinary:  i.layout.previousBinaryPath(),
		StartedAt:       i.clock(),
	}, healthErr.Error())
	if rollbackErr != nil {
		return rollbackErr
	}
	return fmt.Errorf("%s", result.GetDetail())
}
