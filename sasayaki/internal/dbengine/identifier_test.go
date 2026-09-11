package dbengine

import (
	"strings"
	"testing"
)

// The check that stands between a name from the panel and a SQL statement.
//
// Neither engine has a bind parameter for an identifier, so this is the whole attack surface
// of the package and it gets a test per rule rather than one that walks a table and asserts
// "no error".

func TestDatabaseNamesArePreciselyWhatThePanelAllows(t *testing.T) {
	// The panel's own constraint is `^[a-z][a-z0-9_]{2,62}$` (V25__managed_database.sql), and
	// this is the node's copy of it. They have to agree: a name the panel accepts and the node
	// refuses is a database a customer created that never appears.
	accepted := []string{
		"abc",
		"proj_api",
		"a_1",
		strings.Repeat("a", 63),
	}
	for _, name := range accepted {
		if err := checkDatabaseName(name); err != nil {
			t.Fatalf("the panel would accept %q and this node refused it: %v", name, err)
		}
	}

	refused := map[string]string{
		"":                          "empty",
		"ab":                        "too short",
		strings.Repeat("a", 64):     "too long",
		"1abc":                      "starts with a digit",
		"_abc":                      "starts with an underscore",
		"ProjApi":                   "upper case",
		"proj-api":                  "a dash",
		"proj api":                  "a space",
		`proj"api`:                  "a double quote",
		"proj`api":                  "a backtick",
		"proj'api":                  "a single quote",
		"proj;DROP DATABASE x":      "a statement separator",
		"proj\\api":                 "a backslash",
		"proj\x00api":               "a null byte",
		"../../etc":                 "a path",
		"proj\nCREATE ROLE evil":    "a line break",
		"prøj":                      "a character outside ASCII",
		"proj/**/api":               "a comment",
		"proj_api--":                "a comment marker",
		"proj_api\tSELECT":          "a tab",
		"proj_api%":                 "a wildcard",
		"proj_api@localhost":        "an account separator",
		"proj_api.public":           "a qualified name",
		"proj_api,other":            "a list",
		"proj_api)":                 "a bracket",
		"proj_api=1":                "an assignment",
		"proj_api\r\nDROP SCHEMA x": "a carriage return",
	}
	for name, why := range refused {
		if err := checkDatabaseName(name); err == nil {
			t.Fatalf("%q (%s) was accepted as a database name", name, why)
		}
	}
}

func TestUsernamesAreShorterThanDatabaseNames(t *testing.T) {
	// MySQL truncates a user past 32 characters, and a login that silently becomes a different
	// login is a customer who cannot connect and an operator who cannot see why.
	if err := checkUsername(strings.Repeat("a", 31)); err != nil {
		t.Fatalf("a 31-character username was refused: %v", err)
	}
	if err := checkUsername(strings.Repeat("a", 32)); err == nil {
		t.Fatal("a 32-character username was accepted, and MySQL would cut it down")
	}
}

func TestPasswordsRefuseOnlyWhatCannotSurviveTheTrip(t *testing.T) {
	// A password is a value, not an identifier: the panel generates it with whatever alphabet
	// it likes and the node escapes rather than constrains it. Three bytes are the exception.
	for _, password := range []string{
		"a-perfectly-ordinary-password",
		`quotes ' and " together`,
		`back\slash`,
		"unicode: ✓ ✗",
		"; DROP DATABASE postgres; --",
	} {
		if err := checkPassword(password); err != nil {
			t.Fatalf("the password %q was refused: %v", password, err)
		}
	}

	for name, password := range map[string]string{
		"empty":             "",
		"a null byte":       "pass\x00word",
		"a line break":      "pass\nword",
		"a carriage return": "pass\rword",
	} {
		if err := checkPassword(password); err == nil {
			t.Fatalf("a password containing %s was accepted", name)
		}
	}
}

func TestQuotingDoublesTheQuoteCharacter(t *testing.T) {
	// The check above makes this unreachable for anything the panel sends, and it is here so
	// that relaxing the check does not silently open an injection.
	if got := quotePostgres(`a"b`); got != `"a""b"` {
		t.Fatalf("PostgreSQL quoting produced %s", got)
	}
	if got := quoteMySQL("a`b"); got != "`a``b`" {
		t.Fatalf("MySQL quoting produced %s", got)
	}
	if got := literalPostgres("it's"); got != `'it''s'` {
		t.Fatalf("a PostgreSQL literal produced %s", got)
	}
	if got := literalMySQL("it's"); got != `'it''s'` {
		t.Fatalf("a MySQL literal produced %s", got)
	}
	// The backslash is left alone, which is correct only because mysqlPrologue sets
	// NO_BACKSLASH_ESCAPES for the session.
	if got := literalMySQL(`a\b`); got != `'a\b'` {
		t.Fatalf("a MySQL literal escaped a backslash: %s", got)
	}
	if !strings.Contains(mysqlPrologue, "NO_BACKSLASH_ESCAPES") {
		t.Fatal("MySQL literals assume NO_BACKSLASH_ESCAPES and the prologue no longer sets it")
	}
	if !strings.Contains(postgresPrologue, "standard_conforming_strings = on") {
		t.Fatal("PostgreSQL literals assume standard_conforming_strings and the prologue no " +
			"longer sets it")
	}
}

func TestEncodingsAreOneWord(t *testing.T) {
	for _, encoding := range []string{"", "UTF8", "utf8mb4", "LATIN1", "SQL_ASCII"} {
		if err := checkEncoding(encoding); err != nil {
			t.Fatalf("the encoding %q was refused: %v", encoding, err)
		}
	}
	for _, encoding := range []string{"UTF8'; DROP DATABASE x; --", "UTF 8", "UTF8;"} {
		if err := checkEncoding(encoding); err == nil {
			t.Fatalf("the encoding %q was accepted", encoding)
		}
	}
}

func TestInstanceIdsCannotClimbOutOfTheStateDirectory(t *testing.T) {
	for _, id := range []string{"", "..", "../../etc", ".hidden", "a/b", `a\b`, strings.Repeat("a", 65)} {
		if err := checkInstanceID(id); err == nil {
			t.Fatalf("the instance id %q was accepted, and it is joined onto the state root", id)
		}
	}
	for _, id := range []string{"eng-1", "0b3d5a2e-8f4c-4c9a-9f1e-2b6d8c1a7e30", "engine_1"} {
		if err := checkInstanceID(id); err != nil {
			t.Fatalf("the instance id %q was refused: %v", id, err)
		}
	}
}
