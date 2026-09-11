package dbengine

import (
	"context"
	"log/slog"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
)

// Looking at what the servers on this node really hold.
//
// One place, because four callers need the same answer and asking four times would both cost
// four round trips per pass and let two of them disagree: convergence needs to know which
// grants are missing, provisioning needs to know whether a name is taken, dropping needs to
// know which server holds it, and the status batch needs the sizes.
//
// Nothing here fails the caller. A server that is down, still initialising or refusing the
// administrative password produces an observation that says so, and the packages above turn
// that into `exists: false` with a reason rather than into an absent status - because the
// panel showing "unavailable" is right and it showing "deleted" is a customer's afternoon.

// observed is one server as it really is.
type observed struct {
	Instance instance
	// ContainerID is empty when there is no container for this server yet.
	ContainerID string
	// Ready is whether the server answered its probe.
	Ready bool
	// Version is what it said it is: "PostgreSQL 17.2", "8.4.3". Empty when it did not answer.
	Version string
	// Databases is every database on it, with owner and size. Empty and meaningless when
	// Ready is false, which is what Ready is for.
	Databases []databaseFact
	// Detail is why Ready is false, in a sentence a person can act on.
	Detail string
}

// database finds one database on this server by name.
func (o observed) database(name string) (databaseFact, bool) {
	for _, fact := range o.Databases {
		if fact.Name == name {
			return fact, true
		}
	}
	return databaseFact{}, false
}

// holdsCustomerData reports whether anything has been created on this server yet.
//
// Used to tell an empty dedicated instance from one that is already somebody's, which is the
// only signal available for placing a dedicated grant when the wire does not name its server.
func (o observed) holdsCustomerData() bool { return len(o.Databases) > 0 }

// look asks every server what it is holding.
//
// The budget is passed down to the readiness probe and is the whole of the difference between
// the two callers: a command a person is waiting on gives a cold server minutes to finish
// initialising, and the fifteen-second status pass gives it one probe and moves on.
func (e *Engines) look(ctx context.Context, wanted []instance, budget time.Duration) (map[string]observed, error) {
	existing, err := e.containers(ctx)
	if err != nil {
		return nil, err
	}

	servers := make(map[string]observed, len(wanted))
	for _, built := range wanted {
		servers[built.ID] = e.inspect(ctx, built, existing[built.ID], budget)
	}
	return servers, nil
}

// inspect is look for one server.
func (e *Engines) inspect(ctx context.Context, built instance, container found, budget time.Duration) observed {
	seen := observed{Instance: built, ContainerID: container.ID}

	if container.ID == "" {
		seen.Detail = "this node has not created the container for this database server yet"
		return seen
	}
	if !container.Running {
		seen.Detail = "the database server container is not running (" + container.Status + ")"
		return seen
	}
	if err := e.waitReady(ctx, built, container.ID, budget); err != nil {
		seen.Detail = err.Error()
		return seen
	}

	execute := e.runner(container.ID)
	version, err := built.Talk.version(ctx, execute, built.Spec)
	if err != nil {
		seen.Detail = err.Error()
		return seen
	}
	facts, err := built.Talk.inventory(ctx, execute, built.Spec)
	if err != nil {
		seen.Version = version
		seen.Detail = err.Error()
		return seen
	}

	seen.Ready = true
	seen.Version = version
	seen.Databases = facts
	return seen
}

// survey resolves the spec and looks at every server it names, in one call.
//
// The three commands and the status pass all start this way, and they all need the same three
// things: what the panel wants, what is really there, and which server each grant belongs on.
func (e *Engines) survey(ctx context.Context, budget time.Duration) (spec.Spec, []instance, map[string]observed, error) {
	desired, err := e.desired(ctx)
	if err != nil {
		return spec.Spec{}, nil, nil, err
	}

	wanted, problems := e.resolve(desired)
	for _, problem := range problems {
		// Logged rather than returned: one server this node cannot resolve must not stop it
		// reporting on the other, and the panel is told through each grant's last_error.
		e.log.Warn("a database server in the spec could not be resolved", slog.String("error", problem))
	}

	servers, err := e.look(ctx, wanted, budget)
	if err != nil {
		return spec.Spec{}, nil, nil, err
	}
	return desired, wanted, servers, nil
}
