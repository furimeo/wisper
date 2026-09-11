package edge

import (
	"fmt"
	"sync"
)

// The bridge between a Caddy module and the edge it belongs to.
//
// Caddy builds its modules from JSON: it decodes a configuration, calls New for each
// module it finds and provisions the result. There is no way to hand it a Go value, so a
// module that has to reach a live object reaches it by name, and the name travels in the
// JSON. That is what this file is - a lookup from the id in the configuration to the Edge
// that wrote it.
//
// The alternative, a package-level variable holding "the" edge, would work in the daemon
// and fail in the tests, where several edges exist at once and each one has to see its
// own routes. One process, one edge is a fact about deployment; it should not be baked
// into the type.

var registry struct {
	sync.RWMutex
	edges map[string]*Edge
}

func register(e *Edge) {
	registry.Lock()
	defer registry.Unlock()
	if registry.edges == nil {
		registry.edges = make(map[string]*Edge)
	}
	registry.edges[e.id] = e
}

// lookup finds the edge a module was configured for.
//
// A miss is a configuration this package did not write - the only ways to get one are a
// Caddy configuration from somewhere else naming our module, or an edge that was garbage
// before Caddy finished provisioning it. Both are errors worth failing the load for,
// because the alternative is a handler that silently answers nothing.
func lookup(id string) (*Edge, error) {
	registry.RLock()
	defer registry.RUnlock()
	e, known := registry.edges[id]
	if !known {
		return nil, fmt.Errorf("edge: no edge named %q is running in this process, so the "+
			"handler configured for it has nothing to route with", id)
	}
	return e, nil
}
