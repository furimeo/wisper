// Package bootstrap is a node's whole life outside the reconcile loop: checking the
// machine, joining a panel, installing itself as a service, replacing itself with a newer
// build and taking itself off again.
//
// Design section 7 is the specification, and one sentence in it shapes everything here:
// there is no step that asks an administrator to trust something blindly. Concretely,
// that means four properties this package exists to hold up, each of which is also a
// test:
//
//   - Doctor changes nothing. The installer runs it first, so "the preflight failed and
//     nothing was installed" has to be literally true rather than approximately true.
//   - A bootstrap token never appears in argv, which every user on the machine can read
//     out of ps. Only a file, which is destroyed after it is read, or stdin.
//   - Re-running the installer is an upgrade. Installing twice is what an administrator
//     does, so it cannot be what breaks the node.
//   - Uninstall does not delete customer data. Only --purge does, and only after the
//     node's own name has been typed back.
//
// Nothing in this package is reachable from the reconcile loop, and nothing in it knows
// about Docker beyond asking the engine what version it is. The one thing the daemon
// borrows is [Preflight], which it re-runs on every reconnect so the panel is placing
// workloads against facts from this minute rather than from installation day.
package bootstrap

import (
	"context"
	"flag"
	"fmt"
	"io"
)

// Doctor is the `doctor` subcommand: inspect this machine and change nothing on it.
//
// The exit code is the contract with deploy/install.sh, which branches on it: zero when
// every required check passed, one when any of them failed. A warning never fails the
// command - a node with no runsc is a node that works and is less isolated, and refusing
// to install one would be the check making the decision instead of the administrator
// (design section 7.2).
func Doctor(ctx context.Context, args []string, out, errOut io.Writer) error {
	flags := flag.NewFlagSet("doctor", flag.ContinueOnError)
	flags.SetOutput(errOut)
	flags.Usage = func() {
		fmt.Fprintln(errOut, "Usage: sasayaki doctor [flags]")
		fmt.Fprintln(errOut)
		fmt.Fprintln(errOut, "Inspects this machine and writes nothing. Exits non-zero when a")
		fmt.Fprintln(errOut, "required check fails; a missing runsc is a warning, not a failure.")
		fmt.Fprintln(errOut)
		flags.PrintDefaults()
	}

	asJSON := flags.Bool("json", false, "print the machine-readable report the installer parses")
	stateDir := flags.String("state-dir", DefaultStateDir,
		"the directory whose filesystem decides whether disk quotas can be enforced")
	panel := flags.String("panel", "",
		"the panel endpoint, which turns on the reachability and clock-offset checks")
	dockerHost := flags.String("docker-host", "",
		"override the Docker socket; defaults to DOCKER_HOST or the platform default")

	if err := flags.Parse(args); err != nil {
		return err
	}
	if flags.NArg() > 0 {
		return fmt.Errorf("doctor takes no arguments, got %q", flags.Arg(0))
	}

	report := Preflight(ctx, PreflightOptions{
		StateDir:   *stateDir,
		Panel:      *panel,
		DockerHost: *dockerHost,
	})

	if *asJSON {
		if err := writeReportJSON(out, report); err != nil {
			return err
		}
	} else {
		writeReportText(out, report)
	}

	if !report.GetRequiredChecksPassed() {
		// Deliberately terse. Everything worth saying is in the report that was just
		// printed, and repeating it here would be the second copy that goes stale.
		return errFailedPreflight
	}
	return nil
}

// errFailedPreflight is what doctor returns when a required check failed. It is a
// sentinel because the installer catches it: install has to stop without writing, and it
// has to be able to tell "the machine is not ready" from "the check itself broke".
var errFailedPreflight = fmt.Errorf("a required preflight check failed")
