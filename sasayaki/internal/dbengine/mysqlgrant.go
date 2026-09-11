package dbengine

import (
	"strconv"
	"strings"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
)

// The SQL that gives a customer a database on a shared MySQL, and the SQL that takes it away.
//
// MySQL has no role attributes to switch off the way PostgreSQL does; what it has instead is
// the scope of a grant, and the scope is the whole of the isolation here:
//
//	GRANT ALL PRIVILEGES ON `theirs`.* TO ...
//
// `theirs`.* and never *.*. The schema-level form covers everything a customer needs inside
// their own database - tables, views, routines, temporary tables - and covers none of the
// global privileges: no SUPER, no FILE (which reads any file the server can), no PROCESS
// (which lists every other customer's running query, including the literals in it), no
// RELOAD, no SHUTDOWN, no CREATE USER. And no WITH GRANT OPTION, because a customer who can
// grant can hand their access to an account the panel has never heard of.
//
// The account is also given a connection ceiling of its own, for the same reason the
// PostgreSQL one is: the first customer whose pool leaks would otherwise take every slot on a
// shared server and everybody else sees "too many connections".

// provision creates the database and the login that may use it.
//
// IF NOT EXISTS throughout, because a command the panel repeated after a dropped stream must
// not fail on its second attempt. The account is created locked and unlocked by the ALTER
// that sets its password, so there is no instant in which it exists with no password on a
// server every tenant on the node can reach.
func (m mysql) provision(engine spec.Engine, grant spec.Grant, password string) (command, error) {
	if err := checkGrant(grant); err != nil {
		return command{}, err
	}
	if err := checkPassword(password); err != nil {
		return command{}, err
	}

	var sql strings.Builder
	sql.WriteString("CREATE DATABASE IF NOT EXISTS " + quoteMySQL(grant.DatabaseName) +
		mysqlCharacterSet(grant) + ";\n")
	sql.WriteString(createMySQLUser(grant.Username))
	sql.WriteString("ALTER USER " + mysqlAccount(grant.Username) +
		" IDENTIFIED BY " + literalMySQL(password) +
		" WITH MAX_USER_CONNECTIONS " + strconv.FormatInt(int64(roleConnections(engine)), 10) +
		" ACCOUNT UNLOCK;\n")
	sql.WriteString(grantMySQLSchema(grant.DatabaseName, grant.Username))
	sql.WriteString("FLUSH PRIVILEGES;\n")

	return m.script(engine, "create the database "+grant.DatabaseName+" and its login",
		sql.String(), password), nil
}

// adopt rebuilds a grant whose server lost its disk.
//
// The login stays locked. An account recreated with no password is an account anybody on the
// server can use, and one recreated with a password the node invented is one the panel can
// neither show the customer nor put in a backup job - so the caller reports last_error and
// the rotation the panel sends back is what turns it on.
func (m mysql) adopt(engine spec.Engine, grant spec.Grant) (command, error) {
	if err := checkGrant(grant); err != nil {
		return command{}, err
	}

	var sql strings.Builder
	sql.WriteString("CREATE DATABASE IF NOT EXISTS " + quoteMySQL(grant.DatabaseName) +
		mysqlCharacterSet(grant) + ";\n")
	sql.WriteString(createMySQLUser(grant.Username))
	sql.WriteString(grantMySQLSchema(grant.DatabaseName, grant.Username))
	sql.WriteString("FLUSH PRIVILEGES;\n")

	return m.script(engine, "recreate the database "+grant.DatabaseName+" and its locked login",
		sql.String()), nil
}

// rotate changes one login's password and switches it back on.
func (m mysql) rotate(engine spec.Engine, username, password string) (command, error) {
	if err := checkUsername(username); err != nil {
		return command{}, err
	}
	if err := checkPassword(password); err != nil {
		return command{}, err
	}
	return m.script(engine, "change the password of "+username,
		createMySQLUser(username)+
			"ALTER USER "+mysqlAccount(username)+" IDENTIFIED BY "+literalMySQL(password)+
			" ACCOUNT UNLOCK;\n"+
			"FLUSH PRIVILEGES;\n", password), nil
}

// revoke removes the login and leaves the data.
//
// DROP USER here where PostgreSQL gets NOLOGIN, and the difference is not an inconsistency:
// MySQL has no ownership to orphan, so removing the account is the clean operation, and it
// closes existing sessions as well - a MySQL connection whose account has been dropped is
// terminated at its next statement.
func (m mysql) revoke(engine spec.Engine, _, username string) (command, error) {
	if err := checkUsername(username); err != nil {
		return command{}, err
	}
	return m.script(engine, "remove the login "+username,
		"DROP USER IF EXISTS "+mysqlAccount(username)+";\n"+
			"FLUSH PRIVILEGES;\n"), nil
}

// destroy removes the database and the login.
func (m mysql) destroy(engine spec.Engine, databaseName, username string) (command, error) {
	if err := checkDatabaseName(databaseName); err != nil {
		return command{}, err
	}
	if err := checkUsername(username); err != nil {
		return command{}, err
	}
	return m.script(engine, "drop the database "+databaseName+" and its login",
		"DROP DATABASE IF EXISTS "+quoteMySQL(databaseName)+";\n"+
			"DROP USER IF EXISTS "+mysqlAccount(username)+";\n"+
			"FLUSH PRIVILEGES;\n"), nil
}

// mysqlAccount is one login as MySQL names it: `'name'@'host'`.
func mysqlAccount(username string) string {
	return literalMySQL(username) + "@'" + mysqlHost + "'"
}

// createMySQLUser is the idempotent half of every account statement. Locked on creation, so
// the account never exists with an empty password even for the length of one script.
func createMySQLUser(username string) string {
	return "CREATE USER IF NOT EXISTS " + mysqlAccount(username) + " ACCOUNT LOCK;\n"
}

// grantMySQLSchema is the one grant a customer gets, and the scope is the point.
func grantMySQLSchema(databaseName, username string) string {
	return "GRANT ALL PRIVILEGES ON " + quoteMySQL(databaseName) + ".* TO " +
		mysqlAccount(username) + ";\n"
}

// mysqlCharacterSet is the encoding clause, when the panel asked for one. Empty means the
// server default, which the container sets to utf8mb4.
func mysqlCharacterSet(grant spec.Grant) string {
	if grant.Encoding == "" {
		return ""
	}
	return " CHARACTER SET " + literalMySQL(grant.Encoding)
}
