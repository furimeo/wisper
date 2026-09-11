// Package dbengine runs the database servers a node hosts and carves customer databases
// out of them.
//
// # Why the servers are shared
//
// One PostgreSQL container and one MySQL container per node, with every customer getting a
// database and a login inside them. An idle PostgreSQL costs 30-50 MB of resident memory
// and a node holds hundreds of customers, so a container each is the whole machine spent on
// processes that are doing nothing (design section 8.1). A customer who pays for isolation
// gets `dedicated_instance` on their grant and the panel publishes a second engine for
// them; the code below treats that as one more instance rather than as a special case.
//
// # What the panel owns and what this package owns
//
// The panel owns intent and this package owns fact, which here means something very
// concrete: the panel picks the image, the port, the administrative credentials, the
// resource ceilings and the connection limit, and it generates every password. The node
// never invents a secret, because a secret the node invented is one the panel could neither
// show the customer nor put in a backup job.
//
// # Never a superuser
//
// A customer's login is created with every administrative attribute switched off -
// NOSUPERUSER, NOCREATEDB, NOCREATEROLE, NOREPLICATION, NOBYPASSRLS on PostgreSQL, and a
// grant scoped to one schema with no GRANT OPTION on MySQL. Their database is revoked from
// PUBLIC so nobody else on the same server can connect to it. That is the whole of the
// isolation between two customers sharing an engine, which is why privilege_test.go asserts
// on the generated SQL rather than trusting it to stay right.
//
// # No SQL driver
//
// Every statement runs through the engine's own client program inside the engine's own
// container: `psql`, `pg_dump`, `mysql`, `mysqldump`. Three reasons, in the order they
// matter. The client always matches the server, so a dump taken on a node running
// PostgreSQL 17 is never produced by a 15 client that silently omits something. sasayaki
// stays one static binary with no database driver linked into it. And the connection is a
// unix socket inside the container rather than a TCP port on the host, so the administrative
// login is not reachable from the network at all.
//
// The corollary is that identifiers reach a SQL string. identifier.go is the answer:
// database names and usernames are checked against exactly the shape the panel's own schema
// constrains them to (`^[a-z][a-z0-9_]{2,62}$` and `^[a-z][a-z0-9_]{2,30}$`) and refused
// otherwise, and they are quoted on top of that. Passwords never appear in a command line -
// argv is world-readable through `ps` - and travel on standard input instead.
//
// # Readiness is waited for, never slept through
//
// A database server that has just been created spends anywhere from two seconds to a minute
// running initdb before it accepts a connection. Nothing here sleeps for a guessed interval;
// ready.go polls the server with its own client until it answers, with a deadline the caller
// chooses. A command the customer is waiting on gets minutes; the status pass that runs
// every fifteen seconds gets a couple of seconds and reports "not yet" rather than holding
// the whole batch up.
//
// # What this package will not do
//
// It never drops a database or a login except when the panel sends DropDatabase. A grant
// that has left the spec is left alone, and one that is in the spec but missing from the
// server is recreated with its login locked and reported with `last_error` so the panel
// re-issues a rotation. Omission removing a container is safe because the image can be
// pulled again; omission removing a database is a customer's data gone, and a spec that
// arrived truncated would take out every account on the node at once.
package dbengine
