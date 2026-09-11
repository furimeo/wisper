package edge

import (
	"net/http"
	"strings"
	"sync/atomic"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
)

// The route table, and the swap that replaces it.
//
// A table is immutable once published. Sync builds a complete replacement and stores the
// pointer; readers - a request handler and a TLS handshake, both of which can be running
// while the swap happens - take the pointer once and keep using that snapshot to the end
// of their work. There is no lock on the read path, no partially applied table, and no
// difference between the hostname a certificate was just issued for and the hostname the
// next request is routed by, because both come from the same snapshot.

// liveBackend is the thing bytes are actually sent to, and whatever it is holding.
//
// Separate from the entry because two routes can share one - an apex and a www alias
// pointing at the same container is the normal case - and because a backend can outlive
// the table it was built for. A reverse proxy holds a connection pool to a container;
// throwing it away and building a new one on every reconcile pass that happened to touch
// something else would drop every keep-alive on the node several times a minute.
type liveBackend struct {
	// key identifies what this backend talks to. Two syncs that produce the same key
	// reuse the same backend. Empty means "not reusable": rebuilt on every sync, because
	// what it serves is a page describing a situation that may have changed.
	key string

	// serve answers a request. Never nil - a route whose container is missing still gets
	// a handler that says so in a page, because a visitor who is shown nothing cannot
	// tell a broken deployment from a broken DNS record.
	serve http.Handler

	// release lets go of what the backend holds, once it is out of the published table.
	// Never nil; for anything that holds nothing it is a no-op.
	release func()
}

// entry is one hostname-and-prefix pointing at one backend.
type entry struct {
	route   spec.Route
	backend *liveBackend

	// serving is whether there is something real behind this hostname right now. It is
	// what the panel shows the customer who is waiting for their domain to come up, and
	// it is re-decided on every sync rather than carried with the backend: a container
	// that came back up has the same proxy and a different answer.
	serving bool
	// detail is why it is not serving, in a sentence. Empty when it is.
	detail string
}

// table is one complete snapshot of what this node serves.
type table struct {
	// hosts is every entry for a hostname, longest path prefix first, so a hostname that
	// serves an API from one workload and a site from another resolves to the more
	// specific of the two.
	hosts map[string][]*entry
	// domains is every hostname, in spec order. Statuses reports in this order so the
	// panel's list does not reshuffle itself between passes.
	domains []string
	// backends is every distinct backend in this table, by key. What a swap diffs to
	// decide which connection pools are still wanted and which are to be let go.
	backends map[string]*liveBackend
}

func emptyTable() *table {
	return &table{
		hosts:    make(map[string][]*entry),
		backends: make(map[string]*liveBackend),
	}
}

// add appends one entry, keeping the hostname's entries ordered by descending prefix
// length. Insertion sort over a slice that is almost always one element long.
func (t *table) add(host string, e *entry) {
	if _, known := t.hosts[host]; !known {
		t.domains = append(t.domains, host)
	}
	existing := t.hosts[host]
	at := len(existing)
	for i, other := range existing {
		if len(e.route.PathPrefix) > len(other.route.PathPrefix) {
			at = i
			break
		}
	}
	existing = append(existing, nil)
	copy(existing[at+1:], existing[at:])
	existing[at] = e
	t.hosts[host] = existing
}

// match is the entry that should answer this request, if any.
func (t *table) match(host, path string) (*entry, bool) {
	for _, candidate := range t.hosts[host] {
		if matchesPrefix(candidate.route.PathPrefix, path) {
			return candidate, true
		}
	}
	return nil, false
}

// known reports whether the table serves this hostname at all, whatever the path.
func (t *table) known(host string) bool {
	return len(t.hosts[host]) > 0
}

// wantsCertificate reports whether any route for this hostname asked for TLS. This is the
// on-demand decision, and it is a map lookup precisely so that it can be made during a
// handshake without waiting on anything.
func (t *table) wantsCertificate(host string) bool {
	for _, candidate := range t.hosts[host] {
		if candidate.route.WantsCertificate() {
			return true
		}
	}
	return false
}

// entries is every entry in the table, in hostname order.
func (t *table) entries() []*entry {
	all := make([]*entry, 0, len(t.domains))
	for _, domain := range t.domains {
		all = append(all, t.hosts[domain]...)
	}
	return all
}

// releaseBackendsAbsentFrom lets go of every backend in this table that the next one does
// not use.
//
// Called after the swap, never before: a backend still reachable from the published table
// must stay usable, and a request that started a microsecond ago is still holding the old
// table. What release does is close idle connections, which by definition are not the ones
// in flight - so this is safe the instant the pointer has moved.
func (t *table) releaseBackendsAbsentFrom(next *table) {
	for key, backend := range t.backends {
		if _, kept := next.backends[key]; kept {
			continue
		}
		backend.release()
	}
}

// router holds the published table.
//
// One pointer, one atomic store. Everything about concurrent correctness in this package
// reduces to the fact that a table is never modified after it is published.
type router struct {
	current atomic.Pointer[table]
}

func newRouter() *router {
	r := &router{}
	r.current.Store(emptyTable())
	return r
}

// load is the snapshot to serve this request from. Never nil.
func (r *router) load() *table { return r.current.Load() }

// swap publishes a new table and returns the one it replaced.
func (r *router) swap(next *table) *table {
	if next == nil {
		next = emptyTable()
	}
	return r.current.Swap(next)
}

// matchesPrefix reports whether a request path falls under a route's prefix.
//
// A prefix matches on path segments, not on characters: /api must not capture /apixyz.
// The exact prefix itself matches, so /api reaches the backend that owns /api/.
func matchesPrefix(prefix, path string) bool {
	if prefix == "" || prefix == "/" {
		return true
	}
	trimmed := strings.TrimSuffix(prefix, "/")
	if !strings.HasPrefix(path, trimmed) {
		return false
	}
	rest := path[len(trimmed):]
	return rest == "" || strings.HasPrefix(rest, "/")
}

// normaliseHost reduces the Host header, or a TLS server name, to the form the table is
// keyed by.
//
// Four things happen to it, and every one of them is a way two spellings of the same
// hostname would otherwise miss each other: the port is dropped, IPv6 brackets are
// dropped, the fully-qualified trailing dot is dropped, and what is left is lower-cased.
// The panel already sends a lower-cased A-label, so this is about what arrives from the
// network, where "EXAMPLE.com.:443" is a perfectly ordinary thing for a client to send.
func normaliseHost(value string) string {
	host := strings.TrimSpace(value)
	if host == "" {
		return ""
	}

	// Strip the port, but only when what precedes it is not a bare IPv6 address.
	if strings.HasPrefix(host, "[") {
		if end := strings.IndexByte(host, ']'); end >= 0 {
			host = host[1:end]
		}
	} else if colon := strings.LastIndexByte(host, ':'); colon >= 0 &&
		strings.IndexByte(host, ':') == colon {
		host = host[:colon]
	}

	host = strings.TrimSuffix(host, ".")
	return strings.ToLower(host)
}
