package dbengine

import (
	"strings"
	"testing"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
)

// The privileges a customer's login is created with, asserted on the exact SQL.
//
// This is the test that matters most in the package. Everything else can be wrong and produce
// an error somebody notices; this being wrong produces a database that works perfectly and
// hands one customer everything on a server shared with forty others. So it is asserted
// against the statement text rather than against "the command succeeded", and it is written as
// a list of things that must not appear as well as things that must.

func postgresGrant() spec.Grant {
	return spec.Grant{
		ID:           "db-1",
		Engine:       spec.EnginePostgres,
		DatabaseName: "proj_api",
		Username:     "proj_api_user",
		QuotaBytes:   1 << 30,
	}
}

func mysqlGrant() spec.Grant {
	g := postgresGrant()
	g.Engine = spec.EngineMySQL
	return g
}

func postgresSpec() spec.Engine {
	return spec.Engine{
		Kind:           spec.EnginePostgres,
		Image:          "postgres:17.2",
		ListenPort:     5432,
		AdminUsername:  "wisper_admin",
		AdminPassword:  adminPassword,
		DataVolumeID:   postgresInstance,
		MaxConnections: 200,
	}
}

func mysqlSpec() spec.Engine {
	return spec.Engine{
		Kind:           spec.EngineMySQL,
		Image:          "mysql:8.4.3",
		ListenPort:     3306,
		AdminUsername:  "root",
		AdminPassword:  adminPassword,
		DataVolumeID:   mysqlInstance,
		MaxConnections: 200,
	}
}

func TestPostgresLoginIsNeverASuperuser(t *testing.T) {
	statement, err := postgres{}.provision(postgresSpec(), postgresGrant(), "password")
	if err != nil {
		t.Fatalf("build the statement: %v", err)
	}

	for _, required := range []string{
		"NOSUPERUSER",
		"NOCREATEDB",
		"NOCREATEROLE",
		"NOREPLICATION",
		"NOBYPASSRLS",
		`REVOKE ALL ON DATABASE "proj_api" FROM PUBLIC`,
		"REVOKE ALL ON SCHEMA public FROM PUBLIC",
		"CONNECTION LIMIT",
	} {
		if !strings.Contains(statement.SQL, required) {
			t.Fatalf("the login is created without %q:\n%s", required, statement.SQL)
		}
	}

	// The absence list. Every occurrence of one of these attributes has to be the negated
	// form: a single "NO" dropped from the constant would be a customer with the run of the
	// server, and it would still pass the presence check above.
	for _, attribute := range []string{
		"SUPERUSER",
		"CREATEDB",
		"CREATEROLE",
		"REPLICATION",
		"BYPASSRLS",
	} {
		if granted(statement.SQL, attribute) {
			t.Fatalf("the login is granted %s rather than being denied it:\n%s",
				attribute, statement.SQL)
		}
	}
}

// granted reports whether an attribute appears anywhere without its NO prefix.
func granted(sql, attribute string) bool {
	for offset := 0; ; {
		index := strings.Index(sql[offset:], attribute)
		if index < 0 {
			return false
		}
		at := offset + index
		if at < 2 || sql[at-2:at] != "NO" {
			return true
		}
		offset = at + len(attribute)
	}
}

func TestPostgresRotationReappliesTheAttributes(t *testing.T) {
	// A rotation that only set the password would leave an attribute somebody added by hand
	// in place forever, because nothing else ever touches the role.
	statement, err := postgres{}.rotate(postgresSpec(), "proj_api_user", "password")
	if err != nil {
		t.Fatalf("build the statement: %v", err)
	}
	if !strings.Contains(statement.SQL, postgresAttributes) {
		t.Fatalf("a rotation does not reapply the attributes:\n%s", statement.SQL)
	}
	if !strings.Contains(statement.SQL, "WITH LOGIN PASSWORD") {
		t.Fatalf("a rotation does not switch the account back on:\n%s", statement.SQL)
	}
}

func TestMySQLGrantIsScopedToOneSchema(t *testing.T) {
	statement, err := mysql{}.provision(mysqlSpec(), mysqlGrant(), "password")
	if err != nil {
		t.Fatalf("build the statement: %v", err)
	}

	if !strings.Contains(statement.SQL, "GRANT ALL PRIVILEGES ON `proj_api`.* TO 'proj_api_user'@'%'") {
		t.Fatalf("the grant is not scoped to the customer's own schema:\n%s", statement.SQL)
	}
	if !strings.Contains(statement.SQL, "MAX_USER_CONNECTIONS") {
		t.Fatalf("the login has no connection ceiling of its own:\n%s", statement.SQL)
	}

	for _, forbidden := range []string{
		"ON *.*",
		"WITH GRANT OPTION",
		"SUPER",
		"FILE",
		"PROCESS",
	} {
		if strings.Contains(statement.SQL, forbidden) {
			t.Fatalf("the grant contains %q, which is a global privilege:\n%s", forbidden, statement.SQL)
		}
	}
}

func TestMySQLAccountIsNeverPasswordlessEvenForAnInstant(t *testing.T) {
	statement, err := mysql{}.provision(mysqlSpec(), mysqlGrant(), "password")
	if err != nil {
		t.Fatalf("build the statement: %v", err)
	}
	create := strings.Index(statement.SQL, "CREATE USER IF NOT EXISTS")
	unlock := strings.Index(statement.SQL, "ACCOUNT UNLOCK")
	if create < 0 || unlock < 0 || create > unlock {
		t.Fatalf("the account is not created locked and unlocked afterwards:\n%s", statement.SQL)
	}
	if !strings.Contains(statement.SQL[create:unlock], "ACCOUNT LOCK") {
		t.Fatalf("the account is created without ACCOUNT LOCK, so it exists with no password "+
			"until the next statement:\n%s", statement.SQL)
	}
}

func TestMySQLHardeningClosesTheRemoteAdministrativeLogin(t *testing.T) {
	statement := mysql{}.harden(mysqlSpec())
	if !strings.Contains(statement.SQL, "DROP USER IF EXISTS 'root'@'%'") {
		t.Fatalf("hardening leaves the image's remote root in place:\n%s", statement.SQL)
	}
}

func TestPostgresHardeningClosesTheAdministrativeDatabase(t *testing.T) {
	statement := postgres{}.harden(postgresSpec())
	for _, required := range []string{
		"REVOKE ALL ON DATABASE postgres FROM PUBLIC",
		"REVOKE ALL ON DATABASE template1 FROM PUBLIC",
	} {
		if !strings.Contains(statement.SQL, required) {
			t.Fatalf("hardening does not %q:\n%s", required, statement.SQL)
		}
	}
}

func TestAdoptLeavesTheLoginUnusable(t *testing.T) {
	postgresAdopt, err := postgres{}.adopt(postgresSpec(), postgresGrant())
	if err != nil {
		t.Fatalf("build the statement: %v", err)
	}
	if strings.Contains(postgresAdopt.SQL, "PASSWORD") {
		t.Fatalf("a recreated PostgreSQL login was given a password the panel does not know:\n%s",
			postgresAdopt.SQL)
	}
	if !strings.Contains(postgresAdopt.SQL, "WITH NOLOGIN") {
		t.Fatalf("a recreated PostgreSQL login can be used:\n%s", postgresAdopt.SQL)
	}

	mysqlAdopt, err := mysql{}.adopt(mysqlSpec(), mysqlGrant())
	if err != nil {
		t.Fatalf("build the statement: %v", err)
	}
	if strings.Contains(mysqlAdopt.SQL, "IDENTIFIED BY") {
		t.Fatalf("a recreated MySQL login was given a password:\n%s", mysqlAdopt.SQL)
	}
	if !strings.Contains(mysqlAdopt.SQL, "ACCOUNT LOCK") {
		t.Fatalf("a recreated MySQL login is not locked:\n%s", mysqlAdopt.SQL)
	}
}

func TestNoPasswordEverReachesACommandLine(t *testing.T) {
	// argv is readable by every user on the machine through `ps`, which is the same reason the
	// enrolment token is refused on a command line.
	const password = "the-customer-password"

	built := []command{}
	postgresProvision, err := postgres{}.provision(postgresSpec(), postgresGrant(), password)
	if err != nil {
		t.Fatalf("build the statement: %v", err)
	}
	built = append(built, postgresProvision)

	mysqlProvision, err := mysql{}.provision(mysqlSpec(), mysqlGrant(), password)
	if err != nil {
		t.Fatalf("build the statement: %v", err)
	}
	built = append(built, mysqlProvision)

	postgresRotate, err := postgres{}.rotate(postgresSpec(), "proj_api_user", password)
	if err != nil {
		t.Fatalf("build the statement: %v", err)
	}
	built = append(built, postgresRotate)

	mysqlRotate, err := mysql{}.rotate(mysqlSpec(), "proj_api_user", password)
	if err != nil {
		t.Fatalf("build the statement: %v", err)
	}
	built = append(built, mysqlRotate)

	for _, statement := range built {
		for _, argument := range statement.Argv {
			if strings.Contains(argument, password) {
				t.Fatalf("a password is in argv: %v", statement.Argv)
			}
			if strings.Contains(argument, adminPassword) {
				t.Fatalf("the administrative password is in argv: %v", statement.Argv)
			}
		}
		if !strings.Contains(statement.SQL, password) {
			t.Fatalf("the password did not reach the statement at all: %s", statement.SQL)
		}
		if !containsSecret(statement.Secrets, password) {
			t.Fatalf("the password is not listed as a secret, so a failure would print it: %v",
				statement.Purpose)
		}
	}
}

func containsSecret(secrets []string, wanted string) bool {
	for _, secret := range secrets {
		if secret == wanted {
			return true
		}
	}
	return false
}
