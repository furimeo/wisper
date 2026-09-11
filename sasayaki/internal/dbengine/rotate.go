package dbengine

import (
	"context"
	"fmt"
	"log/slog"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Changing one login's password.
//
// The customer pressed "rotate", or an operator is revoking a credential that leaked, or a
// grant this node had to recreate is being switched back on. The third case is the one worth
// naming: a node that lost its disk rebuilds the database and the login but cannot rebuild the
// password, so it leaves the account locked and reports why. The rotation the panel sends back
// is the only thing that makes the account usable again, which is why this statement unlocks
// as well as sets - a rotation that only set the password would leave the customer with the
// right credential and an account that refuses it.

// RotateDatabasePassword changes one login's password and switches the account back on.
func (e *Engines) RotateDatabasePassword(ctx context.Context, request *wisperpb.RotateDatabasePassword) error {
	username := request.GetUsername()
	password := request.GetNewPassword()
	kind := engineKindOf(request.GetEngine())

	if err := checkUsername(username); err != nil {
		e.recordFailure(request.GetId(), err.Error())
		return err
	}
	if err := checkPassword(password); err != nil {
		e.recordFailure(request.GetId(), err.Error())
		return err
	}

	e.converging.Lock()
	defer e.converging.Unlock()

	seen, err := e.serverHolding(ctx, request.GetId(), kind)
	if err != nil {
		e.recordFailure(request.GetId(), err.Error())
		return err
	}

	statement, err := seen.Instance.Talk.rotate(seen.Instance.Spec, username, password)
	if err != nil {
		e.recordFailure(request.GetId(), err.Error())
		return err
	}
	if _, err := e.runner(seen.ContainerID)(ctx, statement); err != nil {
		e.recordFailure(request.GetId(), err.Error())
		return err
	}

	e.clearFailure(request.GetId())
	e.log.Info("changed a database password",
		slog.String("grant", request.GetId()),
		slog.String("username", username),
		slog.String("instance", seen.Instance.ID))
	return nil
}

// serverHolding finds the ready server one grant already lives on.
//
// By grant id, because rotate and drop both name a grant that has been provisioned rather than
// describing a new one, and the placement rule has already worked out where it went. A grant
// the spec does not mention is refused: the node has no way to tell which of two servers of
// the same kind it was meant, and changing a password on the wrong one would lock a customer
// out of a database that was working.
func (e *Engines) serverHolding(ctx context.Context, grantID string, kind spec.EngineKind) (observed, error) {
	if grantID == "" {
		return observed{}, fmt.Errorf("dbengine: the panel named no database, so there was " +
			"nothing to act on")
	}

	desired, wanted, servers, err := e.survey(ctx, GlanceBudget)
	if err != nil {
		return observed{}, err
	}

	instanceID, refusal := place(desired, wanted, servers).homeOf(grantID)
	if refusal != "" {
		return observed{}, fmt.Errorf("dbengine: %s", refusal)
	}

	seen := servers[instanceID]
	if kind != spec.EngineUnknown && seen.Instance.Kind != kind {
		// The panel and the node disagree about which engine this database is on. Refusing is
		// the only safe answer: the same name can legitimately exist on both servers.
		return observed{}, fmt.Errorf("dbengine: the panel says this database is on a %s server "+
			"and this node has it on a %s one", kind, seen.Instance.Kind)
	}
	if seen.Ready {
		return seen, nil
	}
	if seen.ContainerID == "" {
		return observed{}, fmt.Errorf("dbengine: the %s server for this database is not on this "+
			"node yet: %s", seen.Instance.Kind, seen.Detail)
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
		return observed{}, fmt.Errorf("dbengine: the %s server holding this database is not "+
			"ready: %s", seen.Instance.Kind, seen.Detail)
	}
	return seen, nil
}

// engineKindOf maps the wire's enum through the spec package's own conversion, so an engine
// this binary does not know becomes spec.EngineUnknown in one place rather than three.
func engineKindOf(value wisperpb.DatabaseEngine) spec.EngineKind {
	converted := spec.FromProto(&wisperpb.NodeSpec{
		Databases: []*wisperpb.DatabaseGrant{{Engine: value}},
	})
	if len(converted.Grants) == 0 {
		return spec.EngineUnknown
	}
	return converted.Grants[0].Engine
}
