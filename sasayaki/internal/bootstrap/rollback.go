package bootstrap

import (
	"context"
	"fmt"
	"os"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// rollBack puts the previous binary back and brings the service up on it.
//
// Two callers, one behaviour. An operator's `sasayaki upgrade` calls it directly when the
// new binary does not come back, and systemd calls it through sasayaki-rollback.service
// when the daemon upgraded itself and then died - the case where nobody was left alive to
// notice (design section 7.5).
//
// The previous binary is moved rather than copied, so a rollback consumes it. That is
// deliberate on both counts: after this the live binary *is* the previous one, keeping a
// copy of the build that just failed helps nobody, and the OnFailure unit's
// ConditionPathExists then stops a second, unrelated failure from doing any of this again.
func (s upgradeSteps) rollBack(ctx context.Context, marker upgradeMarker,
	reason string) (*wisperpb.UpgradeResult, error) {

	previous := marker.PreviousBinary
	if previous == "" {
		previous = s.layout.previousBinaryPath()
	}

	result := &wisperpb.UpgradeResult{
		PreviousVersion: marker.PreviousVersion,
		NewVersion:      marker.NewVersion,
	}

	if !exists(previous) {
		clearMarkerQuietly(s.layout.ConfigPath)
		result.Detail = fmt.Sprintf("The upgrade to %s failed (%s) and there is no previous "+
			"binary at %s to go back to. This node needs a working binary installed by hand.",
			orUnknown(marker.NewVersion), reason, previous)
		return result, fmt.Errorf("%s", result.Detail)
	}

	if err := os.Rename(previous, s.layout.BinaryPath); err != nil {
		result.Detail = fmt.Sprintf("The upgrade to %s failed (%s) and %s could not be put "+
			"back: %v", orUnknown(marker.NewVersion), reason, previous, err)
		return result, fmt.Errorf("%s", result.Detail)
	}
	result.RolledBack = true
	fmt.Fprintf(s.out, "Put %s back as %s\n", previous, s.layout.BinaryPath)

	// The decision has been made and acted on. Clearing the marker now means a failure of
	// the restart below is reported as itself rather than rolled back a second time.
	clearMarkerQuietly(s.layout.ConfigPath)

	if !s.serviceRuns(ctx) {
		result.Detail = fmt.Sprintf("The upgrade to %s failed (%s). %s is back in place; "+
			"there is no service running here to restart.", orUnknown(marker.NewVersion),
			reason, s.layout.BinaryPath)
		return result, nil
	}

	// The start limit is what fired the rollback. Until it is cleared, systemd refuses to
	// start the unit however good the binary is.
	s.systemd.resetFailed(ctx, unitName)

	if err := s.systemd.restart(ctx, unitName); err != nil {
		result.Detail = fmt.Sprintf("The upgrade to %s failed (%s) and %s is back in place, "+
			"but %s would not start: %v", orUnknown(marker.NewVersion), reason,
			s.layout.BinaryPath, unitName, err)
		return result, fmt.Errorf("%s", result.Detail)
	}
	if err := s.watch.healthy(ctx); err != nil {
		result.Detail = fmt.Sprintf("The upgrade to %s failed (%s) and %s is back in place, "+
			"but %s is still not healthy: %v", orUnknown(marker.NewVersion), reason,
			s.layout.BinaryPath, unitName, err)
		return result, fmt.Errorf("%s", result.Detail)
	}

	result.Detail = fmt.Sprintf("The upgrade to %s failed (%s). This node is back on %s and "+
		"%s is up.", orUnknown(marker.NewVersion), reason, orUnknown(marker.PreviousVersion),
		unitName)
	fmt.Fprintln(s.out, result.Detail)
	return result, nil
}

// rollBackInFlight is what `sasayaki upgrade --rollback` does, and therefore what the
// systemd rollback unit does.
//
// It refuses to act unless an upgrade is genuinely in flight. That refusal is the whole
// reason the marker exists: sasayaki.service names this unit in OnFailure=, so it runs for
// *any* failure that trips the start limit - a Docker socket that vanished, a disk that
// filled - and silently downgrading a node's agent because of one of those would be a
// change nobody asked for, made at the worst possible moment.
func (s upgradeSteps) rollBackInFlight(ctx context.Context) (*wisperpb.UpgradeResult, error) {
	marker, found, err := readUpgradeMarker(s.layout.ConfigPath)
	if err != nil {
		return nil, err
	}
	if !found {
		fmt.Fprintf(s.out, "No upgrade is in flight, so there is nothing to roll back. "+
			"Whatever stopped %s is not an upgrade.\n", unitName)
		return &wisperpb.UpgradeResult{
			Detail: "No upgrade was in flight; nothing was rolled back.",
		}, nil
	}
	if marker.stale(s.now()) {
		clearMarkerQuietly(s.layout.ConfigPath)
		fmt.Fprintf(s.out, "The upgrade to %s was recorded at %s, more than %s ago, so it is "+
			"not what is wrong now. Nothing was rolled back.\n", orUnknown(marker.NewVersion),
			marker.StartedAt.Format("2006-01-02 15:04:05 MST"), upgradeWindow)
		return &wisperpb.UpgradeResult{
			PreviousVersion: marker.PreviousVersion,
			NewVersion:      marker.NewVersion,
			Detail:          "The recorded upgrade is too old to be the cause; nothing was rolled back.",
		}, nil
	}

	return s.rollBack(ctx, marker, "the service did not stay up")
}
