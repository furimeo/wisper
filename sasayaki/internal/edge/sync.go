package edge

import (
	"context"
	"fmt"
	"log/slog"
	"strconv"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
)

// Making the edge serve exactly what the spec says, and nothing else.
//
// The whole table is rebuilt from the whole spec, every time. Not because it is cheaper -
// it is not - but because a delta between two route tables is a second description of
// what this node serves, and the moment there are two descriptions one of them is wrong.
// A hostname that has left the spec has left the table by construction; nobody has to
// remember to withdraw it.

// Sync makes the edge serve exactly the routes in this spec.
//
// It is called by the reconcile loop only when the route table has changed, so it is
// allowed to be the expensive path: it resolves a container address per app route and
// builds a page handler per route that has nothing behind it. What it is not allowed to
// be is disruptive - the swap at the end is a single pointer store, and a request that
// was in flight when it happened finishes against the table it started with.
//
// A route whose backend cannot be found is not an error. It is a route that is not
// serving, reported as such, with a page that says why: a customer whose container is
// crash-looping needs to be told that, and returning an error here would instead make the
// reconcile loop back off and retry the entire node.
func (e *Edge) Sync(ctx context.Context, desired spec.Spec) error {
	e.building.Lock()
	defer e.building.Unlock()

	build := builder{edge: e, previous: e.routes.load(), next: emptyTable()}
	build.run(ctx, desired)

	previous := e.routes.swap(build.next)
	previous.releaseBackendsAbsentFrom(build.next)
	// A hostname that has left the spec stops being reported at all, so what was known
	// about its certificate goes with it: a domain removed and re-added within the hour
	// must not come back wearing the failure that made the customer remove it.
	e.certificates.forget(build.next.domains)

	e.log.Debug("the edge loaded a new route table",
		slog.Int("hostnames", len(build.next.domains)),
		slog.Int("backends", len(build.next.backends)),
		slog.Int("released", releasedCount(previous, build.next)))

	// After the swap, deliberately. The table is live either way, and a store that cannot
	// be written is a reporting problem rather than a serving one. Returning the error
	// makes the reconcile loop call Sync again next pass, which retries the prune.
	if _, err := e.store.PruneCertificates(ctx, build.next.domains); err != nil {
		return fmt.Errorf("edge: forget the certificate records of withdrawn hostnames: %w", err)
	}
	return nil
}

// builder turns one spec into one table.
//
// A struct rather than a chain of functions taking six arguments each. It exists for the
// length of one Sync and is never shared: the table it is filling in is not published
// until it is finished, which is why nothing in here needs a lock.
type builder struct {
	edge *Edge
	// previous is the table being replaced, read for backends worth carrying over.
	previous *table
	next     *table
	// index is the route's position in the spec, which is what makes the key of a
	// non-reusable backend unique even when two routes agree on everything else.
	index int
}

func (b *builder) run(ctx context.Context, desired spec.Spec) {
	for index, route := range desired.Routes {
		b.index = index

		host := normaliseHost(route.Domain)
		if host == "" {
			// A route with no hostname can never be matched. Logged rather than dropped
			// in silence, because the panel is waiting for a status about it.
			b.edge.log.Warn("ignoring a route with no hostname",
				slog.String("workload", route.WorkloadID))
			continue
		}
		if host != route.Domain {
			// The panel's contract is a lower-cased A-label (workload.proto, Route.domain),
			// so this never happens - and if it starts to, the status the node reports
			// will be keyed by the canonical spelling while the panel is looking for the
			// one it sent. Saying so is the difference between finding that in an hour and
			// finding it in a week.
			b.edge.log.Warn("the panel sent a hostname that is not in canonical form",
				slog.String("sent", route.Domain),
				slog.String("serving_as", host))
		}
		route.Domain = host

		workload, found := desired.Workload(route.WorkloadID)
		if !found {
			b.next.add(host, b.refused(route, fmt.Sprintf(
				"this address points at %s, which is not deployed on this node",
				route.WorkloadID)))
			continue
		}

		switch {
		case workload.IsSite():
			b.next.add(host, b.site(route, workload))
		case workload.IsApp():
			b.next.add(host, b.app(ctx, route, workload))
		default:
			// A kind this binary does not recognise. Guessing between "start a container"
			// and "serve a directory" is the guess spec.KindUnknown exists to prevent.
			b.next.add(host, b.refused(route, fmt.Sprintf(
				"the workload %s is of a kind this node's version does not understand, so "+
					"it cannot tell whether to proxy to it or serve files from it",
				workload.ID)))
		}
	}
}

// app routes a hostname to a container.
//
// The address is resolved here so the panel can be told whether the route is serving, and
// resolved again at dial time by the proxy itself - a container that restarts between two
// reconcile passes comes back on a new address, and a proxy holding the old one would
// fail every request until something unrelated rebuilt the table.
func (b *builder) app(ctx context.Context, route spec.Route, workload spec.Workload) *entry {
	if route.Port == 0 || route.Port > 65535 {
		return b.refused(route, fmt.Sprintf(
			"the route to %s names port %d, which is not a port a container can listen on",
			workload.ID, route.Port))
	}
	if workload.Desired == spec.DesiredStopped {
		return b.refused(route,
			"this application is stopped, so there is nothing behind this address right now")
	}

	port := uint16(route.Port)
	key := "app|" + workload.ID + "|" + strconv.FormatUint(uint64(port), 10)
	backend, already := b.next.backends[key]
	if !already {
		carried, kept := b.previous.backends[key]
		if kept {
			backend = carried
		} else {
			backend = b.edge.newProxy(workload.ID, port)
		}
		b.next.backends[key] = backend
	}

	serving, detail := true, ""
	if _, err := b.edge.backends.Address(ctx, workload.ID, port); err != nil {
		serving, detail = false, err.Error()
	}
	return &entry{route: route, backend: backend, serving: serving, detail: detail}
}

// site routes a hostname to a directory of files.
//
// No process, no port, no container: the release the builder published is read straight
// off the disk (design section 5.5). The handler is rebuilt on every sync because it
// holds nothing worth carrying - it opens the release directory per request, which is
// what makes a symlink swap visible to the very next visitor.
func (b *builder) site(route spec.Route, workload spec.Workload) *entry {
	served := newSite(b.edge.publishedDir(workload.ID), workload.Site)
	backend := b.install(&liveBackend{
		key:     b.uniqueKey("site", route),
		serve:   served,
		release: func() {},
	})

	serving, detail := true, ""
	if err := served.ready(); err != nil {
		serving, detail = false, err.Error()
	}
	return &entry{route: route, backend: backend, serving: serving, detail: detail}
}

// refused is a route that is loaded but has nothing to serve.
//
// It is still loaded, and that is deliberate: the hostname keeps its certificate and the
// visitor gets a page instead of a connection reset, which is the difference between
// "this is broken" and "this does not exist". A customer chasing a failed deployment
// needs the first answer.
func (b *builder) refused(route spec.Route, reason string) *entry {
	backend := b.install(&liveBackend{
		key:     b.uniqueKey("unavailable", route),
		serve:   unavailablePage(reason),
		release: func() {},
	})
	return &entry{route: route, backend: backend, serving: false, detail: reason}
}

func (b *builder) install(backend *liveBackend) *liveBackend {
	b.next.backends[backend.key] = backend
	return backend
}

// uniqueKey names a backend that must never be carried across a sync.
//
// The route's position in the spec is in it because two routes can otherwise agree on
// everything: the same hostname, the same missing workload, the same empty prefix.
func (b *builder) uniqueKey(kind string, route spec.Route) string {
	return kind + "|" + strconv.Itoa(b.index) + "|" + route.Domain + "|" + route.PathPrefix
}

// releasedCount is how many backends the swap let go of, for the log line.
func releasedCount(previous, next *table) int {
	released := 0
	for key := range previous.backends {
		if _, kept := next.backends[key]; !kept {
			released++
		}
	}
	return released
}
