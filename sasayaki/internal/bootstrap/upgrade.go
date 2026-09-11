package bootstrap

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
)

// Upgrade is the `upgrade` subcommand: replace this node's binary with a verified one.
//
// Three ways in, and they are the three ways an upgrade actually happens:
//
//   - `--url` and `--sha256`, which is what the panel publishes and what an operator
//     copies off the node's page;
//   - `--binary`, for a node with no route to the panel, where the operator carried the
//     file on and checked it themselves;
//   - `--rollback`, which is not run by hand: sasayaki.service names the rollback unit in
//     OnFailure=, and that unit runs this.
//
// Customers are not affected by any of it. sasayaki is a control plane - the containers
// are children of the Docker daemon, not of this process - so restarting it does not
// touch a single running workload (design section 7.5).
func Upgrade(ctx context.Context, args []string, out, errOut io.Writer) error {
	flags := flag.NewFlagSet("upgrade", flag.ContinueOnError)
	flags.SetOutput(errOut)
	flags.Usage = func() {
		fmt.Fprintln(errOut, "Usage: sasayaki upgrade --url <url> --sha256 <hex>")
		fmt.Fprintln(errOut, "       sasayaki upgrade --binary <path>")
		fmt.Fprintln(errOut)
		fmt.Fprintln(errOut, "Verifies the new binary before replacing anything, keeps the one")
		fmt.Fprintln(errOut, "it replaced, and puts it back if the service does not come up.")
		fmt.Fprintln(errOut)
		flags.PrintDefaults()
	}

	url := flags.String("url", "", "where to download the new binary from")
	checksum := flags.String("sha256", "", "the SHA-256 the download must have, as the panel published it")
	local := flags.String("binary", "", "a binary already on this machine, for a node with no route to the panel")
	target := flags.String("target", DefaultBinaryPath, "the installed binary to replace")
	configPath := flags.String("config", DefaultConfigPath, "the node credential, beside which the upgrade marker is kept")
	rollback := flags.Bool("rollback", false,
		"undo an upgrade that did not come back. Run by "+rollbackUnitName+", not by hand")
	force := flags.Bool("force", false, "replace the binary even if it is the version already running")
	restart := flags.Bool("restart", true, "restart the service once the binary is in place")

	if err := flags.Parse(args); err != nil {
		return err
	}
	if flags.NArg() > 0 {
		return fmt.Errorf("upgrade takes no arguments, got %q", flags.Arg(0))
	}

	steps := newUpgradeSteps(layout{
		ConfigPath: *configPath,
		StateDir:   DefaultStateDir,
		BinaryPath: *target,
		UnitDir:    unitDirectory,
	}, hostSystemd(), out)

	if *rollback {
		if *url != "" || *local != "" {
			return errors.New("--rollback goes back to the binary this node was upgraded " +
				"from; it takes no source. Use --binary on its own to install a specific file")
		}
		_, err := steps.rollBackInFlight(ctx)
		return err
	}

	source, err := chooseUpgradeSource(*url, *checksum, *local)
	if err != nil {
		return err
	}

	result, err := steps.apply(ctx, upgradeIntent{
		source:  source,
		force:   *force,
		restart: *restart,
		// Run from a terminal or from a systemd unit that is not sasayaki.service, this
		// process outlives the restart and can watch what happens. That is what makes the
		// rollback below immediate rather than something systemd gets to a minute later.
		supervise: true,
	})
	if err != nil {
		return err
	}

	fmt.Fprintln(out, result.GetDetail())
	if result.GetRolledBack() {
		return fmt.Errorf("the upgrade did not take: this node is still on %s",
			orUnknown(result.GetPreviousVersion()))
	}
	return nil
}

// chooseUpgradeSource refuses the combinations that would install something unverified.
//
// A URL without a checksum is the one case worth spelling out at length: it looks like it
// works, it does work, and it turns the upgrade path into a way to run arbitrary code on
// every node in a fleet as root. The panel always sends both.
func chooseUpgradeSource(url, checksum, local string) (upgradeSource, error) {
	switch {
	case url != "" && local != "":
		return upgradeSource{}, errors.New("--url and --binary are two answers to the same " +
			"question. Pass one")

	case url != "":
		if normaliseChecksum(checksum) == "" {
			return upgradeSource{}, errors.New("--url needs --sha256. A download nobody " +
				"checks is a way to run anything on this node as root; the panel shows the " +
				"hash of every binary it publishes next to the download")
		}
		return upgradeSource{url: url, sha256: checksum}, nil

	case local != "":
		// No checksum is allowed here, and only here: the operator put the file on this
		// machine themselves, so they are the verification. When they pass one anyway it is
		// checked like any other.
		return upgradeSource{localPath: local, sha256: checksum}, nil

	default:
		return upgradeSource{}, errors.New("nothing to install: pass --url with --sha256, " +
			"or --binary for a file already on this machine")
	}
}
