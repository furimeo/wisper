package bootstrap

import (
	"context"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// checkPanelReachable asks whether this machine can get to the panel.
//
// Omitted entirely when no --panel was given, rather than recorded with an unspecified
// outcome. A check that did not run is not a check that passed, and a report full of
// "unknown" rows is a report nobody reads.
//
// The value of the check is the separation it buys. There are two ways an installation
// fails at this point and they look identical from a terminal - the machine cannot reach
// the panel, or it can and the token is refused - and they send an administrator to
// different places. This one answers the first question with no token involved, so when
// enrolment then fails, the failure is unambiguous (design section 7.2).
func checkPanelReachable(ctx context.Context, m *machine, _ *wisperpb.MachineFacts) []*wisperpb.DoctorCheck {
	if m.panel == "" {
		return nil
	}

	probe := m.panelReading(ctx)
	if probe.Reachable {
		return []*wisperpb.DoctorCheck{passed("network.panel", "Panel reachable",
			severityRequired, probe.describe())}
	}
	if probe.timedOut() {
		return []*wisperpb.DoctorCheck{check("network.panel", "Panel reachable",
			severityRequired, outcomeWarn,
			probe.describe()+" (the check was cut short before it finished)",
			"Run doctor again and let it finish.")}
	}

	return []*wisperpb.DoctorCheck{check("network.panel", "Panel reachable", severityRequired,
		outcomeFail, probe.describe(), probe.remedy())}
}
