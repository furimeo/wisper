package bootstrap

import (
	"context"

	"google.golang.org/protobuf/types/known/timestamppb"

	"github.com/furimeo/wisper/sasayaki/internal/version"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Shorthands for the proto enums. The generated names are correct and unreadable, and a
// file of checks that spells DOCTOR_OUTCOME_PASS out forty times hides the checks.
const (
	severityRequired = wisperpb.DoctorSeverity_DOCTOR_SEVERITY_REQUIRED
	severityAdvisory = wisperpb.DoctorSeverity_DOCTOR_SEVERITY_ADVISORY

	outcomePass = wisperpb.DoctorOutcome_DOCTOR_OUTCOME_PASS
	outcomeWarn = wisperpb.DoctorOutcome_DOCTOR_OUTCOME_WARN
	outcomeFail = wisperpb.DoctorOutcome_DOCTOR_OUTCOME_FAIL
)

// PreflightOptions is what a preflight run needs to know that it cannot work out.
type PreflightOptions struct {
	// StateDir is the directory whose filesystem decides whether disk quotas can be
	// enforced. Defaults to /var/lib/wisper.
	StateDir string

	// Panel enables the two checks that need somewhere to reach: whether the endpoint
	// answers, and how far this machine's clock is from the panel's. Empty leaves both
	// out of the report entirely rather than recording an unspecified outcome - a check
	// that was not run is not a check that passed.
	Panel string

	// DockerHost overrides the socket. Empty means the environment's DOCKER_HOST, then
	// the platform default.
	DockerHost string

	// EdgeRunning indicates whether the embedded edge proxy (Caddy) is already running and
	// listening on ports 80 and 443 in this daemon process. When true, port checks treat
	// :80 and :443 as bound and served by sasayaki edge rather than attempting to bind them.
	EdgeRunning bool
}

// Preflight inspects this machine and changes nothing on it.
//
// Nothing here writes, creates, binds for longer than a syscall, or installs anything.
// That is not a stylistic preference: the installer runs this before it touches the
// machine, and "doctor failed, so nothing was installed" has to be literally true
// (design section 7.2). It is also called on every reconnect, because RAM gets added and
// runsc gets uninstalled, and the panel placing workloads against week-old facts places
// them onto a machine that cannot hold them.
//
// It never returns an error. A machine that cannot be inspected is the most important
// thing the report has to say, and an error would throw the rest of the findings away
// with it.
func Preflight(ctx context.Context, options PreflightOptions) *wisperpb.DoctorReport {
	return preflight(ctx, newMachine(options))
}

func preflight(ctx context.Context, m *machine) *wisperpb.DoctorReport {
	facts := baseFacts(ctx, m)

	// The order is the order a person reads them in: what this process is allowed to do,
	// then the kernel under it, then the engine, then the disk, then the network, then
	// the capacity that decides what the panel may place here.
	gatherers := []func(context.Context, *machine, *wisperpb.MachineFacts) []*wisperpb.DoctorCheck{
		checkPrivileges,
		checkKernel,
		checkDocker,
		checkIsolation,
		checkStorage,
		checkPorts,
		checkOutbound,
		checkPanelReachable,
		checkClock,
		checkCapacity,
		checkIdentity,
	}

	checks := make([]*wisperpb.DoctorCheck, 0, 16)
	for _, gather := range gatherers {
		checks = append(checks, gather(ctx, m, facts)...)
	}

	return &wisperpb.DoctorReport{
		Checks:               checks,
		Machine:              facts,
		TakenAt:              timestamppb.New(m.now().UTC()),
		RequiredChecksPassed: requiredChecksPassed(checks),
		AgentVersion:         version.Number,
	}
}

// requiredChecksPassed is the one boolean an installer branches on.
//
// A warning never blocks, whatever its severity: a required check that produced a warning
// is one this node could not prove either way, and refusing to install because a clock
// could not be measured would make the check worse than useless. Only an outright failure
// of a required check stops an installation.
func requiredChecksPassed(checks []*wisperpb.DoctorCheck) bool {
	for _, check := range checks {
		if check.GetSeverity() == severityRequired && check.GetOutcome() == outcomeFail {
			return false
		}
	}
	return true
}

// failures and warnings are what the summary line counts and what the exit code is
// decided from.
func countOutcomes(report *wisperpb.DoctorReport) (failures, warnings int) {
	for _, check := range report.GetChecks() {
		switch check.GetOutcome() {
		case outcomeFail:
			failures++
		case outcomeWarn:
			warnings++
		}
	}
	return failures, warnings
}

// check builds one line of the report. Every check goes through it, so a check with a
// failing outcome and no remedy is impossible to write by accident - the argument is
// there and has to be filled in.
func check(id, title string, severity wisperpb.DoctorSeverity,
	outcome wisperpb.DoctorOutcome, detail, remedy string) *wisperpb.DoctorCheck {
	return &wisperpb.DoctorCheck{
		Id:       id,
		Title:    title,
		Severity: severity,
		Outcome:  outcome,
		Detail:   detail,
		Remedy:   remedy,
	}
}

// passed is the shape of a check with nothing to say beyond what it found. It takes no
// remedy because there is nothing to remedy.
func passed(id, title string, severity wisperpb.DoctorSeverity, detail string) *wisperpb.DoctorCheck {
	return check(id, title, severity, outcomePass, detail, "")
}
