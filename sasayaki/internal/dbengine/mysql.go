package dbengine

import (
	"context"
	"fmt"
	"strconv"
	"strings"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
)

// MySQL, as this node runs it: the container, the client and the reads.
//
// The SQL that makes and unmakes a customer's account is next door in mysqlgrant.go.
//
// # The administrative login
//
// The official image creates exactly one administrative account, `root`, from
// MYSQL_ROOT_PASSWORD - there is no environment variable that names it something else, the
// way POSTGRES_USER does. So this node authenticates as root over the container's unix
// socket, and `harden` additionally creates `database_engine.admin_username` as a second
// administrative account when the panel chose something other than root, so that the column
// describes an account that exists rather than an intention.
//
// MYSQL_ROOT_HOST is pinned to localhost. Left alone the image defaults it to '%', which
// creates an administrative login reachable from every tenant network the server is joined
// to - the single worst default in the image, and one that is invisible until somebody
// guesses the password.
//
// # sql_mode
//
// Every script sets NO_BACKSLASH_ESCAPES for its own session. Whether a backslash inside a
// string literal is an escape character depends on a server variable that a customer's own
// connection can change, so there is no single encoding of a password that is correct under
// both settings. Fixing the mode for the length of the script is cheaper than guessing, and
// literalMySQL is written against it.
//
// # On the length of this file
//
// It is over three hundred lines, and the split that would fix that is not one worth making.
// What is here is one boundary - everything this node knows about talking to MySQL that is not
// the grant SQL - and the two halves anybody would be tempted to separate, "how the container
// is configured" and "what the client is asked", are the two halves that have to agree about
// the port, the socket and the administrative account. postgres.go is the same shape and stays
// under the limit only because PostgreSQL needs one query where MySQL needs two.

type mysql struct{}

const (
	// mysqlSocket is where the official image puts its unix socket.
	mysqlSocket = "/var/run/mysqld/mysqld.sock"
	// mysqlMount is the data directory inside the container.
	mysqlMount = "/var/lib/mysql"
	// mysqlPort is the port the server uses when the panel sent none.
	mysqlPort uint32 = 3306
	// mysqlAdmin is the account the image guarantees exists after initialisation.
	mysqlAdmin = "root"
	// mysqlHost is the host part of every customer's login. Their containers reach the
	// server from a tenant bridge whose addresses change whenever a container is recreated,
	// so pinning it to anything narrower would lock them out at an unpredictable moment.
	mysqlHost = "%"
	// mysqlPrologue is the first line of every script.
	mysqlPrologue = "SET SESSION sql_mode = 'NO_BACKSLASH_ESCAPES';\n"
)

// mysqlSystemSchemas are the server's own, which are never a customer's database.
var mysqlSystemSchemas = map[string]bool{
	"information_schema": true,
	"mysql":              true,
	"performance_schema": true,
	"sys":                true,
}

func (mysql) kind() spec.EngineKind { return spec.EngineMySQL }

func (mysql) dumpExtension() string { return ".sql" }

func (mysql) container(engine spec.Engine) containerPlan {
	port := listenPort(engine, mysqlPort)
	return containerPlan{
		Env: []string{
			"MYSQL_ROOT_PASSWORD=" + engine.AdminPassword,
			"MYSQL_ROOT_HOST=localhost",
		},
		Cmd: []string{
			"--port=" + portArgument(port),
			"--max-connections=" + strconv.FormatInt(int64(maxConnections(engine)), 10),
			// The customer's connections arrive over the tenant network. No port is
			// published to the host, so this is not the public address.
			"--bind-address=0.0.0.0",
			// utf8mb4 or a customer's first emoji is a truncated row. utf8 in MySQL is
			// three bytes and has never been UTF-8.
			"--character-set-server=utf8mb4",
			// Host matching by address only. A reverse lookup on every connection is both a
			// stall when the resolver is slow and a way for whoever controls the reverse
			// zone to influence which grant matches.
			"--skip-name-resolve",
		},
		DataMount: mysqlMount,
		Port:      port,
	}
}

// client builds an invocation of the mysql client.
func (mysql) client(arguments ...string) []string {
	argv := []string{
		"mysql",
		"--protocol=socket",
		"--socket=" + mysqlSocket,
		"-u", mysqlAdmin,
		"--batch",
		"--skip-column-names",
		"--default-character-set=utf8mb4",
	}
	return append(argv, arguments...)
}

// environment carries the administrative password where the client looks for it, which is not
// a command line: `-p<password>` is world-readable through `ps` for as long as the statement
// runs.
func (mysql) environment(engine spec.Engine, connectTimeout int) []string {
	env := []string{"MYSQL_PWD=" + engine.AdminPassword}
	if connectTimeout > 0 {
		env = append(env, "MYSQL_CONNECT_TIMEOUT="+strconv.Itoa(connectTimeout))
	}
	return env
}

// query is a read. Batch mode already writes headerless, tab-separated rows.
func (m mysql) query(engine spec.Engine, sql string) command {
	return command{
		Argv:    m.client("-e", sql),
		Env:     m.environment(engine, 0),
		Secrets: []string{engine.AdminPassword},
		Purpose: "ask the server: " + sql,
	}
}

// script is a write, fed on standard input because it may carry a password.
//
// Every secret that went into the statement is listed on the command, so that a client
// quoting the failing statement back at us cannot put a customer's password into an error the
// panel records.
func (m mysql) script(engine spec.Engine, purpose, sql string, secrets ...string) command {
	return command{
		Argv:    m.client(),
		Env:     m.environment(engine, 0),
		SQL:     mysqlPrologue + sql,
		Secrets: append([]string{engine.AdminPassword}, secrets...),
		Purpose: purpose,
	}
}

func (m mysql) probe(engine spec.Engine) command {
	return command{
		Argv:    m.client("--connect-timeout="+strconv.Itoa(probeConnectSeconds), "-e", "SELECT 1"),
		Env:     m.environment(engine, probeConnectSeconds),
		Secrets: []string{engine.AdminPassword},
		Purpose: "ask whether the server is accepting connections",
	}
}

func (m mysql) version(ctx context.Context, execute run, engine spec.Engine) (string, error) {
	output, err := execute(ctx, m.query(engine, "SELECT VERSION()"))
	if err != nil {
		return "", err
	}
	version := firstField(output)
	if version == "" {
		return "", fmt.Errorf("dbengine: the server did not say which version it is")
	}
	return version, nil
}

// inventory is two queries, because MySQL has no owner column.
//
// The first is every schema with the space its tables and indexes occupy. The second is who
// holds schema-level privileges, which is the closest thing to ownership the server records
// and is what tells a database this node created for a grant from one an operator made by
// hand. information_schema's size figures are the server's own accounting and lag a little
// behind reality on InnoDB; that is the same number MySQL itself reports and the right one to
// compare a quota against, because the alternative - measuring the data directory - measures
// every customer on the server at once.
func (m mysql) inventory(ctx context.Context, execute run, engine spec.Engine) ([]databaseFact, error) {
	sizes, err := execute(ctx, m.query(engine, strings.Join([]string{
		"SELECT s.SCHEMA_NAME,",
		"IFNULL(SUM(t.DATA_LENGTH + t.INDEX_LENGTH), 0)",
		"FROM information_schema.SCHEMATA s",
		"LEFT JOIN information_schema.TABLES t ON t.TABLE_SCHEMA = s.SCHEMA_NAME",
		"GROUP BY s.SCHEMA_NAME ORDER BY 1",
	}, " ")))
	if err != nil {
		return nil, err
	}

	owners, err := m.owners(ctx, execute, engine)
	if err != nil {
		return nil, err
	}

	facts := make([]databaseFact, 0, 8)
	for _, row := range rows(sizes) {
		if len(row) < 2 {
			return nil, fmt.Errorf("dbengine: the server answered the inventory query with %d "+
				"columns instead of two", len(row))
		}
		name := strings.TrimSpace(row[0])
		if name == "" || mysqlSystemSchemas[name] {
			continue
		}
		facts = append(facts, databaseFact{
			Name:      name,
			Owner:     owners[name],
			SizeBytes: parseBytes(row[1]),
		})
	}
	return facts, nil
}

// owners is which login holds privileges on which schema.
//
// GRANTEE arrives as `'name'@'host'`, so the two SUBSTRING_INDEX calls cut the name out of
// it. A schema with more than one grantee reports none: two logins on one database is not
// something this package creates, and guessing which of them owns it would let a login added
// by hand make a customer's database look like somebody else's.
func (m mysql) owners(ctx context.Context, execute run, engine spec.Engine) (map[string]string, error) {
	output, err := execute(ctx, m.query(engine, strings.Join([]string{
		"SELECT DISTINCT TABLE_SCHEMA,",
		"SUBSTRING_INDEX(SUBSTRING_INDEX(GRANTEE, '''', 2), '''', -1)",
		"FROM information_schema.SCHEMA_PRIVILEGES ORDER BY 1",
	}, " ")))
	if err != nil {
		return nil, err
	}

	found := make(map[string]string)
	ambiguous := make(map[string]bool)
	for _, row := range rows(output) {
		if len(row) < 2 {
			continue
		}
		schema := strings.TrimSpace(row[0])
		grantee := strings.TrimSpace(row[1])
		if schema == "" || grantee == "" || grantee == mysqlAdmin {
			continue
		}
		if existing, seen := found[schema]; seen && existing != grantee {
			ambiguous[schema] = true
			continue
		}
		found[schema] = grantee
	}
	for schema := range ambiguous {
		delete(found, schema)
	}
	return found, nil
}

// harden shuts the remote administrative login the image may have created, and mirrors the
// administrative name the panel recorded.
//
// Idempotent, so it runs after every start rather than being remembered somewhere that could
// disagree with the server.
func (m mysql) harden(engine spec.Engine) command {
	var sql strings.Builder
	// Created by the image whenever MYSQL_ROOT_HOST was left at its default of '%' - which
	// is how a server initialised before this daemon pinned it ends up with an
	// administrative account reachable from every tenant network it is joined to.
	sql.WriteString("DROP USER IF EXISTS " + literalMySQL(mysqlAdmin) + "@'%';\n")

	if engine.AdminUsername != "" && engine.AdminUsername != mysqlAdmin {
		account := literalMySQL(engine.AdminUsername) + "@'localhost'"
		sql.WriteString("CREATE USER IF NOT EXISTS " + account +
			" IDENTIFIED BY " + literalMySQL(engine.AdminPassword) + ";\n")
		sql.WriteString("GRANT ALL PRIVILEGES ON *.* TO " + account + " WITH GRANT OPTION;\n")
	}
	sql.WriteString("FLUSH PRIVILEGES;\n")

	return m.script(engine, "close the remote administrative login", sql.String())
}

// ensureDatabase creates an empty schema, for a restore into a name that does not exist yet.
func (m mysql) ensureDatabase(engine spec.Engine, name, owner string) (command, error) {
	if err := checkDatabaseName(name); err != nil {
		return command{}, err
	}
	sql := "CREATE DATABASE IF NOT EXISTS " + quoteMySQL(name) + ";\n"
	if owner != "" {
		if err := checkUsername(owner); err != nil {
			return command{}, err
		}
		sql += "GRANT ALL PRIVILEGES ON " + quoteMySQL(name) + ".* TO " +
			literalMySQL(owner) + "@'" + mysqlHost + "';\n"
	}
	return m.script(engine, "create the database "+name, sql), nil
}

// resetDatabase empties a database that is about to be restored over, and leaves the login
// alone: dropping the account here would leave nothing able to use what the restore creates,
// and the node cannot make it again because the password belongs to the panel.
func (m mysql) resetDatabase(engine spec.Engine, name, owner string) (command, error) {
	if err := checkDatabaseName(name); err != nil {
		return command{}, err
	}
	sql := "DROP DATABASE IF EXISTS " + quoteMySQL(name) + ";\n" +
		"CREATE DATABASE " + quoteMySQL(name) + ";\n"
	if owner != "" {
		if err := checkUsername(owner); err != nil {
			return command{}, err
		}
		sql += grantMySQLSchema(name, owner) + "FLUSH PRIVILEGES;\n"
	}
	return m.script(engine, "empty the database "+name+" before restoring into it", sql), nil
}

// dump writes plain SQL with no CREATE DATABASE in it, which is what lets the same file be
// restored into a differently named database for a dry run.
func (m mysql) dump(engine spec.Engine, databaseName, containerPath string) (command, error) {
	if err := checkDatabaseName(databaseName); err != nil {
		return command{}, err
	}
	return command{
		Argv: []string{
			"mysqldump",
			"--protocol=socket",
			"--socket=" + mysqlSocket,
			"-u", mysqlAdmin,
			"--default-character-set=utf8mb4",
			// One consistent point in time without locking the customer's tables, which on
			// InnoDB is the difference between a backup and an outage.
			"--single-transaction",
			"--routines",
			"--triggers",
			"--events",
			// Reading tablespace metadata needs a global privilege the dump does not
			// otherwise use, and the information is meaningless on a restore anyway.
			"--no-tablespaces",
			"--result-file=" + containerPath,
			databaseName,
		},
		Env:     m.environment(engine, 0),
		Secrets: []string{engine.AdminPassword},
		Purpose: "dump the database " + databaseName,
	}, nil
}

// restore reads the dump with the client's own `source`, so the file never passes through
// this process's memory.
func (m mysql) restore(engine spec.Engine, databaseName, containerPath string) (command, error) {
	if err := checkDatabaseName(databaseName); err != nil {
		return command{}, err
	}
	return command{
		Argv: m.client(
			"-D", databaseName,
			"-e", "source "+containerPath),
		Env:     m.environment(engine, 0),
		Secrets: []string{engine.AdminPassword},
		Purpose: "restore the database " + databaseName,
	}, nil
}
