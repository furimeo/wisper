package spec

import (
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// DatabaseStatus is measured fact about one grant, reported in every status batch.
//
// The panel owns the quota; the node owns the size. Neither engine has a hard per-database
// quota, so the node measures and the panel decides - rather than the node silently
// dropping writes on a customer who is over.
type DatabaseStatus struct {
	GrantID string
	Engine  EngineKind
	// False after a restore that has not finished, or when the engine container is down.
	// The panel shows "unavailable" rather than "deleted": not seeing something is not the
	// same as it not existing.
	Exists     bool
	SizeBytes  int64
	QuotaBytes int64
	// Computed here so the panel does not re-derive the same comparison in three screens,
	// and so the threshold can move with the engine's own accounting.
	OverQuota bool
	// "PostgreSQL 17.2", "8.4.3". Reported so the panel can warn before a dump lands on an
	// engine that cannot read it.
	EngineVersion string
	MeasuredAt    time.Time
	// Why the last provision, rotation or size query failed. Empty when it did not.
	LastError string
}

// ToProto renders the status for the wire.
func (d DatabaseStatus) ToProto() *wisperpb.DatabaseStatus {
	return &wisperpb.DatabaseStatus{
		Id:            d.GrantID,
		Engine:        engineKindToProto(d.Engine),
		Exists:        d.Exists,
		SizeBytes:     d.SizeBytes,
		QuotaBytes:    d.QuotaBytes,
		OverQuota:     d.OverQuota,
		EngineVersion: d.EngineVersion,
		MeasuredAt:    wireInstant(d.MeasuredAt),
		LastError:     d.LastError,
	}
}

// DatabaseStatusFromProto reads one back.
func DatabaseStatusFromProto(message *wisperpb.DatabaseStatus) DatabaseStatus {
	return DatabaseStatus{
		GrantID:       message.GetId(),
		Engine:        engineKindFromProto(message.GetEngine()),
		Exists:        message.GetExists(),
		SizeBytes:     message.GetSizeBytes(),
		QuotaBytes:    message.GetQuotaBytes(),
		OverQuota:     message.GetOverQuota(),
		EngineVersion: message.GetEngineVersion(),
		MeasuredAt:    instant(message.GetMeasuredAt()),
		LastError:     message.GetLastError(),
	}
}
