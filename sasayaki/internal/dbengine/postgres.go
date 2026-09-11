package dbengine

import (
	"context"
	"fmt"
	"strconv"
	"strings"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
)

// PostgreSQL, as this node runs it: the container, the client and the reads.
//
// The SQL that makes and unmakes a customer's account is next door in postgresgrant.go,
// because that is the part whose exact text is the isolation between two customers sharing
// one server and it deserves to be read on its own.
//
// Every statement goes through `psql` inside the server's own container, over the unix
// socket in /var/run/postgresql - so the administrative login is not reachable over the
// network at all, and the client is always the same major version as the server.
//
// The two settings at the top of every script are not decoration. ON_ERROR_STOP makes psql
// exit non-zero on the first failed statement instead of carrying on and reporting success
// after having created half an account. standard_conforming_strings makes a backslash inside
// a string literal an ordinary character, which is what literalPostgres assumes; it is on by
// default and it is also a setting a customer can change on their own database, so it is
// asserted rather than trusted.

type postgres struct{}

const (
	// postgresSocket is where the official image puts its unix socket.
	postgresSocket = "/var/run/postgresql"
	// postgresMount is where the official image expects its data directory, and postgresData
	// is the subdirectory inside it that PGDATA actually points at. The extra level is the
	// image's own documented requirement: initdb refuses a directory that is not empty, and
	// a bind mount on Linux never is.
	postgresMount = "/var/lib/postgresql/data"
	postgresData  = postgresMount + "/pgdata"
	// postgresPort is the port the server uses when the panel sent none.
	postgresPort uint32 = 5432
	// postgresPrologue is the first line of every script.
	postgresPrologue = "SET standard_conforming_strings = on;\n"
)

func (postgres) kind() spec.EngineKind { return spec.EnginePostgres }

func (postgres) dumpExtension() string { return ".pgdump" }

// container is how the official postgres image is configured.
//
// POSTGRES_USER creates the superuser under the name the panel chose rather than leaving it
// as "postgres", so `database_engine.admin_username` is the truth and not a label. The port
// and the connection ceiling are server arguments rather than environment variables, because
// the image passes everything after the entrypoint straight to `postgres`.
func (postgres) container(engine spec.Engine) containerPlan {
	port := listenPort(engine, postgresPort)
	return containerPlan{
		Env: []string{
			"POSTGRES_USER=" + engine.AdminUsername,
			"POSTGRES_PASSWORD=" + engine.AdminPassword,
			"POSTGRES_DB=postgres",
			"PGDATA=" + postgresData,
			// Initialise with UTF-8 and a deterministic collation. A server initialised with
			// whatever locale the image's base happened to carry is a server whose indexes
			// sort differently from the next node's, which makes a restore onto another
			// machine look like corruption. C is also the only locale under which a database
			// can later be created in any encoding.
			"POSTGRES_INITDB_ARGS=--encoding=UTF8 --locale=C",
		},
		Cmd: []string{
			"postgres",
			"-c", "port=" + portArgument(port),
			"-c", "max_connections=" + strconv.FormatInt(int64(maxConnections(engine)), 10),
			// The customer's own connections arrive over the tenant network, so the server
			// has to listen on more than its socket. That is still not the public address:
			// the container sits on tenant bridges and no port is published to the host.
			"-c", "listen_addresses=*",
		},
		DataMount: postgresMount,
		Port:      port,
	}
}

// psql builds an invocation of the client.
func (postgres) psql(engine spec.Engine, database string, arguments ...string) []string {
	argv := []string{
		"psql",
		"--no-psqlrc",
		"-U", engine.AdminUsername,
		"-h", postgresSocket,
		"-p", portArgument(listenPort(engine, postgresPort)),
		"-d", database,
		"-v", "ON_ERROR_STOP=1",
		"-q",
	}
	return append(argv, arguments...)
}

// environment carries the administrative password where libpq looks for it, which is not a
// command line. The connect timeout is set only for the readiness probe: everywhere else a
// slow server should be waited for rather than reported as absent.
func (postgres) environment(engine spec.Engine, connectTimeout int) []string {
	env := []string{"PGPASSWORD=" + engine.AdminPassword}
	if connectTimeout > 0 {
		env = append(env, "PGCONNECT_TIMEOUT="+strconv.Itoa(connectTimeout))
	}
	return env
}

// query is a read, with tab-separated, headerless, unaligned output.
func (p postgres) query(engine spec.Engine, database, sql string) command {
	return command{
		Argv:    p.psql(engine, database, "-t", "-A", "-F", "\t", "-c", sql),
		Env:     p.environment(engine, 0),
		Secrets: []string{engine.AdminPassword},
		Purpose: "ask the server: " + sql,
	}
}

// script is a write, fed on standard input because it may carry a password.
//
// Every secret that went into the statement is listed on the command, so that psql quoting
// the failing line back at us cannot put a customer's password into an error the panel
// records.
func (p postgres) script(engine spec.Engine, purpose, sql string, secrets ...string) command {
	return command{
		Argv:    p.psql(engine, "postgres", "-f", "-"),
		Env:     p.environment(engine, 0),
		SQL:     postgresPrologue + sql,
		Secrets: append([]string{engine.AdminPassword}, secrets...),
		Purpose: purpose,
	}
}

func (p postgres) probe(engine spec.Engine) command {
	return command{
		Argv:    p.psql(engine, "postgres", "-t", "-A", "-c", "SELECT 1"),
		Env:     p.environment(engine, probeConnectSeconds),
		Secrets: []string{engine.AdminPassword},
		Purpose: "ask whether the server is accepting connections",
	}
}

func (p postgres) version(ctx context.Context, execute run, engine spec.Engine) (string, error) {
	output, err := execute(ctx, p.query(engine, "postgres",
		"SELECT split_part(current_setting('server_version'), ' ', 1)"))
	if err != nil {
		return "", err
	}
	number := firstField(output)
	if number == "" {
		return "", fmt.Errorf("dbengine: the server did not say which version it is")
	}
	return "PostgreSQL " + number, nil
}

// inventory is one query: name, owner and size for every database that can be connected to.
//
// pg_database_size is the server's own accounting rather than a walk of a directory, which on
// a shared server would measure every customer at once. The owner is what tells a database
// this node created for a grant from one an operator made by hand, which is the whole of the
// collision check in provision.go.
func (p postgres) inventory(ctx context.Context, execute run, engine spec.Engine) ([]databaseFact, error) {
	output, err := execute(ctx, p.query(engine, "postgres", strings.Join([]string{
		"SELECT d.datname,",
		"pg_catalog.pg_get_userbyid(d.datdba),",
		"pg_catalog.pg_database_size(d.datname)",
		"FROM pg_catalog.pg_database d",
		"WHERE d.datallowconn AND NOT d.datistemplate",
		"ORDER BY 1",
	}, " ")))
	if err != nil {
		return nil, err
	}

	facts := make([]databaseFact, 0, 8)
	for _, row := range rows(output) {
		if len(row) < 3 {
			return nil, fmt.Errorf("dbengine: the server answered the inventory query with %d "+
				"columns instead of three", len(row))
		}
		name := strings.TrimSpace(row[0])
		if name == "" || name == "postgres" {
			continue
		}
		facts = append(facts, databaseFact{
			Name:      name,
			Owner:     strings.TrimSpace(row[1]),
			SizeBytes: parseBytes(row[2]),
		})
	}
	return facts, nil
}

// harden closes the door the image leaves open: PUBLIC may connect to the administrative
// database, where pg_catalog lists every other customer's database and role name. Idempotent,
// so it is run after every start rather than remembered somewhere that could be wrong.
func (p postgres) harden(engine spec.Engine) command {
	return p.script(engine, "close the administrative database to everyone but the administrator",
		"REVOKE ALL ON DATABASE postgres FROM PUBLIC;\n"+
			"REVOKE ALL ON DATABASE template1 FROM PUBLIC;\n")
}

// ensureDatabase creates an empty database, for a restore into a name that does not exist
// yet - which is what a dry run is.
func (p postgres) ensureDatabase(engine spec.Engine, name, owner string) (command, error) {
	if err := checkDatabaseName(name); err != nil {
		return command{}, err
	}
	clause := ""
	if owner != "" {
		if err := checkUsername(owner); err != nil {
			return command{}, err
		}
		clause = " OWNER " + quotePostgres(owner)
	}
	return p.script(engine, "create the database "+name,
		createDatabaseIfMissing(name, "CREATE DATABASE "+quotePostgres(name)+clause)), nil
}

// resetDatabase empties a database that is about to be restored over.
//
// The open sessions go first, because DROP DATABASE waits for the last connection rather than
// taking it away - a customer's own idle pool would otherwise hold the restore until its
// deadline. The login is left exactly as it was: dropping it here would leave nothing able to
// own what the restore is about to create, and the node cannot make it again because the
// password belongs to the panel.
func (p postgres) resetDatabase(engine spec.Engine, name, owner string) (command, error) {
	if err := checkDatabaseName(name); err != nil {
		return command{}, err
	}
	if owner != "" {
		if err := checkUsername(owner); err != nil {
			return command{}, err
		}
	}
	return p.script(engine, "empty the database "+name+" before restoring into it",
		"SELECT pg_catalog.pg_terminate_backend(pid) FROM pg_catalog.pg_stat_activity"+
			" WHERE datname = "+literalPostgres(name)+
			" AND pid <> pg_catalog.pg_backend_pid();\n"+
			"DROP DATABASE IF EXISTS "+quotePostgres(name)+";\n"+
			createPostgresDatabase(name, owner, "")), nil
}

// dump writes the custom format: compressed, and loadable into a database with a different
// name, which is what a dry-run restore needs.
func (p postgres) dump(engine spec.Engine, databaseName, containerPath string) (command, error) {
	if err := checkDatabaseName(databaseName); err != nil {
		return command{}, err
	}
	return command{
		Argv: []string{
			"pg_dump",
			"-U", engine.AdminUsername,
			"-h", postgresSocket,
			"-p", portArgument(listenPort(engine, postgresPort)),
			"-d", databaseName,
			"--format=custom",
			"--compress=6",
			"--file=" + containerPath,
		},
		Env:     p.environment(engine, 0),
		Secrets: []string{engine.AdminPassword},
		Purpose: "dump the database " + databaseName,
	}, nil
}

// restore loads one back in a single transaction, so a failure halfway leaves the database as
// it was rather than as half of two.
//
// Ownership in the dump is deliberately kept. The customer's role is on this same server, so
// restoring as the administrator and letting the dump's own ALTER ... OWNER TO statements run
// is what leaves the customer able to write to their own tables afterwards; --no-owner would
// hand every table to the administrator and produce a database its owner cannot use.
func (p postgres) restore(engine spec.Engine, databaseName, containerPath string) (command, error) {
	if err := checkDatabaseName(databaseName); err != nil {
		return command{}, err
	}
	return command{
		Argv: []string{
			"pg_restore",
			"-U", engine.AdminUsername,
			"-h", postgresSocket,
			"-p", portArgument(listenPort(engine, postgresPort)),
			"-d", databaseName,
			"--single-transaction",
			containerPath,
		},
		Env:     p.environment(engine, 0),
		Secrets: []string{engine.AdminPassword},
		Purpose: "restore the database " + databaseName,
	}, nil
}
