package spec

import (
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// CronStatus is the observed half of a CronEntry.
//
// The node is the only place this can live: the schedule is evaluated here, so a daemon
// that forgot its history on restart would report "never ran" for a job that has run every
// night for a year - and would run one twice on a machine restarted a second after it
// fired.
type CronStatus struct {
	CronID       string
	LastRunAt    time.Time
	NextRunAt    time.Time
	LastExitCode int32
	// True while a run is in flight, so the panel does not show "never ran" for a job that
	// is running right now.
	Running bool
	// Set when the last run was skipped because the previous one had not finished and
	// AllowOverlap is false. Without it the schedule simply looks broken.
	LastRunSkipped bool
	LastError      string
}

// ToProto renders the status for the wire.
func (c CronStatus) ToProto() *wisperpb.CronStatus {
	return &wisperpb.CronStatus{
		CronId:         c.CronID,
		LastRunAt:      wireInstant(c.LastRunAt),
		NextRunAt:      wireInstant(c.NextRunAt),
		LastExitCode:   c.LastExitCode,
		Running:        c.Running,
		LastRunSkipped: c.LastRunSkipped,
		LastError:      c.LastError,
	}
}

// CronStatusFromProto reads one back.
func CronStatusFromProto(message *wisperpb.CronStatus) CronStatus {
	return CronStatus{
		CronID:         message.GetCronId(),
		LastRunAt:      instant(message.GetLastRunAt()),
		NextRunAt:      instant(message.GetNextRunAt()),
		LastExitCode:   message.GetLastExitCode(),
		Running:        message.GetRunning(),
		LastRunSkipped: message.GetLastRunSkipped(),
		LastError:      message.GetLastError(),
	}
}
