package backup

import (
	"context"
	"fmt"
	"log/slog"
	"strings"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Applying the retention rule where the credentials are.
//
// backup.proto puts this at the end of a successful backup rather than in a command of its
// own, and gives the reason: "a prune that is scheduled separately is a prune somebody forgets
// to schedule". The second reason is here rather than there - deletion has to happen where the
// destination credentials are, and they only exist on this node for the length of one command.
//
// Two safety properties, both load-bearing:
//
//   - the archive just written is never a candidate, so a misconfigured rule cannot delete the
//     backup that was taken to satisfy it;
//   - a listing that failed stops the prune instead of pruning what it managed to see. Deleting
//     on a partial view of a bucket removes generations the rule would have kept, and there is
//     no undo for that.

// pruneRequest is what one prune needs to know. A struct rather than six positional strings,
// because five of them are strings and getting two of them the wrong way round would delete
// the wrong customer's backups.
type pruneRequest struct {
	Destination Destination
	// Prefix is the destination's own key prefix, and SubjectID the volume or database. The
	// two together are what a subject's history is listed under.
	Prefix    string
	SubjectID string
	// Protected is the key just written, which no rule may remove.
	Protected string
	Rule      *wisperpb.RetentionRule
}

// prune removes the generations the rule does not keep, and reports how many went.
func (r *Runner) prune(ctx context.Context, request pruneRequest) (int, error) {
	listing, err := request.Destination.List(ctx, subjectPrefix(request.Prefix, request.SubjectID)+"/")
	if err != nil {
		return 0, fmt.Errorf("backup: list the existing backups of %s: %w", request.SubjectID, err)
	}

	generations := make([]generation, 0, len(listing))
	for _, object := range listing {
		if parsed, ok := parseGeneration(object.Key, object.Size); ok {
			generations = append(generations, parsed)
		}
	}

	keep, remove := retentionPlan(generations, request.Rule, request.Protected)
	if len(remove) == 0 {
		return 0, nil
	}

	removed := 0
	var failures []string
	for _, candidate := range remove {
		if err := request.Destination.Delete(ctx, candidate.Key); err != nil {
			failures = append(failures, candidate.Key)
			r.log.Warn("could not remove a backup the retention rule aged out",
				slog.String("key", candidate.Key), slog.String("error", err.Error()))
			continue
		}
		// The sidecar goes with it. A checksum left behind for an archive that is gone would
		// be read by nothing and would show up in the next listing as an object nobody can
		// explain.
		if err := request.Destination.Delete(ctx, digestKey(candidate.Key)); err != nil {
			r.log.Warn("removed a backup but not the checksum beside it",
				slog.String("key", candidate.Key), slog.String("error", err.Error()))
		}
		removed++
	}

	r.log.Info("applied the retention rule",
		slog.String("subject", request.SubjectID),
		slog.Int("kept", len(keep)),
		slog.Int("removed", removed))

	if len(failures) > 0 {
		return removed, fmt.Errorf("backup: %d of %d aged-out backups of %s could not be removed: %s",
			len(failures), len(remove), request.SubjectID, strings.Join(failures, ", "))
	}
	return removed, nil
}
