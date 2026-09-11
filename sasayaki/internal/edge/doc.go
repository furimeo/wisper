// Package edge is the web server customers' visitors actually reach.
//
// It is Caddy, compiled into this daemon rather than run beside it, listening on :80 and
// :443 of a node that has a public IP. Nothing about a request goes near the panel: the
// panel has no public address and, more to the point, a panel outage must not be an
// outage for every site on every node (design section 5.4). That single requirement is
// what shapes everything here.
//
// # Why embedded rather than a second process
//
// On-demand TLS needs an answer to one question before it will ask a certificate
// authority for anything: "is this hostname one of ours?". Caddy asks that over HTTP by
// default, and a node whose answer lived in the panel would stop being able to issue
// certificates the moment the tunnel dropped. Here the question is answered by
// permission.go, in this process, from the same route table the request handler uses -
// a map lookup, no socket, no dependency that can be down.
//
// # The route table is the whole design
//
// One immutable snapshot, published through an atomic pointer (table.go). A reconcile
// pass builds a complete replacement and swaps it in; a request in flight keeps reading
// the snapshot it started with, and the next request sees the new one. There is no lock
// on the request path and no window in which the table is half-updated - which matters
// because the two readers are a visitor's request and a TLS handshake, and both happen
// while the swap is going on.
//
// # What Caddy does and what this package does
//
// Caddy owns the listeners, the TLS handshake, certificate issuance and renewal through
// certmagic, HTTP/1.1, HTTP/2 and HTTP/3, response compression and the access log.
// That is precisely the part nobody should write again.
//
// This package owns the dynamic half: which hostname maps to which backend, and how a
// request is served once it has arrived. The Caddy configuration built in caddyconfig.go
// is therefore fixed - it is written once at Start and never reloaded - and every change
// a deployment makes lands in the route table instead. Reloading Caddy's configuration
// for every container that gets rebuilt on a busy node would re-provision every route on
// the machine to change one of them.
//
// A route points at one of two things (design section 5.5):
//
//   - an app, which is a container reached over its tenant's Docker network. Its address
//     is resolved at dial time (containeraddress.go) because a container that restarts
//     can come back on a different address, and a proxy holding the old one would serve
//     502s that no amount of waiting fixes.
//   - a site, which is a directory and no process at all. The edge reads it straight off
//     the disk through the `current` symlink the builder swaps, so publishing a release
//     and rolling one back are both free and neither needs anything running.
package edge
