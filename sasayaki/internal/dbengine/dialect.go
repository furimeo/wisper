package dbengine

import (
	"context"
	"fmt"
	"strconv"
	"strings"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
)

// What differs between PostgreSQL and MySQL, in one list.
//
// The two are not interchangeable anywhere: the SQL that creates a login, the way a database
// is sized, the dump format and the client's own flags are all different, and pretending
// otherwise is how a platform ends up with a MySQL branch nobody exercised. So every
// difference is a method here, every method has exactly two implementations, and the code
// above this file never asks which engine it is talking to.
//
// Everything that can be a pure function is one. `provision`, `rotate`, `destroy` and the
// rest return a command rather than running it, which is what makes privilege_test.go able
// to assert on the exact SQL a customer's account is created with - with no container, no
// server and nothing to mock. Only the two operations that need more than one round trip -
// reading the version, taking an inventory - are handed a runner.

// command is one invocation of an engine's client program inside its container.
type command struct {
	// Argv of the program. Never a shell string, and never a password: argv is readable by
	// every user on the machine through `ps`, which is the same reason the enrolment token
	// is refused on a command line (AGENTS.md section 5).
	Argv []string
	// Env carries the administrative password to the client - PGPASSWORD, MYSQL_PWD - which
	// is where both of them look for it and is not visible outside the process.
	Env []string
	// SQL is fed to the program's standard input and then closed. Everything with a secret
	// in it travels here.
	SQL string
	// Secrets are the values that must never reach a log line or a status message.
	//
	// Not paranoia: `psql` reports a syntax error by quoting the line it failed on, and the
	// line a password is on is exactly the line most likely to be quoted. exec.go removes
	// each of these from anything a failed command produced, so the panel gets "the server
	// refused the statement" rather than the customer's new password in an audit record.
	Secrets []string
	// Describes what this command is for, in a log line and in an error. "create the
	// database and its login", not "psql".
	Purpose string
}

// databaseFact is one database as the server reports it.
type databaseFact struct {
	Name string
	// Owner is the login that owns the database on PostgreSQL, and the login holding
	// schema-level privileges on MySQL. Empty when nobody but the administrator has any,
	// which is how a database created by hand is told apart from one this package made.
	Owner string
	// SizeBytes is what the server's own accounting says, which is the figure the quota is
	// compared against - not a `du` of a directory, which on a shared server measures every
	// customer at once.
	SizeBytes int64
}

// run executes one command inside a server's container and returns its standard output.
type run func(ctx context.Context, c command) (string, error)

// dialect is one database engine, as this package needs it.
type dialect interface {
	kind() spec.EngineKind

	// container is how the server itself is started: the environment the official image
	// reads its administrative credentials from, the arguments that set its port and its
	// connection ceiling, and where its data directory lives inside the container.
	container(engine spec.Engine) containerPlan

	// probe is the cheapest statement that proves the server is accepting connections. Run
	// in a loop by ready.go, so it must be one round trip and must not create anything.
	probe(engine spec.Engine) command

	// version is what the panel shows and what warns before a dump lands on a server that
	// cannot read it: "PostgreSQL 17.2", "8.4.3".
	version(ctx context.Context, execute run, engine spec.Engine) (string, error)

	// inventory is every database on the server with its owner and its size. One or two
	// queries depending on the engine, which is why it takes a runner rather than returning
	// commands.
	inventory(ctx context.Context, execute run, engine spec.Engine) ([]databaseFact, error)

	// harden is run once after a server first becomes ready. It closes the doors the
	// official images leave open - PUBLIC being able to connect to the administrative
	// database, and on MySQL an administrative login that is not the one the panel named.
	harden(engine spec.Engine) command

	// provision creates the database and a login that can do everything inside it and
	// nothing outside it.
	provision(engine spec.Engine, grant spec.Grant, password string) (command, error)

	// adopt recreates a grant whose server lost its disk. Same database, same login, and
	// the login is deliberately unusable until the panel sends a rotation: an account
	// recreated with no password is an account anybody can use.
	adopt(engine spec.Engine, grant spec.Grant) (command, error)

	// rotate changes one login's password and unlocks it.
	rotate(engine spec.Engine, username, password string) (command, error)

	// revoke removes the login and leaves the data. A real operation, not a half-finished
	// drop: it locks an application out while an operator investigates.
	revoke(engine spec.Engine, databaseName, username string) (command, error)

	// destroy removes the database and the login.
	destroy(engine spec.Engine, databaseName, username string) (command, error)

	// ensureDatabase creates an empty database if it is not there, for a restore into a
	// name that does not exist yet - which is what a dry run is.
	ensureDatabase(engine spec.Engine, name, owner string) (command, error)

	// resetDatabase empties a database that is about to be restored over: it drops the
	// database, makes it again, and leaves the login alone.
	//
	// Deliberately not destroy followed by ensureDatabase. destroy removes the login too, and
	// the next statement would then be creating a database owned by a role that no longer
	// exists - which fails, and which the node cannot repair, because the password that would
	// recreate the login belongs to the panel.
	resetDatabase(engine spec.Engine, name, owner string) (command, error)

	// dump writes a logical backup to a path inside the container.
	dump(engine spec.Engine, databaseName, containerPath string) (command, error)

	// restore reads one back.
	restore(engine spec.Engine, databaseName, containerPath string) (command, error)

	// dumpExtension is what a dump of this engine is called, so an operator finding the
	// object in a bucket can tell what will read it.
	dumpExtension() string
}

// containerPlan is the engine-specific half of a server's container.
type containerPlan struct {
	// Env is what the official image reads its administrative credentials and its data
	// directory from.
	Env []string
	// Cmd is passed to the server process: the port and the connection ceiling.
	Cmd []string
	// DataMount is where the data directory appears inside the container.
	DataMount string
	// Port is the port the server actually listens on, which is the panel's listen_port -
	// set inside the container as well as published, so the address in a connection string
	// is the same number wherever it is used.
	Port uint32
}

// dialectFor picks the implementation for an engine kind.
//
// An unrecognised kind is an error and never a default. Starting a PostgreSQL container and
// then talking to it with a MySQL client produces a server nobody can use and a dump nothing
// can restore, and both failures would appear hours later somewhere else.
func dialectFor(kind spec.EngineKind) (dialect, error) {
	switch kind {
	case spec.EnginePostgres:
		return postgres{}, nil
	case spec.EngineMySQL:
		return mysql{}, nil
	default:
		return nil, fmt.Errorf("dbengine: this node does not know the database engine %q, so it "+
			"has created nothing: a server started with the wrong client would produce a database "+
			"no dump can be restored into", kind)
	}
}

// listenPort is the port a server runs on, with the engine's own default when the panel sent
// none. Zero would make the server pick its default and the connection string say 0.
func listenPort(engine spec.Engine, fallback uint32) uint32 {
	if engine.ListenPort == 0 || engine.ListenPort > 65535 {
		return fallback
	}
	return engine.ListenPort
}

// maxConnections is the ceiling for the whole shared server, with a floor underneath it.
//
// Without a ceiling one customer's leaking pool locks every other customer out of the same
// container. With one that is too small the server refuses the administrative connection
// this package needs to measure anything, so a value the panel has not set becomes a
// sensible default rather than zero.
func maxConnections(engine spec.Engine) int32 {
	if engine.MaxConnections < 10 {
		return 200
	}
	return engine.MaxConnections
}

// portArgument renders a port for a command line.
func portArgument(port uint32) string { return strconv.FormatUint(uint64(port), 10) }

// rows splits a client's tab-separated output into fields, dropping blank lines.
//
// Both clients are asked for unaligned, headerless, tab-separated output, so this is the
// whole of the parsing. A row with fewer fields than expected is a bug in the query rather
// than something to tolerate, and the caller says so.
func rows(output string) [][]string {
	lines := strings.Split(strings.ReplaceAll(output, "\r\n", "\n"), "\n")
	parsed := make([][]string, 0, len(lines))
	for _, line := range lines {
		if strings.TrimSpace(line) == "" {
			continue
		}
		parsed = append(parsed, strings.Split(line, "\t"))
	}
	return parsed
}

// firstField is the single value a one-row, one-column query returned.
func firstField(output string) string {
	for _, row := range rows(output) {
		if len(row) > 0 {
			return strings.TrimSpace(row[0])
		}
	}
	return ""
}

// parseBytes reads a size a server reported. An unreadable figure is zero rather than an
// error: a size that could not be parsed must not stop the other forty databases in the same
// batch from being reported.
func parseBytes(value string) int64 {
	size, err := strconv.ParseInt(strings.TrimSpace(value), 10, 64)
	if err != nil || size < 0 {
		return 0
	}
	return size
}
