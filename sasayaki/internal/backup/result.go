package backup

import (
	"strings"
	"time"

	"google.golang.org/protobuf/types/known/timestamppb"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// What the panel is told when a backup or a restore ends.
//
// One message for both outcomes in each direction, because the panel has one place to put it
// and a failure arriving in a different shape would be a second code path on the receiving
// side. The difference is in the fields, and one of them decides everything downstream:
// restore_point_id is empty on a failure however far the backup got, which is precisely what
// stops the panel from offering a restore to an archive that is not there.

// backupReport accumulates what a run found out as it went, so that a failure at VERIFY still
// reports the size, the digest and the pause the customer already paid for.
type backupReport struct {
	backupID       string
	restorePointID string
	location       string
	size           int64
	sha256         string
	quiesce        time.Duration
	startedAt      time.Time
	verified       bool
	pruned         int
}

func (b backupReport) succeeded(at time.Time, detail string) *wisperpb.BackupCompleted {
	return &wisperpb.BackupCompleted{
		BackupId:       b.backupID,
		Success:        true,
		RestorePointId: b.restorePointID,
		Location:       b.location,
		SizeBytes:      b.size,
		Sha256:         b.sha256,
		QuiesceMillis:  b.quiesce.Milliseconds(),
		StartedAt:      timestamppb.New(b.startedAt),
		FinishedAt:     timestamppb.New(at),
		Verified:       b.verified,
		Pruned:         int32(b.pruned),
		FailedStage:    wisperpb.BackupStage_BACKUP_STAGE_PRUNE,
		Detail:         detail,
	}
}

// failed is the message for a backup that did not finish.
//
// The quiesce is still reported. An application that was paused for six seconds before the
// snapshot failed was still paused for six seconds, and hiding that because the outcome was a
// failure would make the one number a customer feels the one number they cannot see.
func (b backupReport) failed(at time.Time, stage wisperpb.BackupStage, detail string) *wisperpb.BackupCompleted {
	return &wisperpb.BackupCompleted{
		BackupId:      b.backupID,
		Success:       false,
		SizeBytes:     b.size,
		Sha256:        b.sha256,
		QuiesceMillis: b.quiesce.Milliseconds(),
		StartedAt:     timestamppb.New(b.startedAt),
		FinishedAt:    timestamppb.New(at),
		FailedStage:   stage,
		Detail:        trimDetail(detail, "the backup failed and said nothing about why"),
	}
}

// restoreReport is the same idea for the other direction.
type restoreReport struct {
	restoreID  string
	bytes      int64
	restoredTo string
	restarted  bool
	startedAt  time.Time
}

func (r restoreReport) succeeded(at time.Time, detail string) *wisperpb.RestoreCompleted {
	return &wisperpb.RestoreCompleted{
		RestoreId:         r.restoreID,
		Success:           true,
		BytesRestored:     r.bytes,
		RestoredTo:        r.restoredTo,
		WorkloadRestarted: r.restarted,
		StartedAt:         timestamppb.New(r.startedAt),
		FinishedAt:        timestamppb.New(at),
		Detail:            detail,
	}
}

// failed reports a restore that did not happen, and says where the data still is.
//
// restored_to is filled in on a failure too, because the question a customer asks when a
// restore fails is not "why" but "is my application still serving what it was" - and the
// answer is only believable if the message says which directory it is talking about.
func (r restoreReport) failed(at time.Time, detail string) *wisperpb.RestoreCompleted {
	return &wisperpb.RestoreCompleted{
		RestoreId:         r.restoreID,
		Success:           false,
		BytesRestored:     r.bytes,
		RestoredTo:        r.restoredTo,
		WorkloadRestarted: r.restarted,
		StartedAt:         timestamppb.New(r.startedAt),
		FinishedAt:        timestamppb.New(at),
		Detail:            trimDetail(detail, "the restore failed and said nothing about why"),
	}
}

// trimDetail keeps the message short enough to sit in a notification. The whole story is in
// the daemon's log; this field is the line shown next to the run in a list, and an unbounded
// one there is a table that scrolls sideways for a screen and a half.
func trimDetail(detail, fallback string) string {
	cleaned := strings.TrimSpace(detail)
	if cleaned == "" {
		return fallback
	}
	const limit = 500
	if len(cleaned) <= limit {
		return cleaned
	}
	return cleaned[:limit] + "..."
}
