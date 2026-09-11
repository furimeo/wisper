package daemon

import (
	"bytes"
	"errors"
	"flag"
	"path/filepath"
	"strings"
	"testing"

	"github.com/furimeo/wisper/sasayaki/internal/bootstrap"
)

// The flags are a contract, not a convenience: the systemd unit and the Makefile's run-dev
// target both invoke this command, and deploy/install.sh branches on what it exits with.
//
// The one that is easy to get wrong is the state directory. run-dev passes ./var/dev, and
// six of the packages this daemon assembles refuse a relative path - correctly, because one
// stored inside a container mount specification resolves against whatever directory the
// process happened to be started in. So it is made absolute once, here.

func TestParse(t *testing.T) {
	cases := []struct {
		name string
		args []string

		wantConfig string
		// wantStateDir is written the way it is passed and compared after the same
		// resolution parse applies, so the assertion means the same thing on the developer's
		// Windows machine and on the Linux node the daemon actually runs on.
		wantStateDir string
		wantDev      bool

		fails bool
		help  bool
	}{
		{
			name:         "no flags is what the systemd unit would get if it passed none",
			args:         nil,
			wantConfig:   bootstrap.DefaultConfigPath,
			wantStateDir: bootstrap.DefaultStateDir,
		},
		{
			name:         "what the systemd unit actually passes",
			args:         []string{"--config", "/etc/wisper/node.json", "--state-dir", "/var/lib/wisper"},
			wantConfig:   "/etc/wisper/node.json",
			wantStateDir: "/var/lib/wisper",
		},
		{
			name:         "what make run-dev passes, with the relative state directory resolved",
			args:         []string{"--state-dir", "./var/dev", "--config", "./var/dev/node.json", "--dev"},
			wantConfig:   "./var/dev/node.json",
			wantStateDir: "./var/dev",
			wantDev:      true,
		},
		{
			name:  "a flag this command does not have is a usage error, not a default",
			args:  []string{"--token", "hunter2"},
			fails: true,
		},
		{
			name:  "a stray argument is refused rather than ignored",
			args:  []string{"start"},
			fails: true,
		},
		{
			name: "an empty state directory is refused rather than resolved to wherever the " +
				"daemon happened to be started",
			args:  []string{"--state-dir", ""},
			fails: true,
		},
		{
			name: "-h is answered by the flag package and recognised by main",
			args: []string{"-h"},
			help: true,
		},
	}

	for _, test := range cases {
		t.Run(test.name, func(t *testing.T) {
			var out bytes.Buffer
			got, err := parse(test.args, &out)

			switch {
			case test.help:
				if !errors.Is(err, flag.ErrHelp) {
					t.Fatalf("parse(-h) returned %v, want flag.ErrHelp so main can exit 0 quietly", err)
				}
				for _, flagName := range []string{"config", "state-dir", "dev"} {
					if !strings.Contains(out.String(), flagName) {
						t.Errorf("the usage written to out does not mention --%s", flagName)
					}
				}
				return
			case test.fails:
				if err == nil {
					t.Fatalf("parse(%v) was accepted, want a usage error", test.args)
				}
				if errors.Is(err, flag.ErrHelp) {
					t.Fatal("a usage error was reported as a request for help, which exits 0")
				}
				return
			}

			if err != nil {
				t.Fatalf("parse(%v): %v", test.args, err)
			}
			wantStateDir, absErr := filepath.Abs(test.wantStateDir)
			if absErr != nil {
				t.Fatalf("resolve %q: %v", test.wantStateDir, absErr)
			}
			want := settings{configPath: test.wantConfig, stateDir: wantStateDir, dev: test.wantDev}
			if got != want {
				t.Errorf("parse(%v) = %+v, want %+v", test.args, got, want)
			}
			if !filepath.IsAbs(got.stateDir) {
				t.Errorf("the state directory %q is relative, and half the daemon refuses one",
					got.stateDir)
			}
		})
	}
}

// Usage goes to out rather than to the log, because that is the stream a person running
// `sasayaki run -h` is reading and the one the contract names.
func TestParseWritesUsageToOutOnly(t *testing.T) {
	var out, errOut bytes.Buffer
	if _, err := parse([]string{"--nonsense"}, &out); err == nil {
		t.Fatal("an unknown flag was accepted")
	}
	if out.Len() == 0 {
		t.Error("nothing was written to out, so the operator was told a flag is wrong and not which")
	}
	if errOut.Len() != 0 {
		t.Error("something reached errOut, which the daemon's log owns")
	}
}
