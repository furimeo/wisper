package bootstrap

import (
	"context"
	"flag"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/rpc"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Install is the `install` subcommand: preflight, enrol, and leave a running service
// behind.
//
// Running it twice is normal and must be an upgrade rather than a break (design section
// 7.1). Everything below is written for the second run as much as the first: the node key
// is reused, the unit is rewritten, the binary is only replaced when it differs, and the
// previous one is kept so a bad binary can be undone. The only step that changes shape is
// enrolment - a token means "enrol again", no token means "keep the credential you have".
func Install(ctx context.Context, args []string, out, errOut io.Writer) error {
	flags := flag.NewFlagSet("install", flag.ContinueOnError)
	flags.SetOutput(errOut)
	flags.Usage = func() {
		fmt.Fprintln(errOut, "Usage: sasayaki install --token-file <path> --panel <url>")
		fmt.Fprintln(errOut)
		fmt.Fprintln(errOut, "Checks the machine, enrols it, installs the systemd unit and")
		fmt.Fprintln(errOut, "starts it. Nothing is written if a required check fails.")
		fmt.Fprintln(errOut)
		flags.PrintDefaults()
	}

	var tokenFile string
	tokenFlags(flags, &tokenFile)
	panel := flags.String("panel", "", "the panel's gRPC endpoint, https://panel.example[:port]")
	configPath := flags.String("config", DefaultConfigPath, "where the node credential lives")
	stateDir := flags.String("state-dir", DefaultStateDir, "the node's data directory")
	binaryPath := flags.String("binary-path", DefaultBinaryPath, "where the daemon is installed")
	unitDir := flags.String("unit-dir", unitDirectory, "where the systemd unit is written")
	source := flags.String("binary", "", "the binary to install. Defaults to the one running this command")
	dockerHost := flags.String("docker-host", "", "override the Docker socket for the preflight")
	skipDoctor := flags.Bool("skip-doctor", false,
		"install even when a required preflight check fails. The checks still run: the panel "+
			"refuses an enrolment whose report did not pass, so there is no way to skip them "+
			"entirely")
	start := flags.Bool("start", true, "enable and start the service once it is installed")

	if err := flags.Parse(args); err != nil {
		return err
	}
	if flags.NArg() > 0 {
		return fmt.Errorf("install takes no arguments, got %q", flags.Arg(0))
	}

	return installation{
		layout: layout{
			ConfigPath: *configPath,
			StateDir:   *stateDir,
			BinaryPath: *binaryPath,
			UnitDir:    *unitDir,
		},
		panel:      *panel,
		tokenFile:  tokenFile,
		source:     *source,
		dockerHost: *dockerHost,
		skipDoctor: *skipDoctor,
		start:      *start,
		systemd:    hostSystemd(),
		stdin:      os.Stdin,
		out:        out,
	}.run(ctx)
}

// installation is one run of the installer, with every seam it needs to be tested against
// a machine that does not exist.
type installation struct {
	layout     layout
	panel      string
	tokenFile  string
	source     string
	dockerHost string
	skipDoctor bool
	start      bool

	systemd systemd
	stdin   io.Reader
	out     io.Writer

	// Seams. Nil means this machine and the real implementation.
	host       *machine
	enrol      func(ctx context.Context, request rpc.EnrolmentRequest) (rpc.Credential, error)
	executable func() (string, error)
	probe      func(ctx context.Context, path string) (string, error)
	watch      *restartWatch
	now        func() time.Time
}

func (i installation) run(ctx context.Context) error {
	if err := i.layout.validate(); err != nil {
		return err
	}

	// Resolved before the preflight, because one of the checks is whether this machine can
	// reach the panel, and a re-install that was given no --panel still has one: the
	// endpoint it enrolled against.
	panel := i.resolvePanel()

	// First, and before a single byte is written. An installer that has already created a
	// directory by the time it decides the machine is unsuitable has left the operator
	// something to clean up, and has made the promise in section 7.2 into an approximation.
	host := i.machine(panel)
	report := preflight(ctx, host)
	writeReportText(i.out, report)
	if !report.GetRequiredChecksPassed() && !i.skipDoctor {
		return errFailedPreflight
	}

	credential, err := i.credential(ctx, host, panel, report)
	if err != nil {
		return err
	}

	if err := i.createDirectories(); err != nil {
		return err
	}

	change, err := i.placeBinary(ctx)
	if err != nil {
		return err
	}
	if err := i.writeUnits(); err != nil {
		return err
	}

	if err := i.startService(ctx, change); err != nil {
		return err
	}

	i.summarise(ctx, credential)
	return nil
}

// credential either enrols or keeps what is already here.
//
// A token means enrol: an operator who has just issued one is telling the installer to
// join, whether or not this machine has a credential from an earlier life. No token means
// this is a re-run, and the existing credential is what the node keeps using - which is
// the whole of what makes "run the installer again" a safe thing to say to somebody.
func (i installation) credential(ctx context.Context, host *machine, panel string,
	report *wisperpb.DoctorReport) (rpc.Credential, error) {

	if i.tokenFile == "" {
		existing, existingErr := rpc.LoadCredential(i.layout.ConfigPath)
		if existingErr != nil {
			return rpc.Credential{}, fmt.Errorf("this node is not enrolled and no "+
				"--token-file was given, so there is nothing to install: %w.\n\nIssue a "+
				"bootstrap token from the node's page in the panel and pass it with "+
				"--token-file", existingErr)
		}
		fmt.Fprintf(i.out, "\nAlready enrolled as %s. Keeping the credential in %s.\n",
			existing.String(), i.layout.ConfigPath)
		return existing, nil
	}

	return joining{
		layout:        i.layout,
		panel:         panel,
		tokenFile:     i.tokenFile,
		dockerHost:    i.dockerHost,
		requireDoctor: !i.skipDoctor,
		stdin:         i.stdin,
		out:           i.out,
		report:        report,
		host:          host,
		enrol:         i.enrol,
	}.join(ctx)
}

// createDirectories makes the two places a node writes.
//
// 0700 on both. The state directory holds customer volumes, site releases and database
// dumps; the configuration directory holds this node's credential and its private key.
// Neither has a reason to be readable by anybody but root, and a node is a machine
// customers never get a shell on.
func (i installation) createDirectories() error {
	for _, directory := range []string{i.layout.configDir(), i.layout.StateDir} {
		if err := os.MkdirAll(directory, 0o700); err != nil {
			return fmt.Errorf("create %s: %w", directory, err)
		}
	}
	// 0755, because this one is not private: it is /usr/local/bin on a normal machine and
	// it exists already. It is created for the minimal images where it does not.
	binaryDir := filepath.Dir(i.layout.BinaryPath)
	if err := os.MkdirAll(binaryDir, 0o755); err != nil {
		return fmt.Errorf("create %s: %w", binaryDir, err)
	}
	return nil
}

func (i installation) summarise(ctx context.Context, credential rpc.Credential) {
	fmt.Fprintf(i.out, "\n%s\n", describeCredential(credential, i.layout.ConfigPath))
	if i.systemd.booted != nil && i.systemd.booted() {
		fmt.Fprintf(i.out, "%s is %s. It dials %s and the panel places workloads on it as "+
			"soon as the first heartbeat arrives.\n",
			unitName, i.systemd.state(ctx, unitName), credential.Panel)
		fmt.Fprintf(i.out, "Logs: journalctl -u %s -f\n", unitName)
	}
	fmt.Fprintf(i.out, "Customer data lives in %s and no part of uninstall touches it.\n",
		i.layout.StateDir)
}

// machine is the host being installed onto. One object, described once: the preflight and
// the machine fingerprint have to be talking about the same machine, and building two
// would make that a coincidence rather than a guarantee.
func (i installation) machine(panel string) *machine {
	if i.host != nil {
		return i.host
	}
	return newMachine(PreflightOptions{
		StateDir:   i.layout.StateDir,
		Panel:      panel,
		DockerHost: i.dockerHost,
	})
}

// resolvePanel is where this node is being told to dial.
//
// The flag when there is one, and otherwise the endpoint the node already enrolled
// against - a re-install that was given no --panel is not an instruction to move the node
// to a different panel, and guessing one would be exactly that.
func (i installation) resolvePanel() string {
	if i.panel != "" {
		return i.panel
	}
	if existing, err := rpc.LoadCredential(i.layout.ConfigPath); err == nil {
		return existing.Panel
	}
	return ""
}

func (i installation) clock() time.Time {
	if i.now != nil {
		return i.now().UTC()
	}
	return time.Now().UTC()
}
