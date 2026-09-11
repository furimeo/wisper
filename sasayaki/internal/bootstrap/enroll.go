package bootstrap

import (
	"context"
	"flag"
	"fmt"
	"io"
	"os"
)

// Enroll is the `enroll` subcommand: join a panel and write /etc/wisper/node.json.
//
// It is separate from `install` because an offline node is a supported installation
// (design section 7.1): an operator who has copied the binary onto a machine by hand runs
// this, and nothing about it assumes systemd, a package manager or a route to the panel's
// web interface. `install` is this plus a service.
//
// Re-running it on an enrolled node replaces the credential with a new one. That is what
// an operator wants when they have created a fresh node record after deleting the old
// one; the Ed25519 key is not regenerated, so the panel sees the same machine identity it
// recorded the first time.
func Enroll(ctx context.Context, args []string, out, errOut io.Writer) error {
	flags := flag.NewFlagSet("enroll", flag.ContinueOnError)
	flags.SetOutput(errOut)
	flags.Usage = func() {
		fmt.Fprintln(errOut, "Usage: sasayaki enroll --token-file <path> --panel <url>")
		fmt.Fprintln(errOut)
		fmt.Fprintln(errOut, "Joins a panel with a single-use bootstrap token and writes the")
		fmt.Fprintln(errOut, "credential. The token file is deleted once it has been read.")
		fmt.Fprintln(errOut)
		flags.PrintDefaults()
	}

	var tokenFile string
	tokenFlags(flags, &tokenFile)
	panel := flags.String("panel", "", "the panel's gRPC endpoint, https://panel.example[:port]")
	configPath := flags.String("config", DefaultConfigPath, "where to write the node credential")
	stateDir := flags.String("state-dir", DefaultStateDir, "the node's data directory")
	dockerHost := flags.String("docker-host", "", "override the Docker socket for the preflight")
	skipDoctor := flags.Bool("skip-doctor", false,
		"enrol even when a required preflight check fails. The report is still gathered and "+
			"sent: the panel refuses an enrolment that has not passed one")

	if err := flags.Parse(args); err != nil {
		return err
	}
	if flags.NArg() > 0 {
		return fmt.Errorf("enroll takes no arguments, got %q", flags.Arg(0))
	}

	credential, err := joining{
		layout: layout{
			ConfigPath: *configPath,
			StateDir:   *stateDir,
			BinaryPath: DefaultBinaryPath,
			UnitDir:    unitDirectory,
		},
		panel:         *panel,
		tokenFile:     tokenFile,
		dockerHost:    *dockerHost,
		requireDoctor: !*skipDoctor,
		stdin:         os.Stdin,
		out:           out,
	}.join(ctx)
	if err != nil {
		return err
	}

	fmt.Fprintln(out, describeCredential(credential, *configPath))
	fmt.Fprintln(out, "Nothing is running yet. `sasayaki install` adds the service, or run")
	fmt.Fprintf(out, "`sasayaki run --config %s` to start the daemon in this terminal.\n", *configPath)
	return nil
}
