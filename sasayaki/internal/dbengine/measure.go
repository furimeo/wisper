package dbengine

import (
	"context"
	"errors"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
	"github.com/furimeo/wisper/sasayaki/internal/state"
)

// How big each customer's database is, which is the only quota this platform can enforce.
//
// Neither engine has a hard per-database ceiling. There is no setting that makes PostgreSQL
// refuse a write because one database has passed a number, and MySQL's is per-tablespace and
// not per-schema. So the node measures and the panel decides: `over_quota` travels in every
// status batch and the panel suspends, warns or bills, rather than the node silently dropping
// a customer's writes at a threshold nobody told them about.
//
// # Why nothing here fails loudly
//
// This is called from the reconcile loop's status pass, every fifteen seconds, and the batch
// it feeds is one snapshot of the whole node. A server that is down produces `exists: false`
// with a reason, never an absent status and never a zero size - the panel writes
// `managed_database.used_bytes` from what it receives, so a zero would show every customer on
// a restarting server as using nothing, and an absent row would leave the last real figure in
// place with no indication that it is stale.

// Statuses is the measurement that travels in every status batch, one per grant in the spec.
//
// It implements reconcile.Databases. It converges nothing: creating a server here would make a
// fifteen-second status pass do minutes of work, and the loop that calls it is the one systemd
// watches. Convergence is Run and Converge.
func (e *Engines) Statuses(ctx context.Context) ([]spec.DatabaseStatus, error) {
	at := e.now()

	desired, wanted, servers, err := e.survey(ctx, GlanceBudget)
	if err != nil {
		if errors.Is(err, state.ErrNoSpec) {
			// A freshly enrolled node. It holds no databases and has nothing to say about
			// any, which is different from having nothing to say.
			return nil, nil
		}
		return nil, err
	}

	placed := place(desired, wanted, servers)
	statuses := make([]spec.DatabaseStatus, 0, len(desired.Grants)+len(desired.Engines))
	for _, grant := range desired.Grants {
		statuses = append(statuses, e.measure(grant, placed, servers, at))
	}
	if len(desired.Grants) == 0 {
		for _, engine := range desired.Engines {
			seen := servers[engine.DataVolumeID]
			statuses = append(statuses, spec.DatabaseStatus{
				GrantID:       engine.DataVolumeID,
				Engine:        engine.Kind,
				Exists:        seen.Ready,
				EngineVersion: seen.Version,
				MeasuredAt:    at,
				LastError:     seen.Detail,
			})
		}
	}
	return statuses, nil
}

// measure is one grant's status.
func (e *Engines) measure(
	grant spec.Grant,
	placed homes,
	servers map[string]observed,
	at time.Time,
) spec.DatabaseStatus {
	status := spec.DatabaseStatus{
		GrantID:    grant.ID,
		Engine:     grant.Engine,
		QuotaBytes: grant.QuotaBytes,
		MeasuredAt: at,
		LastError:  e.failure(grant.ID),
	}

	instanceID, refusal := placed.homeOf(grant.ID)
	if refusal != "" {
		status.LastError = refusal
		return status
	}

	seen := servers[instanceID]
	status.EngineVersion = seen.Version
	if !seen.Ready {
		// exists stays false and the size stays zero, which together mean "unavailable". The
		// panel is written to show that rather than "deleted": not seeing something is not the
		// same as it not existing (AGENTS.md section 4.5).
		if status.LastError == "" {
			status.LastError = seen.Detail
		}
		return status
	}

	fact, present := seen.database(grant.DatabaseName)
	if !present {
		if status.LastError == "" {
			status.LastError = "this database is not on the server the panel placed it on"
		}
		return status
	}
	if collision := collides(grant, fact); collision != "" {
		// A database with this name exists and is not this grant's. Reporting its size would
		// bill one customer for another's data, and reporting it as present would tell the
		// panel a database it has never provisioned is ready.
		status.LastError = collision
		return status
	}

	status.Exists = true
	status.SizeBytes = fact.SizeBytes
	status.OverQuota = grant.QuotaBytes > 0 && fact.SizeBytes >= grant.QuotaBytes
	return status
}
