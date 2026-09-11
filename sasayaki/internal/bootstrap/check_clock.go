package bootstrap

import (
	"context"
	"fmt"
	"os"
	"strings"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// How far out the clock may be.
//
// The HTTP Date header has one-second granularity and a tunnel adds a few hundred
// milliseconds, so anything under a couple of seconds is measurement noise rather than
// skew. Above the failure mark, TLS certificates start being rejected as not-yet-valid
// and ACME nonces as replayed - failures whose symptoms point at DNS, at the certificate
// authority, at anything except the clock (design section 7.2).
const (
	clockNoiseFloor  = 2 * time.Second
	clockFailureMark = 30 * time.Second
)

// checkClock decides whether this machine's idea of the time can be trusted.
//
// Two sources, in order of authority. The panel's own clock, when doctor was given a
// --panel, because agreeing with the panel is the only agreement that matters here: the
// node's TLS session and its certificate lifetimes are both negotiated against it.
// Failing that, whether a time synchronisation daemon on this machine says it has
// synchronised - which is weaker, since it proves the daemon is happy rather than that
// the clock is right, and is reported as the weaker statement it is.
func checkClock(ctx context.Context, m *machine, facts *wisperpb.MachineFacts) []*wisperpb.DoctorCheck {
	if measured := measureAgainstPanel(ctx, m, facts); measured != nil {
		return []*wisperpb.DoctorCheck{measured}
	}
	return []*wisperpb.DoctorCheck{synchronisationDaemonCheck(ctx, m, facts)}
}

// measureAgainstPanel returns nil when there was no panel to measure against.
func measureAgainstPanel(ctx context.Context, m *machine,
	facts *wisperpb.MachineFacts) *wisperpb.DoctorCheck {
	probe := m.panelReading(ctx)
	if !probe.Reachable || probe.ServerTime.IsZero() || probe.LocalTime.IsZero() {
		return nil
	}

	offset := probe.LocalTime.Sub(probe.ServerTime)
	facts.ClockOffsetMillis = offset.Milliseconds()

	magnitude := offset
	if magnitude < 0 {
		magnitude = -magnitude
	}
	direction := "ahead of"
	if offset < 0 {
		direction = "behind"
	}
	measurement := fmt.Sprintf("this machine is %s %s the panel",
		magnitude.Round(time.Millisecond), direction)

	switch {
	case magnitude <= clockNoiseFloor:
		facts.ClockSynchronised = true
		return passed("clock.synchronised", "Clock synchronised", severityRequired,
			measurement+", measured against its HTTP Date header")

	case magnitude <= clockFailureMark:
		return check("clock.synchronised", "Clock synchronised", severityRequired, outcomeWarn,
			measurement+", which is more than the "+clockNoiseFloor.String()+
				" that measurement noise accounts for",
			"Enable a time synchronisation daemon (`timedatectl set-ntp true`). Skew this "+
				"size does not break anything yet and grows.")

	default:
		return check("clock.synchronised", "Clock synchronised", severityRequired, outcomeFail,
			measurement+". TLS handshakes and ACME orders fail at this offset, with "+
				"symptoms that point at everything except the clock",
			"Enable a time synchronisation daemon (`timedatectl set-ntp true`) and wait "+
				"for it to converge before enrolling this node.")
	}
}

// synchronisationDaemonCheck asks the machine whether it believes its own clock.
//
// systemd-timesyncd creates /run/systemd/timesync/synchronized when it has agreed with a
// server, and timedatectl reports the same thing for whichever daemon is in charge. Both
// are consulted because a node running chrony or ntpd has the second and not the first.
func synchronisationDaemonCheck(ctx context.Context, m *machine,
	facts *wisperpb.MachineFacts) *wisperpb.DoctorCheck {
	if _, err := os.Stat(m.runPath("systemd", "timesync", "synchronized")); err == nil {
		facts.ClockSynchronised = true
		return passed("clock.synchronised", "Clock synchronised", severityRequired,
			"systemd-timesyncd has synchronised (no --panel given, so the offset was not "+
				"measured)")
	}

	output, err := m.runCommand(ctx, "timedatectl", "show", "--property=NTPSynchronized",
		"--value")
	if err != nil {
		return check("clock.synchronised", "Clock synchronised", severityRequired, outcomeWarn,
			fmt.Sprintf("no time synchronisation daemon could be questioned (%v), so it is "+
				"not known whether this clock is right", err),
			"Install and enable one (`timedatectl set-ntp true`), or run doctor with "+
				"--panel so the offset can be measured against the panel directly.")
	}

	switch strings.TrimSpace(string(output)) {
	case "yes":
		facts.ClockSynchronised = true
		return passed("clock.synchronised", "Clock synchronised", severityRequired,
			"timedatectl reports NTPSynchronized=yes (no --panel given, so the offset was "+
				"not measured)")
	default:
		return check("clock.synchronised", "Clock synchronised", severityRequired, outcomeWarn,
			"timedatectl reports NTPSynchronized=no: this machine's clock is free-running",
			"Run `timedatectl set-ntp true` and wait for it to converge. Skew breaks TLS "+
				"and ACME in ways that look like anything but a clock problem.")
	}
}
