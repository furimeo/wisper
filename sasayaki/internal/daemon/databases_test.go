package daemon

import (
	"bytes"
	"context"
	"strings"
	"testing"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Turning a backup's vocabulary into the database layer's.
//
// The one that carries a decision rather than a translation is Replace: it is set on every
// real restore because pg_restore creates and does not drop, so a replay over a live
// PostgreSQL database without it fails on the first relation that already exists. The
// caller has taken a rollback dump before it gets here, which is what makes dropping safe.

func TestDatabaseDumpsTranslatesTheEngine(t *testing.T) {
	cases := []struct {
		name   string
		engine wisperpb.DatabaseEngine
		kind   spec.EngineKind
		fails  bool
	}{
		{
			name:   "postgres",
			engine: wisperpb.DatabaseEngine_DATABASE_ENGINE_POSTGRES,
			kind:   spec.EnginePostgres,
		},
		{
			name:   "mysql",
			engine: wisperpb.DatabaseEngine_DATABASE_ENGINE_MYSQL,
			kind:   spec.EngineMySQL,
		},
		{
			name: "an engine this binary does not implement is refused rather than guessed at",
			// Starting the wrong client against a server produces a dump nothing can read
			// back, which is the one failure a backup system must not have.
			engine: wisperpb.DatabaseEngine_DATABASE_ENGINE_UNSPECIFIED,
			fails:  true,
		},
	}

	for _, test := range cases {
		t.Run(test.name, func(t *testing.T) {
			engines := &fakeEngines{}
			dumps := databaseDumps{engines: engines}

			var out bytes.Buffer
			err := dumps.DumpDatabase(context.Background(), test.engine, "customer_db", &out)
			if test.fails {
				if err == nil {
					t.Fatal("an unknown engine was dumped anyway")
				}
				if len(engines.dumped) != 0 {
					t.Errorf("the database layer was asked for %v, want nothing", engines.dumped)
				}
				return
			}
			if err != nil {
				t.Fatalf("dump the database: %v", err)
			}
			if len(engines.dumped) != 1 {
				t.Fatalf("the database layer was asked %d times, want once", len(engines.dumped))
			}
			if engines.dumped[0].Engine != test.kind {
				t.Errorf("engine = %s, want %s", engines.dumped[0].Engine, test.kind)
			}
			if engines.dumped[0].DatabaseName != "customer_db" {
				t.Errorf("database = %q, want customer_db", engines.dumped[0].DatabaseName)
			}
			if out.Len() == 0 {
				t.Error("nothing reached the writer the backup handed over")
			}
		})
	}
}

func TestRestoreDatabaseEmptiesWhatItReplacesFirst(t *testing.T) {
	engines := &fakeEngines{}
	dumps := databaseDumps{engines: engines}

	err := dumps.RestoreDatabase(context.Background(),
		wisperpb.DatabaseEngine_DATABASE_ENGINE_POSTGRES, "customer_db",
		strings.NewReader("a dump"))
	if err != nil {
		t.Fatalf("restore the database: %v", err)
	}

	if len(engines.restored) != 1 {
		t.Fatalf("the database layer was asked %d times, want once", len(engines.restored))
	}
	if !engines.restored[0].Replace {
		t.Error("Replace is not set: a PostgreSQL custom-format archive replayed over a live " +
			"database without dropping it first fails on the first relation that already exists")
	}
	if engines.restored[0].DatabaseName != "customer_db" {
		t.Errorf("database = %q, want customer_db", engines.restored[0].DatabaseName)
	}
}

func TestCreateEmptyDatabaseIsARehearsalWithNoUsableLogin(t *testing.T) {
	engines := &fakeEngines{}
	dumps := databaseDumps{engines: engines}

	err := dumps.CreateEmptyDatabase(context.Background(),
		wisperpb.DatabaseEngine_DATABASE_ENGINE_MYSQL, "customer_db_r7f21a")
	if err != nil {
		t.Fatalf("create the rehearsal database: %v", err)
	}

	if len(engines.provisioned) != 1 {
		t.Fatalf("the database layer was asked %d times, want once", len(engines.provisioned))
	}
	request := engines.provisioned[0]
	if got := request.GetGrant().GetDatabaseName(); got != "customer_db_r7f21a" {
		t.Errorf("database = %q, want customer_db_r7f21a", got)
	}
	if got := request.GetGrant().GetId(); !strings.HasPrefix(got, "rehearsal:") {
		t.Errorf("grant id = %q, want it marked as a rehearsal so a real grant cannot be "+
			"mistaken for it", got)
	}
	if request.GetPassword() == "" {
		t.Error("no password was generated: an account with no password on a shared server is " +
			"an account every other customer on it can use")
	}
}

func TestCreateEmptyDatabaseRefusesAnUnknownEngine(t *testing.T) {
	engines := &fakeEngines{}
	dumps := databaseDumps{engines: engines}

	err := dumps.CreateEmptyDatabase(context.Background(),
		wisperpb.DatabaseEngine_DATABASE_ENGINE_UNSPECIFIED, "customer_db_r7f21a")
	if err == nil {
		t.Fatal("a rehearsal was started against an engine this node does not run")
	}
	if len(engines.provisioned) != 0 {
		t.Errorf("the database layer was asked for %d databases, want none", len(engines.provisioned))
	}
}

// The login a rehearsal is created under has to fit what both engines accept: MySQL
// truncates a user past 32 characters, and a login that silently becomes a different login
// is a database nobody can connect to and an operator who cannot see why.
func TestRehearsalLoginFitsTheEngines(t *testing.T) {
	cases := []struct {
		name     string
		database string
		want     string
	}{
		{
			name:     "a short name is used as it is, so an operator can see what it belongs to",
			database: "customer_db_r7f21a",
			want:     "customer_db_r7f21a",
		},
		{
			name:     "a long one is cut to what MySQL will keep",
			database: strings.Repeat("a", 63),
			want:     strings.Repeat("a", maxRehearsalLogin),
		},
	}

	for _, test := range cases {
		t.Run(test.name, func(t *testing.T) {
			got := rehearsalLogin(test.database)
			if got != test.want {
				t.Errorf("rehearsalLogin(%d characters) = %q, want %q", len(test.database), got, test.want)
			}
			if len(got) > maxRehearsalLogin {
				t.Errorf("the login is %d characters, which MySQL would truncate", len(got))
			}
		})
	}
}

// Two rehearsals must not be handed the same secret, and no secret is ever kept: the login
// exists so the database has an owner, not so anybody can connect as it.
func TestUnusablePasswordIsDifferentEveryTime(t *testing.T) {
	first, err := unusablePassword()
	if err != nil {
		t.Fatalf("generate a password: %v", err)
	}
	second, err := unusablePassword()
	if err != nil {
		t.Fatalf("generate a second password: %v", err)
	}

	if first == second {
		t.Error("two rehearsals were given the same password")
	}
	if len(first) < 32 {
		t.Errorf("the password is %d characters, which is guessable", len(first))
	}
	if strings.ContainsAny(first, "\x00\n\r") {
		t.Error("the password contains a byte neither engine's client can carry in a statement")
	}
}
