package bootstrap

import (
	"bufio"
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"

	"github.com/furimeo/wisper/sasayaki/internal/rpc"
)

// Uninstall is the `uninstall` subcommand: take the service off this machine.
//
// The rule it exists to enforce is one sentence of design section 7.7 - the default never
// destroys anything - and the reason is that the two operations an administrator confuses
// are "stop running wisper here" and "delete what the customers on this node own". The
// first is routine. The second is irreversible, and the only way to it is --purge plus
// typing the node's own name.
func Uninstall(ctx context.Context, args []string, out, errOut io.Writer) error {
	flags := flag.NewFlagSet("uninstall", flag.ContinueOnError)
	flags.SetOutput(errOut)
	flags.Usage = func() {
		fmt.Fprintln(errOut, "Usage: sasayaki uninstall [--purge]")
		fmt.Fprintln(errOut)
		fmt.Fprintln(errOut, "Removes the service and the binary. Customer data in the state")
		fmt.Fprintln(errOut, "directory is left exactly where it is unless --purge is given,")
		fmt.Fprintln(errOut, "and --purge asks for the node's name to be typed back.")
		fmt.Fprintln(errOut)
		flags.PrintDefaults()
	}

	configPath := flags.String("config", DefaultConfigPath, "the node credential")
	stateDir := flags.String("state-dir", DefaultStateDir, "the node's data directory")
	binaryPath := flags.String("binary-path", DefaultBinaryPath, "the installed daemon")
	unitDir := flags.String("unit-dir", unitDirectory, "where the systemd unit was written")
	purge := flags.Bool("purge", false,
		"also delete every customer volume, site, database dump and certificate on this node")

	if err := flags.Parse(args); err != nil {
		return err
	}
	if flags.NArg() > 0 {
		return fmt.Errorf("uninstall takes no arguments, got %q", flags.Arg(0))
	}

	return removal{
		layout: layout{
			ConfigPath: *configPath,
			StateDir:   *stateDir,
			BinaryPath: *binaryPath,
			UnitDir:    *unitDir,
		},
		purge:   *purge,
		systemd: hostSystemd(),
		stdin:   os.Stdin,
		out:     out,
	}.run(ctx)
}

// removal is one uninstall, with the machine behind a seam so a test can prove the state
// directory survived.
type removal struct {
	layout  layout
	purge   bool
	systemd systemd
	stdin   io.Reader
	out     io.Writer
}

func (r removal) run(ctx context.Context) error {
	if err := r.layout.validate(); err != nil {
		return err
	}

	// The confirmation comes first, before anything is stopped. An operator who typed the
	// wrong thing gets a node that is still running, rather than one that is stopped,
	// unenrolled and half removed.
	if r.purge {
		if err := r.confirmPurge(); err != nil {
			return err
		}
	}

	r.stopService(ctx)
	if err := r.removeUnits(ctx); err != nil {
		return err
	}
	if err := r.removeBinaries(); err != nil {
		return err
	}
	if !r.purge {
		r.reportWhatSurvived()
		return nil
	}
	return r.destroyData()
}

// confirmPurge asks for the node's name and accepts nothing else.
//
// The name rather than "yes" because "yes" is what a person types without reading, and
// because the name is what tells them which machine they are standing on. It is read from
// stdin, never from a flag: a confirmation that can be passed on the command line is a
// confirmation that ends up in somebody's shell script.
func (r removal) confirmPurge() error {
	phrase, subject := r.confirmationPhrase()

	fmt.Fprintf(r.out, "--purge deletes everything under %s: every customer volume, every\n"+
		"site release, every database dump and every certificate on this node. It cannot\n"+
		"be undone from here, and the panel cannot put it back.\n\n", r.layout.StateDir)
	fmt.Fprintf(r.out, "Type %s (%s) to confirm: ", phrase, subject)

	reader := bufio.NewReader(r.stdin)
	typed, err := reader.ReadString('\n')
	if err != nil && !errors.Is(err, io.EOF) {
		return fmt.Errorf("read the confirmation: %w", err)
	}
	if strings.TrimSpace(typed) != phrase {
		return fmt.Errorf("that is not %q, so nothing was deleted. The service and the "+
			"binary are still installed", phrase)
	}
	return nil
}

// confirmationPhrase is the node's name when this machine is enrolled, and the state
// directory when it is not - a node that never enrolled has no name, and the path is the
// other thing an operator can read off the screen and check.
func (r removal) confirmationPhrase() (phrase, subject string) {
	credential, err := rpc.LoadCredential(r.layout.ConfigPath)
	if err == nil && credential.NodeName != "" {
		return credential.NodeName, "this node's name"
	}
	return r.layout.StateDir, "the directory to be deleted"
}

func (r removal) stopService(ctx context.Context) {
	if r.systemd.booted != nil && !r.systemd.booted() {
		return
	}
	if !r.systemd.installed(ctx, unitName) {
		return
	}
	fmt.Fprintf(r.out, "Stopping %s\n", unitName)
	if err := r.systemd.stop(ctx, unitName); err != nil {
		fmt.Fprintf(r.out, "  %v\n", err)
	}
	if err := r.systemd.disable(ctx, unitName); err != nil {
		fmt.Fprintf(r.out, "  %v\n", err)
	}
	// So a unit left in the failed state does not keep showing up in `systemctl
	// --failed` on a machine wisper is no longer installed on.
	r.systemd.resetFailed(ctx, unitName)
}

func (r removal) removeUnits(ctx context.Context) error {
	removed := false
	for _, path := range []string{
		r.layout.unitPath(),
		filepath.Join(r.layout.UnitDir, rollbackUnitName),
	} {
		gone, err := removeIfPresent(path)
		if err != nil {
			return err
		}
		if gone {
			fmt.Fprintf(r.out, "Removed %s\n", path)
			removed = true
		}
	}

	if removed && (r.systemd.booted == nil || r.systemd.booted()) {
		if err := r.systemd.reload(ctx); err != nil {
			return err
		}
	}
	return nil
}

func (r removal) removeBinaries() error {
	for _, path := range []string{r.layout.BinaryPath, r.layout.previousBinaryPath()} {
		gone, err := removeIfPresent(path)
		if err != nil {
			return err
		}
		if gone {
			fmt.Fprintf(r.out, "Removed %s\n", path)
		}
	}
	return nil
}

// reportWhatSurvived is the default ending, and it is deliberately explicit.
//
// An operator who wanted the data gone needs to be told it is still there and how to get
// rid of it; an operator who did not needs to be told their customers' files are safe.
// The same three lines answer both.
func (r removal) reportWhatSurvived() {
	fmt.Fprintf(r.out, "\nsasayaki is no longer installed on this machine.\n\n")
	fmt.Fprintf(r.out, "Left alone, deliberately:\n")
	fmt.Fprintf(r.out, "  %-28s customer volumes, sites, databases and certificates\n",
		r.layout.StateDir)
	if exists(r.layout.configDir()) {
		fmt.Fprintf(r.out, "  %-28s this node's credential and key\n", r.layout.configDir())
	}
	fmt.Fprintf(r.out, "\nRun `sasayaki uninstall --purge` to delete them, or delete the node "+
		"in the panel\nand copy %s somewhere else first.\n", r.layout.StateDir)
}

// destroyData is the only code in wisper that deletes a customer's files.
//
// The guard below is not paranoia about a hypothetical: a layout whose state directory
// came out as "/" or "/var" would remove an operating system, and the flag that gets here
// is one keystroke from the one that does not. Two path components is the shallowest
// anything wisper is ever installed under.
func (r removal) destroyData() error {
	for _, directory := range []string{r.layout.StateDir, r.layout.configDir()} {
		if err := refuseShallowPath(directory); err != nil {
			return err
		}
	}

	for _, directory := range []string{r.layout.StateDir, r.layout.configDir()} {
		if !exists(directory) {
			continue
		}
		if err := os.RemoveAll(directory); err != nil {
			return fmt.Errorf("delete %s: %w", directory, err)
		}
		fmt.Fprintf(r.out, "Deleted %s\n", directory)
	}

	fmt.Fprintf(r.out, "\nsasayaki is gone and so is everything it was keeping. Delete the "+
		"node in the panel\nto stop it waiting for this machine to come back.\n")
	return nil
}

func refuseShallowPath(path string) error {
	cleaned := filepath.Clean(path)
	components := 0
	for _, part := range strings.Split(filepath.ToSlash(cleaned), "/") {
		if part != "" && part != "." {
			components++
		}
	}
	if components < 2 {
		return fmt.Errorf("refusing to delete %q: it is too close to the root of the "+
			"filesystem to be a wisper state directory", path)
	}
	return nil
}

// removeIfPresent deletes a path and says whether there was one. Absence is the normal
// outcome on a half-installed node and is not an error.
func removeIfPresent(path string) (bool, error) {
	err := os.Remove(path)
	switch {
	case err == nil:
		return true, nil
	case errors.Is(err, os.ErrNotExist):
		return false, nil
	default:
		return false, fmt.Errorf("remove %s: %w", path, err)
	}
}
