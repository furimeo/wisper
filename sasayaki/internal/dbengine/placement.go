package dbengine

import (
	"fmt"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
)

// Which server a grant lives on.
//
// # The gap this file exists to close
//
// `DatabaseGrant` carries the engine *kind* and a `dedicated_instance` boolean, and nothing
// else about where it belongs. `DatabaseEngineSpec` carries `data_volume_id`, which is the
// panel's own `database_engine.id`. There is no field joining the two, so on a node running
// more than one server of the same kind - one shared and one or more dedicated, which the
// schema explicitly allows (schema.md, `database_engine`) - the wire does not say which server
// a grant belongs to. The field that would fix it is `DatabaseGrant.engine_id`.
//
// Until it exists, the rule below is deterministic, prefers observed fact over inference, and
// refuses rather than guesses when it cannot tell. Creating a customer's database on the wrong
// server is not recoverable by anything the panel can do afterwards; refusing shows up as a
// `last_error` an operator can read.
//
// # The rule
//
//  1. One server of a kind: everything of that kind goes there. This is every node the panel
//     builds by default and the case that has to be simple.
//  2. Otherwise, a grant whose database is already *on* a server belongs to that server.
//     Observed fact beats every inference, and it is what makes the assignment stable across
//     restarts, spec changes and a grant being removed from the middle of the list.
//  3. A grant with no home yet and no dedicated flag goes to the shared server, which is the
//     lowest-numbered port of its kind. The spec orders engines by (engine, port) and the
//     shared instance is created before any dedicated one on a node the panel built, so this
//     is stable; it is also the only ordering the wire offers.
//  4. A dedicated grant with no home yet takes an unclaimed server of its kind that is not the
//     shared one and holds nothing yet. Empty, because a dedicated server that already has a
//     database on it is somebody else's.
//  5. A dedicated grant with nowhere to go is refused and reported. That is a spec naming a
//     dedicated grant without the server it was paid for, which is a panel bug, and inventing
//     a placement for it would put two customers who each paid for isolation on one machine.

// homes is where each grant belongs, and why the ones without a home do not have one.
type homes struct {
	// byGrant maps a grant id to an instance id.
	byGrant map[string]string
	// refused maps a grant id to the reason it could not be placed.
	refused map[string]string
}

// place works out where every grant in the spec belongs.
func place(desired spec.Spec, wanted []instance, servers map[string]observed) homes {
	placed := homes{
		byGrant: make(map[string]string, len(desired.Grants)),
		refused: make(map[string]string),
	}

	byKind := make(map[spec.EngineKind][]instance, 2)
	for _, built := range wanted {
		byKind[built.Kind] = append(byKind[built.Kind], built)
	}

	claimed := make(map[string]bool, len(wanted))

	// Rule 2 first, for every grant, before anything is inferred: a server that already holds
	// a grant's database owns it whatever the rest of the rule would have said.
	for _, grant := range desired.Grants {
		for _, built := range byKind[grant.Engine] {
			seen, known := servers[built.ID]
			if !known || !seen.Ready {
				continue
			}
			if _, present := seen.database(grant.DatabaseName); present {
				placed.byGrant[grant.ID] = built.ID
				if grant.Dedicated {
					claimed[built.ID] = true
				}
				break
			}
		}
	}

	for _, grant := range desired.Grants {
		if _, done := placed.byGrant[grant.ID]; done {
			continue
		}
		candidates := byKind[grant.Engine]
		if len(candidates) == 0 {
			placed.refused[grant.ID] = fmt.Sprintf(
				"this node runs no %s server, so the database %s could not be placed on it",
				grant.Engine, grant.DatabaseName)
			continue
		}
		if len(candidates) == 1 {
			placed.byGrant[grant.ID] = candidates[0].ID
			claimed[candidates[0].ID] = claimed[candidates[0].ID] || grant.Dedicated
			continue
		}

		shared := candidates[0]
		if !grant.Dedicated {
			placed.byGrant[grant.ID] = shared.ID
			continue
		}

		chosen, found := vacant(candidates[1:], servers, claimed)
		if !found {
			placed.refused[grant.ID] = fmt.Sprintf(
				"the database %s is marked as needing its own %s server and this node has no "+
					"free one; the spec names %d server(s) of that kind and every one of them is "+
					"either the shared instance or already holds another customer's data. "+
					"DatabaseGrant carries no engine id, so this node cannot be told which server "+
					"is meant",
				grant.DatabaseName, grant.Engine, len(candidates))
			continue
		}
		placed.byGrant[grant.ID] = chosen
		claimed[chosen] = true
	}

	return placed
}

// vacant is the first server in the list that nothing has claimed and that holds no customer
// data. A server whose state could not be read is skipped rather than treated as empty: "could
// not look" is not "there is nothing there", and this is the one decision in the package where
// getting that wrong puts two customers on one machine.
func vacant(candidates []instance, servers map[string]observed, claimed map[string]bool) (string, bool) {
	for _, built := range candidates {
		if claimed[built.ID] {
			continue
		}
		seen, known := servers[built.ID]
		if !known || !seen.Ready || seen.holdsCustomerData() {
			continue
		}
		return built.ID, true
	}
	return "", false
}

// homeOf is the server one grant was placed on, with the reason when it was not placed.
func (h homes) homeOf(grantID string) (string, string) {
	if id, placed := h.byGrant[grantID]; placed {
		return id, ""
	}
	if reason, refused := h.refused[grantID]; refused {
		return "", reason
	}
	return "", "this node was not told about a database with this id"
}
