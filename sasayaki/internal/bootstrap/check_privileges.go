package bootstrap

import (
	"context"
	"fmt"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// checkPrivileges answers whether this process can do the job at all.
//
// It runs first because it changes how every check after it should be read. A doctor run
// as an ordinary user cannot bind :80, cannot read the DMI serials the machine
// fingerprint is derived from, and usually cannot reach the Docker socket - and reporting
// three failures when the answer is one is how a check turns into a support ticket.
func checkPrivileges(_ context.Context, m *machine, _ *wisperpb.MachineFacts) []*wisperpb.DoctorCheck {
	if m.uid == 0 {
		return []*wisperpb.DoctorCheck{
			passed("privileges.root", "Running as root", severityRequired, "effective uid 0"),
		}
	}

	return []*wisperpb.DoctorCheck{check(
		"privileges.root",
		"Running as root",
		severityRequired,
		outcomeFail,
		fmt.Sprintf("running as uid %d. The daemon binds :80 and :443, talks to the Docker "+
			"socket and reads the hardware serials this machine is identified by; none of "+
			"that works unprivileged, and the checks below will report failures caused by "+
			"this one", m.uid),
		"Run this again with sudo.",
	)}
}
