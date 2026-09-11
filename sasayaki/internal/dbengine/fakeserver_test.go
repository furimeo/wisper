package dbengine

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"

	"github.com/furimeo/wisper/sasayaki/internal/runtime"
	"github.com/furimeo/wisper/sasayaki/internal/spec"
)

// A database engine that never runs a database.
//
// It is a real little server rather than a recorder that returns canned strings, and that is
// the point: a fake that only remembers what it was asked proves that a statement was sent,
// while this one proves that the statement *did* something. Provisioning has to make the
// inventory query report a database with the right owner afterwards, a dump has to contain
// what a restore then puts back, and a name that is already taken has to look taken.
//
// What it understands is exactly what this package emits, matched line by line - which is also
// why the regular expressions below are worth reading: each one is a statement the package
// promises to produce, and a change to the SQL that breaks one of them is a change the tests
// notice.

// fakeDatabase is one database on a fake server.
type fakeDatabase struct {
	Owner   string
	Bytes   int64
	Content string
}

// fakeLogin is one account on a fake server.
type fakeLogin struct {
	Password string
	// Locked is NOLOGIN on PostgreSQL and ACCOUNT LOCK on MySQL: the account exists and
	// cannot be used.
	Locked bool
	// Grants is which schemas this login holds privileges on. MySQL only; on PostgreSQL
	// ownership is the database's own field.
	Grants map[string]bool
}

// fakeServer is one database server.
type fakeServer struct {
	// mutex guards everything below. Held for the length of one statement, which is what makes
	// a test able to switch Ready on from another goroutine while a provision is polling.
	mutex sync.Mutex

	Kind spec.EngineKind
	// TransferDir is the host directory the server's container sees at transferMount.
	TransferDir string
	// Version is what it answers a version query with.
	Version string
	// Ready is whether it accepts connections at all. False is a server still initialising.
	Ready bool
	// FailNext is an error the next statement returns, for the paths that have to survive one.
	FailNext error
	// FailScript is an error every write returns, leaving reads alone. Reads happen on the way
	// to a write - the readiness probe, the inventory - so a test that wants the write itself
	// to fail cannot use FailNext.
	FailScript error

	Databases map[string]*fakeDatabase
	Logins    map[string]*fakeLogin

	// Scripts is every script it was fed on standard input, in order. The privilege tests read
	// these.
	Scripts []string
	// Queries is every read it was asked, in order.
	Queries []string
}

// fakeServers is the Commands implementation: it routes a command to the server running in
// the container it names.
type fakeServers struct {
	mutex sync.Mutex

	// byContainer is which server is in which container.
	byContainer map[string]*fakeServer
	// stateDir is where transfer directories are resolved from.
	stateDir string
}

func newFakeServers(stateDir string) *fakeServers {
	return &fakeServers{
		byContainer: make(map[string]*fakeServer),
		stateDir:    stateDir,
	}
}

// register makes a server for a container the fake engine has just created, reading its kind
// and instance id off the labels this package stamped on it.
func (s *fakeServers) register(made *fakeContainer) {
	kind := spec.EngineKind(made.Labels[labelKind])
	paths, err := pathsFor(s.stateDir, made.Labels[labelInstance])
	if err != nil {
		panic("the fake engine was given a container with an unusable instance id: " + err.Error())
	}

	version := "PostgreSQL 17.2"
	if kind == spec.EngineMySQL {
		version = "8.4.3"
	}

	s.mutex.Lock()
	defer s.mutex.Unlock()
	s.byContainer[made.ID] = &fakeServer{
		Kind:        kind,
		TransferDir: paths.Transfer,
		Version:     version,
		Ready:       true,
		Databases:   make(map[string]*fakeDatabase),
		Logins:      make(map[string]*fakeLogin),
	}
}

// only is the single server this fake is running, which is what most tests have.
func (s *fakeServers) only() *fakeServer {
	s.mutex.Lock()
	defer s.mutex.Unlock()
	for _, found := range s.byContainer {
		return found
	}
	return nil
}

// ofKind is the server of one engine kind, for a node running both.
func (s *fakeServers) ofKind(kind spec.EngineKind) *fakeServer {
	s.mutex.Lock()
	defer s.mutex.Unlock()
	for _, found := range s.byContainer {
		if found.Kind == kind {
			return found
		}
	}
	return nil
}

// Run is the Commands implementation.
func (s *fakeServers) Run(_ context.Context, containerID string, options runtime.RunOptions) (runtime.RunResult, error) {
	s.mutex.Lock()
	found := s.byContainer[containerID]
	s.mutex.Unlock()

	if found == nil {
		return runtime.RunResult{}, fmt.Errorf("no server is running in %s", containerID)
	}
	return found.run(options)
}

func (f *fakeServer) run(options runtime.RunOptions) (runtime.RunResult, error) {
	f.mutex.Lock()
	defer f.mutex.Unlock()

	if failure := f.FailNext; failure != nil {
		f.FailNext = nil
		return runtime.RunResult{ExitCode: 1, Stderr: []byte(failure.Error())}, nil
	}
	if !f.Ready {
		return runtime.RunResult{
			ExitCode: 2,
			Stderr:   []byte("could not connect to server: Connection refused"),
		}, nil
	}

	switch program := options.Command[0]; program {
	case "psql", "mysql":
		return f.client(options)
	case "pg_dump", "mysqldump":
		return f.dump(options)
	case "pg_restore":
		return f.loadInto(f.targetOf(options.Command), lastArgument(options.Command))
	default:
		return runtime.RunResult{}, fmt.Errorf("the fake server was asked to run %q", program)
	}
}

// client is psql or mysql: a read when it was given a statement on the command line, a write
// when it was given a script on standard input.
func (f *fakeServer) client(options runtime.RunOptions) (runtime.RunResult, error) {
	if statement, found := flagValue(options.Command, "-e"); found {
		if path, isSource := strings.CutPrefix(statement, "source "); isSource {
			// MySQL's restore: the client is told to read a file the container can see.
			database, _ := flagValue(options.Command, "-D")
			return f.loadInto(database, path)
		}
		return f.answer(statement)
	}
	if statement, found := flagValue(options.Command, "-c"); found {
		return f.answer(statement)
	}
	f.Scripts = append(f.Scripts, string(options.Stdin))
	if failure := f.FailScript; failure != nil {
		return runtime.RunResult{ExitCode: 3, Stderr: []byte(failure.Error())}, nil
	}
	f.apply(string(options.Stdin))
	return runtime.RunResult{}, nil
}

// answer is a read.
func (f *fakeServer) answer(statement string) (runtime.RunResult, error) {
	f.Queries = append(f.Queries, statement)

	switch {
	case statement == "SELECT 1":
		return runtime.RunResult{Stdout: []byte("1\n")}, nil

	case strings.Contains(statement, "server_version"), strings.Contains(statement, "VERSION()"):
		version := strings.TrimPrefix(f.Version, "PostgreSQL ")
		return runtime.RunResult{Stdout: []byte(version + "\n")}, nil

	case strings.Contains(statement, "pg_database"):
		var out strings.Builder
		for name, database := range f.Databases {
			fmt.Fprintf(&out, "%s\t%s\t%d\n", name, database.Owner, database.Bytes)
		}
		return runtime.RunResult{Stdout: []byte(out.String())}, nil

	case strings.Contains(statement, "information_schema.SCHEMATA"):
		var out strings.Builder
		for name, database := range f.Databases {
			fmt.Fprintf(&out, "%s\t%d\n", name, database.Bytes)
		}
		return runtime.RunResult{Stdout: []byte(out.String())}, nil

	case strings.Contains(statement, "SCHEMA_PRIVILEGES"):
		var out strings.Builder
		for name, login := range f.Logins {
			for schema := range login.Grants {
				fmt.Fprintf(&out, "%s\t%s\n", schema, name)
			}
		}
		return runtime.RunResult{Stdout: []byte(out.String())}, nil

	default:
		return runtime.RunResult{}, fmt.Errorf("the fake server was asked %q", statement)
	}
}

// The statements this package promises to produce. Each one is matched against a line of a
// script, in the order the lines appear, so the effect of a script is the effect of its
// statements in order.
var (
	reCreateDatabasePostgres = regexp.MustCompile(`CREATE DATABASE "([a-z0-9_]+)"(?: OWNER "([a-z0-9_]+)")?`)
	reCreateDatabaseMySQL    = regexp.MustCompile("CREATE DATABASE(?: IF NOT EXISTS)? `([a-z0-9_]+)`")
	reDropDatabasePostgres   = regexp.MustCompile(`DROP DATABASE IF EXISTS "([a-z0-9_]+)"`)
	reDropDatabaseMySQL      = regexp.MustCompile("DROP DATABASE IF EXISTS `([a-z0-9_]+)`")
	reCreateRole             = regexp.MustCompile(`CREATE ROLE "([a-z0-9_]+)"`)
	reAlterRoleLogin         = regexp.MustCompile(`ALTER ROLE "([a-z0-9_]+)" WITH LOGIN PASSWORD '((?:[^']|'')*)'`)
	reAlterRoleNoLogin       = regexp.MustCompile(`ALTER ROLE "([a-z0-9_]+)" WITH NOLOGIN`)
	reDropRole               = regexp.MustCompile(`DROP ROLE IF EXISTS "([a-z0-9_]+)"`)
	reCreateUserMySQL        = regexp.MustCompile(`CREATE USER IF NOT EXISTS '([a-z0-9_]+)'@'%'`)
	reAlterUserMySQL         = regexp.MustCompile(`ALTER USER '([a-z0-9_]+)'@'%' IDENTIFIED BY '((?:[^']|'')*)'`)
	reDropUserMySQL          = regexp.MustCompile(`DROP USER IF EXISTS '([a-z0-9_]+)'@'%'`)
	reGrantMySQL             = regexp.MustCompile("GRANT ALL PRIVILEGES ON `([a-z0-9_]+)`\\.\\* TO '([a-z0-9_]+)'@'%'")
	reOwnerClause            = regexp.MustCompile(`OWNER "([a-z0-9_]+)"`)
)

// apply runs a script against the server's state.
func (f *fakeServer) apply(script string) {
	for _, line := range strings.Split(script, "\n") {
		switch {
		case reDropDatabasePostgres.MatchString(line):
			delete(f.Databases, reDropDatabasePostgres.FindStringSubmatch(line)[1])
		case reDropDatabaseMySQL.MatchString(line):
			delete(f.Databases, reDropDatabaseMySQL.FindStringSubmatch(line)[1])
		case reCreateDatabasePostgres.MatchString(line):
			match := reCreateDatabasePostgres.FindStringSubmatch(line)
			f.createDatabase(match[1], match[2])
		case reCreateDatabaseMySQL.MatchString(line):
			f.createDatabase(reCreateDatabaseMySQL.FindStringSubmatch(line)[1], "")
		case reDropRole.MatchString(line):
			delete(f.Logins, reDropRole.FindStringSubmatch(line)[1])
		case reDropUserMySQL.MatchString(line):
			delete(f.Logins, reDropUserMySQL.FindStringSubmatch(line)[1])
		case reCreateRole.MatchString(line):
			f.createLogin(reCreateRole.FindStringSubmatch(line)[1])
		case reCreateUserMySQL.MatchString(line):
			f.createLogin(reCreateUserMySQL.FindStringSubmatch(line)[1])
		case reAlterRoleLogin.MatchString(line):
			match := reAlterRoleLogin.FindStringSubmatch(line)
			f.unlock(match[1], strings.ReplaceAll(match[2], "''", "'"))
		case reAlterUserMySQL.MatchString(line):
			match := reAlterUserMySQL.FindStringSubmatch(line)
			f.unlock(match[1], strings.ReplaceAll(match[2], "''", "'"))
		case reAlterRoleNoLogin.MatchString(line):
			f.lock(reAlterRoleNoLogin.FindStringSubmatch(line)[1])
		case reGrantMySQL.MatchString(line):
			match := reGrantMySQL.FindStringSubmatch(line)
			f.createLogin(match[2])
			f.Logins[match[2]].Grants[match[1]] = true
		}
	}
}

func (f *fakeServer) createDatabase(name, owner string) {
	if _, present := f.Databases[name]; present {
		return
	}
	f.Databases[name] = &fakeDatabase{Owner: owner}
}

func (f *fakeServer) createLogin(name string) {
	if _, present := f.Logins[name]; present {
		return
	}
	f.Logins[name] = &fakeLogin{Locked: true, Grants: make(map[string]bool)}
}

func (f *fakeServer) lock(name string) {
	f.createLogin(name)
	f.Logins[name].Locked = true
}

func (f *fakeServer) unlock(name, password string) {
	f.createLogin(name)
	f.Logins[name].Password = password
	f.Logins[name].Locked = false
}

// dump writes a database out to the transfer directory the container shares with the host.
func (f *fakeServer) dump(options runtime.RunOptions) (runtime.RunResult, error) {
	// pg_dump names its database with -d; mysqldump takes it as the last argument.
	target := f.targetOf(options.Command)
	if target == "" {
		target = lastArgument(options.Command)
	}
	path, found := flagPrefix(options.Command, "--file=")
	if !found {
		path, found = flagPrefix(options.Command, "--result-file=")
	}
	if !found {
		return runtime.RunResult{}, fmt.Errorf("the fake server was asked to dump with no output file")
	}

	database, present := f.Databases[target]
	if !present {
		return runtime.RunResult{ExitCode: 1,
			Stderr: []byte("no such database: " + target)}, nil
	}

	payload, err := json.Marshal(database)
	if err != nil {
		return runtime.RunResult{}, err
	}
	if err := os.WriteFile(f.hostPath(path), payload, 0o600); err != nil {
		return runtime.RunResult{}, err
	}
	return runtime.RunResult{}, nil
}

// loadInto reads an archive back into a database.
func (f *fakeServer) loadInto(database, path string) (runtime.RunResult, error) {
	if database == "" {
		return runtime.RunResult{ExitCode: 1,
			Stderr: []byte("no target database was named")}, nil
	}
	payload, err := os.ReadFile(f.hostPath(path))
	if err != nil {
		return runtime.RunResult{ExitCode: 1, Stderr: []byte(err.Error())}, nil
	}

	restored := &fakeDatabase{}
	if err := json.Unmarshal(payload, restored); err != nil {
		return runtime.RunResult{ExitCode: 1, Stderr: []byte("the archive is not readable")}, nil
	}
	existing, present := f.Databases[database]
	if !present {
		return runtime.RunResult{ExitCode: 1,
			Stderr: []byte("no such database: " + database)}, nil
	}
	existing.Bytes = restored.Bytes
	existing.Content = restored.Content
	return runtime.RunResult{}, nil
}

// hostPath maps a path inside the container onto the host directory bound to it.
func (f *fakeServer) hostPath(containerPath string) string {
	return filepath.Join(f.TransferDir, filepath.Base(containerPath))
}

// pg_restore names its target with -d; the fake reads it the same way psql's caller does.
func (f *fakeServer) targetOf(argv []string) string {
	value, _ := flagValue(argv, "-d")
	return value
}

// flagValue is the argument following a flag.
func flagValue(argv []string, flag string) (string, bool) {
	for index, argument := range argv {
		if argument == flag && index+1 < len(argv) {
			return argv[index+1], true
		}
	}
	return "", false
}

// flagPrefix is the remainder of the first argument starting with a prefix.
func flagPrefix(argv []string, prefix string) (string, bool) {
	for _, argument := range argv {
		if value, found := strings.CutPrefix(argument, prefix); found {
			return value, true
		}
	}
	return "", false
}

func lastArgument(argv []string) string {
	if len(argv) == 0 {
		return ""
	}
	return argv[len(argv)-1]
}

// sizeOf sets a database's measured size, for the quota tests.
func (f *fakeServer) sizeOf(name string, bytes int64) {
	f.mutex.Lock()
	defer f.mutex.Unlock()
	if database, present := f.Databases[name]; present {
		database.Bytes = bytes
	}
}

// setReady switches the server on or off from another goroutine, which is how a test makes a
// cold start finish while a provision is polling for it.
func (f *fakeServer) setReady(ready bool) {
	f.mutex.Lock()
	defer f.mutex.Unlock()
	f.Ready = ready
}

// scriptsJoined is every script the server was given, for one assertion over all of them.
func (f *fakeServer) scriptsJoined() string { return strings.Join(f.Scripts, "\n") }
