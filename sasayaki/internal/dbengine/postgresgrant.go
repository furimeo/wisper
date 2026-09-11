package dbengine

import (
	"strconv"
	"strings"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
)

// The SQL that gives a customer a database on a shared PostgreSQL, and the SQL that takes it
// away again.
//
// This text is the isolation between two customers on one server. Everything else in the
// package is plumbing around it, which is why it is in a file of its own with a test that
// asserts on the exact statements rather than on whether the command ran.
//
// What the login may not do:
//
//	NOSUPERUSER    the whole point. A superuser reads every other customer's data, and
//	               COPY ... TO PROGRAM makes it a shell on the node as well.
//	NOCREATEDB     one database is what was paid for. A second one would be storage the
//	               panel cannot see and the quota sweep never measures.
//	NOCREATEROLE   a role this account created is a role the panel does not know about and
//	               will therefore never rotate, revoke or remove.
//	NOREPLICATION  a replication connection streams the whole cluster, not one database.
//	NOBYPASSRLS    row-level security the customer set on their own tables stays on.
//
// And on top of the attributes, two revocations that matter as much: the database is taken
// away from PUBLIC so no other login on the server can connect to it, and the public schema
// inside it is taken away from PUBLIC and handed to the owner, so a login that did somehow
// connect could still not read a table.

// postgresAttributes is the set of things a customer's login is not allowed to be. Written
// once and applied on create, on adopt and on every rotation, because an attribute added by
// hand between two rotations would otherwise survive.
const postgresAttributes = "NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS"

// provision creates the database and the login that owns it.
//
// The role is created inside a DO block, and only when it is not already there, because a
// command the panel repeated after a dropped stream must not fail on its second attempt.
// CREATE DATABASE cannot run inside a block and is therefore unconditional - the caller has
// already established from the inventory that this name is free, which is also where a
// collision with somebody else's database is refused.
func (p postgres) provision(engine spec.Engine, grant spec.Grant, password string) (command, error) {
	if err := checkGrant(grant); err != nil {
		return command{}, err
	}
	if err := checkPassword(password); err != nil {
		return command{}, err
	}

	user := quotePostgres(grant.Username)

	var sql strings.Builder
	sql.WriteString(createPostgresRole(grant.Username))
	sql.WriteString("ALTER ROLE " + user + " WITH LOGIN PASSWORD " + literalPostgres(password) +
		" " + postgresAttributes +
		" CONNECTION LIMIT " + strconv.FormatInt(int64(roleConnections(engine)), 10) + ";\n")
	sql.WriteString(createPostgresDatabase(grant.DatabaseName, grant.Username, grant.Encoding))

	return p.script(engine, "create the database "+grant.DatabaseName+" and its login",
		sql.String(), password), nil
}

// adopt rebuilds a grant whose server lost its disk.
//
// Same database, same owner, and the login left NOLOGIN. An account recreated with no
// password is an account anybody on the server can use, and one recreated with a password the
// node invented is one the panel can neither show the customer nor put in a backup job. So it
// is created switched off, and the caller reports last_error; the rotation the panel sends
// back is the only thing that turns it on.
func (p postgres) adopt(engine spec.Engine, grant spec.Grant) (command, error) {
	if err := checkGrant(grant); err != nil {
		return command{}, err
	}

	var sql strings.Builder
	sql.WriteString(createPostgresRole(grant.Username))
	sql.WriteString("ALTER ROLE " + quotePostgres(grant.Username) + " WITH NOLOGIN " +
		postgresAttributes + ";\n")
	sql.WriteString(createPostgresDatabase(grant.DatabaseName, grant.Username, grant.Encoding))

	return p.script(engine,
		"recreate the database "+grant.DatabaseName+" and its locked login", sql.String()), nil
}

// rotate changes one login's password and switches it back on.
//
// The role is created first when it is missing, so a rotation is also the repair for a login
// that was never made - which is exactly the state adopt leaves behind when the whole grant
// had to be rebuilt.
func (p postgres) rotate(engine spec.Engine, username, password string) (command, error) {
	if err := checkUsername(username); err != nil {
		return command{}, err
	}
	if err := checkPassword(password); err != nil {
		return command{}, err
	}
	return p.script(engine, "change the password of "+username,
		createPostgresRole(username)+
			"ALTER ROLE "+quotePostgres(username)+" WITH LOGIN PASSWORD "+
			literalPostgres(password)+" "+postgresAttributes+";\n", password), nil
}

// revoke locks the login out and disconnects it, and leaves everything it owns alone.
//
// NOLOGIN rather than DROP ROLE, because the role owns the database: dropping it would either
// be refused or leave a customer's data owned by nobody. Sessions already open are terminated
// too, since a connection that has authenticated keeps working until it reconnects, which
// would turn "revoked" into "revoked in an hour or so".
func (p postgres) revoke(engine spec.Engine, _, username string) (command, error) {
	if err := checkUsername(username); err != nil {
		return command{}, err
	}
	return p.script(engine, "lock the login "+username+" out",
		"ALTER ROLE "+quotePostgres(username)+" WITH NOLOGIN;\n"+
			"SELECT pg_catalog.pg_terminate_backend(pid) FROM pg_catalog.pg_stat_activity"+
			" WHERE usename = "+literalPostgres(username)+
			" AND pid <> pg_catalog.pg_backend_pid();\n"), nil
}

// destroy removes the database and the login.
//
// The open sessions are terminated first. DROP DATABASE waits for the last connection to go
// away rather than taking it away, so a customer's own idle pool would otherwise make this
// hang until the command's deadline - and the panel would show a delete that never finishes.
func (p postgres) destroy(engine spec.Engine, databaseName, username string) (command, error) {
	if err := checkDatabaseName(databaseName); err != nil {
		return command{}, err
	}
	if err := checkUsername(username); err != nil {
		return command{}, err
	}
	return p.script(engine, "drop the database "+databaseName+" and its login",
		"SELECT pg_catalog.pg_terminate_backend(pid) FROM pg_catalog.pg_stat_activity"+
			" WHERE datname = "+literalPostgres(databaseName)+
			" AND pid <> pg_catalog.pg_backend_pid();\n"+
			"DROP DATABASE IF EXISTS "+quotePostgres(databaseName)+";\n"+
			"DROP ROLE IF EXISTS "+quotePostgres(username)+";\n"), nil
}

// createPostgresRole is the idempotent half of every account statement.
//
// Dollar quoting rather than escaped quotes, so the body of the block needs no escaping of
// its own and the one literal in it - the role name, already checked to be lower-case
// letters, digits and underscores - cannot end the string early.
func createPostgresRole(username string) string {
	return "DO $wisper$\nBEGIN\n" +
		"\tIF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = " +
		literalPostgres(username) + ") THEN\n" +
		"\t\tCREATE ROLE " + quotePostgres(username) + " NOLOGIN " + postgresAttributes + ";\n" +
		"\tEND IF;\nEND\n$wisper$;\n"
}

// createPostgresDatabase is the database and the lockdown that goes with it.
//
// Two psql meta-commands, and both are why these scripts are fed to psql rather than assembled
// as one statement:
//
//   - \gexec runs the statement a query produced, which is the only way to make CREATE
//     DATABASE conditional: it cannot appear inside a DO block or a transaction, so there is
//     no server-side IF NOT EXISTS available. Without it a repeated ProvisionDatabase - which
//     is what the panel sends after a dropped stream - would fail on the second attempt.
//   - \connect, because the public schema of a database can only be altered from inside that
//     database, and reaching it any other way would mean a second connection with a password
//     on a second command line.
//
// An empty owner leaves the database belonging to the administrator and skips the schema
// handover, which is what a restore into a scratch name nobody is going to log into wants.
func createPostgresDatabase(name, owner, encoding string) string {
	database := quotePostgres(name)

	// template0 rather than template1 whenever an encoding is asked for: template1 may have
	// been given objects by an operator, and a database created in an encoding its template
	// does not use is refused outright.
	clause := ""
	if encoding != "" {
		clause = " ENCODING " + literalPostgres(encoding) + " TEMPLATE template0"
	}
	if owner != "" {
		clause = " OWNER " + quotePostgres(owner) + clause
	}

	script := createDatabaseIfMissing(name, "CREATE DATABASE "+database+clause) +
		"REVOKE ALL ON DATABASE " + database + " FROM PUBLIC;\n" +
		"\\connect " + database + "\n" +
		postgresPrologue +
		"REVOKE ALL ON SCHEMA public FROM PUBLIC;\n"
	if owner != "" {
		user := quotePostgres(owner)
		script += "ALTER SCHEMA public OWNER TO " + user + ";\n" +
			"GRANT ALL ON SCHEMA public TO " + user + ";\n"
	}
	return script
}

// createDatabaseIfMissing is the \gexec idiom: a query that yields the statement to run, and
// yields no rows at all when the database is already there.
func createDatabaseIfMissing(name, statement string) string {
	return "SELECT " + literalPostgres(statement) + "\n" +
		"WHERE NOT EXISTS (SELECT 1 FROM pg_catalog.pg_database WHERE datname = " +
		literalPostgres(name) + ")\n\\gexec\n"
}

// checkGrant refuses a grant whose names or encoding could not have come from the panel.
func checkGrant(grant spec.Grant) error {
	if err := checkDatabaseName(grant.DatabaseName); err != nil {
		return err
	}
	if err := checkUsername(grant.Username); err != nil {
		return err
	}
	return checkEncoding(grant.Encoding)
}

// roleConnections is how many connections one customer may hold on a shared server.
//
// An eighth of the server's ceiling, with a floor of five. Without a per-role limit the first
// customer whose pool leaks takes every connection slot and every other customer on the
// machine sees "too many clients already" - which looks like a platform fault and is one
// customer's bug. The number is the node's own safety default and not something the panel
// sends, because the panel has one figure for the whole server and no idea how many customers
// will end up on it.
func roleConnections(engine spec.Engine) int32 {
	limit := maxConnections(engine) / 8
	if limit < 5 {
		return 5
	}
	return limit
}
