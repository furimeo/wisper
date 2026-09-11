// Command sasayaki is the wisper node daemon and the tool that installs it.
//
// One binary does everything a node needs: it checks the machine before touching it,
// enrols against a panel, installs itself as a systemd unit, upgrades itself, removes
// itself, and - the actual job - runs the reconcile loop that keeps the machine matching
// the spec the panel published.
//
// There is no command framework here on purpose. Seven subcommands, each owning its own
// flags, is a switch statement; a dependency would only hide it.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"os/signal"
	"sort"
	"syscall"

	"github.com/furimeo/wisper/sasayaki/internal/bootstrap"
	"github.com/furimeo/wisper/sasayaki/internal/daemon"
	"github.com/furimeo/wisper/sasayaki/internal/version"
)

// Exit codes. The installer script branches on these, so they are part of the contract
// with deploy/install.sh and must not be renumbered.
const (
	exitOK      = 0
	exitFailure = 1
	exitUsage   = 2
)

// A subcommand. Flag parsing belongs to the package that owns the command, because the
// flags and the thing they configure change together.
type command struct {
	summary string
	run     func(ctx context.Context, args []string, out, errOut io.Writer) error
}

func commands() map[string]command {
	return map[string]command{
		"run": {
			summary: "Run the daemon: connect to the panel and reconcile continuously.",
			run:     daemon.Run,
		},
		"doctor": {
			summary: "Check this machine can host workloads, and change nothing.",
			run:     bootstrap.Doctor,
		},
		"enroll": {
			summary: "Join a panel using a single-use bootstrap token.",
			run:     bootstrap.Enroll,
		},
		"install": {
			summary: "Install the systemd unit and enrol, after doctor passes.",
			run:     bootstrap.Install,
		},
		"uninstall": {
			summary: "Remove the service and the binary. Customer data is left alone.",
			run:     bootstrap.Uninstall,
		},
		"upgrade": {
			summary: "Replace this binary with a newer verified one and restart.",
			run:     bootstrap.Upgrade,
		},
		"version": {
			summary: "Print the build, the platform and the protocol version.",
			run:     printVersion,
		},
	}
}

func main() {
	os.Exit(dispatch(os.Args[1:], os.Stdout, os.Stderr))
}

func dispatch(args []string, out, errOut io.Writer) int {
	if len(args) == 0 {
		usage(errOut)
		return exitUsage
	}

	name := args[0]
	if name == "help" || name == "-h" || name == "--help" {
		usage(out)
		return exitOK
	}

	command, known := commands()[name]
	if !known {
		fmt.Fprintf(errOut, "sasayaki: unknown command %q\n\n", name)
		usage(errOut)
		return exitUsage
	}

	/*
	 * One context for the whole process, cancelled on the first signal.
	 *
	 * The daemon is crash-only - the truth is on disk and being killed is the same as
	 * shutting down - but "no cleanup" is not the same as "no shutdown". A cancelled
	 * context lets the reconcile loop finish the action it is in the middle of and let
	 * go of the Docker connection, rather than being cut off mid-write. A second signal
	 * bypasses this entirely: stop resets the handler, so the default behaviour of
	 * terminating immediately comes back for anyone who has run out of patience.
	 */
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	err := command.run(ctx, args[1:], out, errOut)
	switch {
	case err == nil:
		return exitOK
	case errors.Is(err, flag.ErrHelp):
		// The command already printed its own usage.
		return exitOK
	case errors.Is(err, context.Canceled):
		// Ctrl-C during a long operation is a choice, not a failure.
		fmt.Fprintln(errOut, "sasayaki: interrupted")
		return exitOK
	default:
		fmt.Fprintf(errOut, "sasayaki %s: %v\n", name, err)
		return exitFailure
	}
}

func printVersion(_ context.Context, args []string, out, _ io.Writer) error {
	flags := flag.NewFlagSet("version", flag.ContinueOnError)
	flags.SetOutput(out)
	short := flags.Bool("short", false, "print just the version number")
	if err := flags.Parse(args); err != nil {
		return err
	}

	if *short {
		fmt.Fprintln(out, version.Number)
		return nil
	}
	fmt.Fprint(out, version.Full())
	return nil
}

func usage(w io.Writer) {
	fmt.Fprintf(w, "sasayaki %s - the wisper node daemon\n\n", version.Short())
	fmt.Fprintln(w, "Usage:")
	fmt.Fprintln(w, "  sasayaki <command> [flags]")
	fmt.Fprintln(w)
	fmt.Fprintln(w, "Commands:")

	all := commands()
	names := make([]string, 0, len(all))
	for name := range all {
		names = append(names, name)
	}
	sort.Strings(names)
	for _, name := range names {
		fmt.Fprintf(w, "  %-10s %s\n", name, all[name].summary)
	}

	fmt.Fprintln(w)
	fmt.Fprintln(w, "Run `sasayaki <command> -h` for the flags a command takes.")
	fmt.Fprintln(w)
	fmt.Fprintln(w, "Tokens are never read from the command line: argv is visible to every")
	fmt.Fprintln(w, "user on the machine through ps. Use --token-file, or pipe on stdin.")
}
