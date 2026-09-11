package dbengine

import (
	"context"
	"fmt"
	"log/slog"
	"strings"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
)

// The half of convergence that happens inside the servers rather than around them.
//
// Two jobs, both of which run on every pass because both are idempotent and neither can be
// remembered safely anywhere else:
//
//  1. Harden a server that has just come up. Once per container, because the statements are
//     idempotent but running them four times a minute forever is four round trips a minute
//     forever.
//  2. Recreate any grant in the spec that is not on its server. That is what a node that lost
//     its disk looks like from the inside: the panel is still showing a customer a connection
//     string, and the account behind it does not exist. The database and the login come back;
//     the password does not, because the node does not have it and must not invent one, so the
//     login is recreated locked and last_error tells the panel to send a rotation.
//
// # What settle deliberately does not do
//
// It never removes a database or a login. `docs/contracts/node-spec.md` says omission is
// deletion, and for a container that is right - the image can be pulled again. For a database
// it is not: the wire has no way for the node to tell a login it created from one an operator
// made, so "everything not in the spec" would be a rule whose blast radius on a truncated or
// mis-built spec is every account on the machine. The panel sends `DropDatabase` explicitly,
// with `drop_data` on it, and drop.go is where a database is removed.

// settle hardens each ready server and rebuilds the grants that are missing from it.
func (e *Engines) settle(ctx context.Context, desired spec.Spec, wanted []instance) error {
	if len(wanted) == 0 {
		e.forgetFailures(nil)
		return nil
	}

	servers, err := e.look(ctx, wanted, GlanceBudget)
	if err != nil {
		return err
	}

	problems := make([]string, 0, 2)
	for _, built := range wanted {
		if err := e.hardenOnce(ctx, servers[built.ID]); err != nil {
			problems = append(problems, err.Error())
		}
	}

	placed := place(desired, wanted, servers)
	known := make(map[string]bool, len(desired.Grants))
	for _, grant := range desired.Grants {
		known[grant.ID] = true
		if err := e.rebuild(ctx, grant, placed, servers); err != nil {
			problems = append(problems, err.Error())
		}
	}
	e.forgetFailures(known)

	if len(problems) > 0 {
		return fmt.Errorf("dbengine: %s", strings.Join(problems, "; "))
	}
	return nil
}

// hardenOnce applies the server's one-time lockdown, if this container has not had it.
func (e *Engines) hardenOnce(ctx context.Context, seen observed) error {
	if !seen.Ready {
		// Not a problem to report: the server being down is already reported through every
		// grant on it, and saying it twice would double every message the panel shows.
		return nil
	}
	if !e.needsHardening(seen.Instance.ID, seen.ContainerID) {
		return nil
	}

	if _, err := e.runner(seen.ContainerID)(ctx, seen.Instance.Talk.harden(seen.Instance.Spec)); err != nil {
		e.hardeningFailed(seen.Instance.ID)
		return fmt.Errorf("harden the %s server %s: %w", seen.Instance.Kind, seen.Instance.ID, err)
	}
	e.log.Info("hardened a database server",
		slog.String("instance", seen.Instance.ID),
		slog.String("kind", string(seen.Instance.Kind)))
	return nil
}

// rebuild makes sure one grant exists on the server it belongs to.
func (e *Engines) rebuild(ctx context.Context, grant spec.Grant, placed homes, servers map[string]observed) error {
	instanceID, refusal := placed.homeOf(grant.ID)
	if refusal != "" {
		e.recordFailure(grant.ID, refusal)
		return nil
	}

	seen := servers[instanceID]
	if !seen.Ready {
		// The server is down or still starting. Nothing is concluded about what it holds, and
		// nothing is created: a grant reported as missing on a server nobody could ask is the
		// mistake that recreates a customer's account over the top of their data.
		e.recordFailure(grant.ID, seen.Detail)
		return nil
	}

	existing, present := seen.database(grant.DatabaseName)
	if present {
		if collision := collides(grant, existing); collision != "" {
			e.recordFailure(grant.ID, collision)
			return nil
		}
		e.clearFailure(grant.ID)
		return nil
	}

	statement, err := seen.Instance.Talk.adopt(seen.Instance.Spec, grant)
	if err != nil {
		e.recordFailure(grant.ID, err.Error())
		return nil
	}
	if _, err := e.runner(seen.ContainerID)(ctx, statement); err != nil {
		e.recordFailure(grant.ID, err.Error())
		return fmt.Errorf("recreate the database %s: %w", grant.DatabaseName, err)
	}

	detail := "this database was missing from the server and has been recreated; its login is " +
		"locked until the panel sends a new password"
	e.recordFailure(grant.ID, detail)
	e.log.Warn("recreated a database that was missing from its server",
		slog.String("grant", grant.ID),
		slog.String("database", grant.DatabaseName),
		slog.String("instance", instanceID))
	return nil
}

// collides reports why an existing database is not the one this grant describes, or "".
//
// The owner is the whole of the check. On PostgreSQL it is the role that owns the database; on
// MySQL it is the single login holding privileges on the schema. Either way, a database with
// this name owned by somebody else is a name collision, and the honest answer is to refuse -
// handing a customer a login to a database that is already somebody's is the worst outcome
// available.
func collides(grant spec.Grant, existing databaseFact) string {
	if existing.Owner == "" {
		return fmt.Sprintf("a database called %s already exists on this server and no login owns "+
			"it, so it was not created by this platform; it has been left alone", grant.DatabaseName)
	}
	if existing.Owner != grant.Username {
		return fmt.Sprintf("a database called %s already exists on this server and belongs to a "+
			"different login, so it has been left alone", grant.DatabaseName)
	}
	return ""
}
