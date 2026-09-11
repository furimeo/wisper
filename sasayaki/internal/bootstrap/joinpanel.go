package bootstrap

import (
	"context"
	"errors"
	"fmt"
	"io"
	"os"

	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"

	"github.com/furimeo/wisper/sasayaki/internal/rpc"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// joining is one attempt to turn a bootstrap token into a credential on disk.
//
// Shared by `enroll` and `install` because they do the same thing here and any difference
// between them would be a difference in what the panel is told about the machine - which
// is the one place two code paths must not disagree.
type joining struct {
	layout     layout
	panel      string
	tokenFile  string
	dockerHost string

	// requireDoctor stops the enrolment when a required preflight check fails. The report
	// is gathered either way: the panel refuses an enrolment whose report did not pass,
	// so a request without one is a request that is certain to be turned down.
	requireDoctor bool

	stdin io.Reader
	out   io.Writer

	// report is a preflight somebody has already run. `install` gates on one before it
	// writes anything, and running the same checks twice would cost half a minute on a
	// node behind a slow tunnel and could report two different machines.
	report *wisperpb.DoctorReport

	// Seams. Nil means this machine and the real panel, which is what every caller
	// outside a test wants.
	host  *machine
	enrol func(ctx context.Context, request rpc.EnrolmentRequest) (rpc.Credential, error)
}

// join is design section 7.3, in an order chosen so that each step only costs something
// once the one before it has succeeded: derive the fingerprint, run the preflight, create
// or load the key, read the token, enrol, write the credential.
//
// The two orderings that matter are both about not spending something on an enrolment that
// was never going to work. Nothing is written until the preflight has passed, so a machine
// that cannot host workloads is left exactly as it was found; and the token file is not
// read until everything else is ready, so a bad endpoint or an unreadable key does not
// destroy a single-use credential the operator would have to go back to the panel for.
//
// Everything here can be repeated harmlessly. A node whose enrolment failed halfway is a
// node an operator runs the installer on again, not one they unpick by hand.
func (j joining) join(ctx context.Context) (rpc.Credential, error) {
	if err := j.layout.validate(); err != nil {
		return rpc.Credential{}, err
	}
	if j.panel == "" {
		return rpc.Credential{}, errors.New("no --panel. This node has nowhere to enrol; " +
			"the endpoint is on the node's page in the panel")
	}
	if _, err := rpc.ParseEndpoint(j.panel); err != nil {
		return rpc.Credential{}, err
	}

	host := j.host
	if host == nil {
		host = newMachine(PreflightOptions{
			StateDir:   j.layout.StateDir,
			Panel:      j.panel,
			DockerHost: j.dockerHost,
		})
	}
	identity := readMachineIdentity(host)
	if identity.Fingerprint == "" {
		return rpc.Credential{}, fmt.Errorf("this machine has no identity to enrol with: %s. "+
			"Without a fingerprint the panel cannot tell this node from a clone of it, so "+
			"enrolment is refused here rather than later", describeMissing(identity))
	}

	// The preflight comes before the first byte is written, and everything above it only
	// reads. That is what makes "doctor failed, so nothing was installed" a fact rather
	// than an intention (design section 7.2).
	report := j.report
	if report == nil {
		fmt.Fprintf(j.out, "Checking this machine before enrolling...\n")
		report = preflight(ctx, host)
		writeReportText(j.out, report)
	}
	if j.requireDoctor && !report.GetRequiredChecksPassed() {
		return rpc.Credential{}, errFailedPreflight
	}

	// 0700: both files in here are secrets, and the directory being readable would let a
	// local user see that a key exists and when it was written.
	if err := os.MkdirAll(j.layout.configDir(), 0o700); err != nil {
		return rpc.Credential{}, fmt.Errorf("create %s: %w", j.layout.configDir(), err)
	}

	key, err := loadOrCreateNodeKey(j.layout.keyPath())
	if err != nil {
		return rpc.Credential{}, err
	}

	// Read last, because reading destroys the file. Everything above can fail without
	// costing the operator their token.
	token, err := readBootstrapToken(j.tokenFile, j.stdin)
	if err != nil {
		return rpc.Credential{}, err
	}
	if token.WasExposed {
		fmt.Fprintf(j.out, "warning: %s was readable by users other than its owner. It has "+
			"been deleted, and the token dies at first use - but whatever wrote it will "+
			"write the next one the same way.\n", token.Origin)
	}

	fmt.Fprintf(j.out, "\nEnrolling with %s using the token from %s...\n", j.panel, token.Origin)
	credential, err := j.enrolWith(ctx, rpc.EnrolmentRequest{
		Panel:              j.panel,
		BootstrapToken:     token.Value,
		Key:                key,
		MachineFingerprint: identity.Fingerprint,
		Doctor:             report,
		Hostname:           report.GetMachine().GetHostname(),
		AdvertiseAddresses: report.GetMachine().GetAdvertiseAddresses(),
	})
	if err != nil {
		return rpc.Credential{}, explainEnrolmentFailure(j.panel, err)
	}

	if err := credential.Save(j.layout.ConfigPath); err != nil {
		return rpc.Credential{}, err
	}
	return credential, nil
}

func (j joining) enrolWith(ctx context.Context, request rpc.EnrolmentRequest) (rpc.Credential, error) {
	if j.enrol != nil {
		return j.enrol(ctx, request)
	}
	return rpc.Enrol(ctx, request)
}

// explainEnrolmentFailure separates the two failures that look identical from a terminal
// and send an operator to completely different places (design section 7.2).
//
// The panel answers a refused token with PERMISSION_DENIED, a machine it will not admit
// with FAILED_PRECONDITION, and a malformed request with INVALID_ARGUMENT - none of which
// is worth retrying. Everything else is the network, and the doctor run that has already
// happened by this point said whether the endpoint was reachable a second ago.
func explainEnrolmentFailure(panel string, err error) error {
	code := status.Code(err)
	switch code {
	case codes.PermissionDenied, codes.InvalidArgument:
		return fmt.Errorf("%s refused this enrolment: %s\n\nThe panel was reached, so this is "+
			"the token and not the network. A bootstrap token enrols one machine once and "+
			"lives fifteen minutes; issue a fresh one from the node's page and run this "+
			"again", panel, status.Convert(err).Message())

	case codes.FailedPrecondition:
		return fmt.Errorf("%s will not admit this machine: %s\n\nThe panel was reached and "+
			"understood the request. Nothing here will change that; fix what it names and "+
			"issue a fresh token", panel, status.Convert(err).Message())

	case codes.Unavailable, codes.DeadlineExceeded:
		return fmt.Errorf("%s could not be reached: %s\n\nThis is the network rather than the "+
			"token: the token has not been spent and is still good until it expires. Check "+
			"the tunnel in front of the panel and the endpoint above", panel,
			status.Convert(err).Message())

	default:
		return err
	}
}

// describeCredential is the line both commands print when it worked. The token is not in
// it, and Credential.String is what guarantees that stays true.
func describeCredential(credential rpc.Credential, configPath string) string {
	return fmt.Sprintf("Enrolled as %s. The credential is in %s, readable only by root.",
		credential.String(), configPath)
}
