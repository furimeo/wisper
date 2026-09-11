package spec

import (
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// CronEntry is a scheduled command inside a workload's container.
//
// Cron is in the spec, unlike a backup, because it is the customer's schedule and it has to
// keep firing while the panel is unreachable. A job that must run at three in the morning
// cannot have a tunnel in its path, so the node evaluates the expression itself; the
// panel's own next_run_at is a display value the node never reads.
type CronEntry struct {
	ID string
	// The container the command runs in. It is a workload in the same spec, and an entry
	// naming one that is not there is reported rather than run.
	WorkloadID string
	// Five-field cron.
	Schedule string
	// IANA zone name. Empty is UTC. A customer who asked for "3am" means their 3am, and a
	// daily job that moves twice a year is a support ticket nobody enjoys.
	Timezone string
	// argv. Never a shell string, for the same reason as Workload.Command.
	Command []string
	// Killed after this and reported as failed. A cron job with no timeout is how a node
	// ends up with two hundred copies of the same stuck script.
	Timeout time.Duration
	// False means a run that starts while the previous one is still going is skipped.
	// Overlap is opt-in because the common case - a backup script, an importer - corrupts
	// itself when two copies run at once.
	AllowOverlap bool
}

// Location resolves Timezone, falling back to UTC.
//
// An unknown zone name is UTC too, and that is a deliberate choice over refusing the entry:
// a job that runs at the wrong hour is a nuisance, and a job that never runs because a
// tzdata name was retired is a customer's backup script silently not happening. The caller
// gets `ok == false` so it can say so in the status.
func (c CronEntry) Location() (*time.Location, bool) {
	if c.Timezone == "" {
		return time.UTC, true
	}
	location, err := time.LoadLocation(c.Timezone)
	if err != nil {
		return time.UTC, false
	}
	return location, true
}

func cronFromProto(message *wisperpb.CronEntry) CronEntry {
	return CronEntry{
		ID:           message.GetId(),
		WorkloadID:   message.GetWorkloadId(),
		Schedule:     message.GetSchedule(),
		Timezone:     message.GetTimezone(),
		Command:      append([]string(nil), message.GetCommand()...),
		Timeout:      seconds(message.GetTimeoutSeconds()),
		AllowOverlap: message.GetAllowOverlap(),
	}
}
