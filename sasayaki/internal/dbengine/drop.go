package dbengine

import (
	"context"
	"fmt"
	"log/slog"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Removing a database, or just the login in front of it.
//
// This is the only place in the package that destroys anything, and that is deliberate: the
// panel has to say so, in a command, with `drop_data` set explicitly. Nothing infers a
// deletion from a grant having left the spec, because the wire gives the node no way to tell a
// login it created from one an operator made, and a rule of "remove everything not in the
// document" is a rule whose worst case - one truncated spec - is every account on the machine.
//
// `drop_data` false is a real operation and not a half-finished one. It removes the login and
// leaves the data, which is what locks an application out while somebody works out what it has
// been doing, and it is the recoverable half of a destructive pair.

// DropDatabase removes a database and its login, or just the login.
func (e *Engines) DropDatabase(ctx context.Context, request *wisperpb.DropDatabase) error {
	databaseName := request.GetDatabaseName()
	username := request.GetUsername()

	if err := checkDatabaseName(databaseName); err != nil {
		return err
	}
	if err := checkUsername(username); err != nil {
		return err
	}

	e.converging.Lock()
	defer e.converging.Unlock()

	seen, err := e.serverHolding(ctx, request.GetId(), engineKindOf(request.GetEngine()))
	if err != nil {
		return err
	}

	// The safety check that stops the wrong customer's database being removed: this node only
	// destroys a database whose owner is the login the panel named. A name that belongs to
	// somebody else, or to nobody, is left where it is - and refusing is visible on the panel,
	// while a wrong deletion is not visible anywhere until it is needed.
	if existing, present := seen.database(databaseName); present {
		if existing.Owner != "" && existing.Owner != username {
			return fmt.Errorf("dbengine: the database %s on this server belongs to a different "+
				"login than the one the panel named, so nothing has been removed", databaseName)
		}
	}

	statement, err := e.removal(seen, request.GetDropData(), databaseName, username)
	if err != nil {
		return err
	}
	if _, err := e.runner(seen.ContainerID)(ctx, statement); err != nil {
		return err
	}

	e.clearFailure(request.GetId())
	e.log.Info("removed a managed database",
		slog.String("grant", request.GetId()),
		slog.String("database", databaseName),
		slog.String("username", username),
		slog.Bool("data_dropped", request.GetDropData()),
		slog.String("instance", seen.Instance.ID))
	return nil
}

// removal picks between the destructive statement and the recoverable one.
func (e *Engines) removal(seen observed, dropData bool, databaseName, username string) (command, error) {
	if dropData {
		return seen.Instance.Talk.destroy(seen.Instance.Spec, databaseName, username)
	}
	return seen.Instance.Talk.revoke(seen.Instance.Spec, databaseName, username)
}
