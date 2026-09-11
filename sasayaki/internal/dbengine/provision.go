package dbengine

import (
	"context"
	"fmt"
	"log/slog"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Creating one customer's database, while they watch.
//
// A command rather than a piece of reconciliation, and the reason is in database.proto: the
// customer is on a screen waiting for a connection string, and the password has to reach the
// panel in the reply to this one request. Reconciliation would get there eventually and
// "eventually" is not an answer to somebody who just pressed a button.
//
// Three things make this safe to send twice, which the panel will do whenever a stream drops
// between the work finishing and the answer arriving:
//
//   - the server is converged first, so a node whose PostgreSQL has not been created yet
//     creates it rather than refusing;
//   - the SQL is idempotent in both dialects - a role that exists is altered, a database that
//     exists is not created again;
//   - a database that exists and belongs to somebody else is refused rather than taken over,
//     which is the one case where doing the work twice would be doing it to the wrong person.

// ProvisionDatabase creates the database, the login and the grant.
//
// The reply carries what a connection string needs and nothing more. The password came from
// the panel and is not sent back: putting a secret on the wire twice buys nothing.
func (e *Engines) ProvisionDatabase(
	ctx context.Context,
	request *wisperpb.ProvisionDatabase,
) (*wisperpb.DatabaseProvisioned, error) {
	grant := grantFrom(request.GetGrant())
	password := request.GetPassword()

	if grant.ID == "" {
		return nil, fmt.Errorf("dbengine: the panel asked for a database with no id, and there " +
			"would be nothing to report its size against")
	}
	if err := checkGrant(grant); err != nil {
		e.recordFailure(grant.ID, err.Error())
		return nil, err
	}
	if err := checkPassword(password); err != nil {
		e.recordFailure(grant.ID, err.Error())
		return nil, err
	}

	// Held for the whole operation, so a reconcile pass cannot replace the container out from
	// under the statement and a second provision of the same grant cannot interleave with this
	// one.
	e.converging.Lock()
	defer e.converging.Unlock()

	// Best effort: a node whose spec has only just arrived may not have created the server
	// yet, and this is the moment a customer notices. A failure here is not fatal on its own -
	// the server may already be running and the failure be about the other engine entirely -
	// so it is logged and the survey below decides.
	if err := e.converge(ctx); err != nil {
		e.log.Warn("converging the database servers before provisioning did not fully succeed",
			slog.String("grant", grant.ID), slog.String("error", err.Error()))
	}

	seen, err := e.serverFor(ctx, grant)
	if err != nil {
		e.recordFailure(grant.ID, err.Error())
		return nil, err
	}

	if existing, present := seen.database(grant.DatabaseName); present {
		if collision := collides(grant, existing); collision != "" {
			e.recordFailure(grant.ID, collision)
			return nil, fmt.Errorf("dbengine: %s", collision)
		}
	}

	statement, err := seen.Instance.Talk.provision(seen.Instance.Spec, grant, password)
	if err != nil {
		e.recordFailure(grant.ID, err.Error())
		return nil, err
	}
	if _, err := e.runner(seen.ContainerID)(ctx, statement); err != nil {
		e.recordFailure(grant.ID, err.Error())
		return nil, err
	}

	e.clearFailure(grant.ID)
	e.log.Info("provisioned a database",
		slog.String("grant", grant.ID),
		slog.String("database", grant.DatabaseName),
		slog.String("instance", seen.Instance.ID),
		slog.String("kind", string(grant.Engine)))

	return &wisperpb.DatabaseProvisioned{
		Id:     grant.ID,
		Engine: request.GetGrant().GetEngine(),
		// The name the server answers to on a tenant network, which is where a customer's
		// container reaches it from. Not the node's own address: that one is behind the
		// egress filter every tenant bridge carries.
		Host:          seen.Instance.Name,
		Port:          seen.Instance.Plan.Port,
		DatabaseName:  grant.DatabaseName,
		Username:      grant.Username,
		EngineVersion: seen.Version,
	}, nil
}

// serverFor finds the ready server one grant belongs on.
//
// Every server is glanced at, and then the one this grant actually needs is waited for with
// the full startup budget. Waiting that long on all of them would mean a node whose MySQL is
// broken took three minutes to create a PostgreSQL database; waiting only a glance on the one
// that matters would mean the very first database on a freshly built node always failed,
// because a cold server is a minute away from answering and the customer is watching.
func (e *Engines) serverFor(ctx context.Context, grant spec.Grant) (observed, error) {
	desired, wanted, servers, err := e.survey(ctx, GlanceBudget)
	if err != nil {
		return observed{}, err
	}

	// The grant may not be in the spec yet - the panel sends ProvisionDatabase as soon as the
	// row exists and the generation carrying it may still be in flight - so it is added to the
	// document placement is computed from rather than looked up in it.
	desired.Grants = withGrant(desired.Grants, grant)

	instanceID, refusal := place(desired, wanted, servers).homeOf(grant.ID)
	if refusal != "" {
		return observed{}, fmt.Errorf("dbengine: %s", refusal)
	}

	seen := servers[instanceID]
	if seen.Ready {
		return seen, nil
	}
	if seen.ContainerID == "" {
		return observed{}, fmt.Errorf("dbengine: the %s server for %s is not on this node yet: %s",
			grant.Engine, grant.DatabaseName, seen.Detail)
	}

	if err := e.waitReady(ctx, seen.Instance, seen.ContainerID, StartupBudget); err != nil {
		return observed{}, err
	}
	existing, err := e.containers(ctx)
	if err != nil {
		return observed{}, err
	}
	seen = e.inspect(ctx, seen.Instance, existing[instanceID], GlanceBudget)
	if !seen.Ready {
		return observed{}, fmt.Errorf("dbengine: the %s server holding %s is not ready: %s",
			grant.Engine, grant.DatabaseName, seen.Detail)
	}
	return seen, nil
}

// withGrant replaces or appends one grant in a spec's list, keeping the rest in order.
func withGrant(grants []spec.Grant, grant spec.Grant) []spec.Grant {
	merged := make([]spec.Grant, 0, len(grants)+1)
	replaced := false
	for _, existing := range grants {
		if existing.ID == grant.ID {
			merged = append(merged, grant)
			replaced = true
			continue
		}
		merged = append(merged, existing)
	}
	if !replaced {
		merged = append(merged, grant)
	}
	return merged
}

// grantFrom reads the grant out of a command.
//
// The spec package's own conversion is not exported for a single grant - it converts a whole
// NodeSpec - so the five fields are read here. The engine kind goes through the same mapping
// the spec uses, by round-tripping a one-grant NodeSpec, so an enum this binary does not know
// becomes spec.EngineUnknown in exactly one place rather than two.
func grantFrom(message *wisperpb.DatabaseGrant) spec.Grant {
	if message == nil {
		return spec.Grant{}
	}
	converted := spec.FromProto(&wisperpb.NodeSpec{Databases: []*wisperpb.DatabaseGrant{message}})
	if len(converted.Grants) == 0 {
		return spec.Grant{}
	}
	return converted.Grants[0]
}
