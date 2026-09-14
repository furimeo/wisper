package bootstrap

import (
	"context"
	"errors"
	"io"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"google.golang.org/protobuf/encoding/protojson"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// The property the installer depends on. Everything else in this package is allowed to
// write; this is the one thing that is not, because "doctor failed, so nothing was
// installed" is only worth saying if doctor could not have installed anything.
func TestPreflightWritesNothing(t *testing.T) {
	host := healthyMachine(t)
	// Every root lives under one temp directory, so listing it lists everything the
	// preflight could have touched.
	root := filepath.Dir(host.procRoot)

	before := everythingUnder(t, root)
	preflight(context.Background(), host)
	after := everythingUnder(t, root)

	if len(before) != len(after) {
		t.Fatalf("the preflight changed the filesystem:\nbefore %v\nafter  %v", before, after)
	}
	for index := range before {
		if before[index] != after[index] {
			t.Fatalf("the preflight changed the filesystem:\nbefore %v\nafter  %v", before, after)
		}
	}
}

func TestHealthyMachinePassesEveryRequiredCheck(t *testing.T) {
	report := preflight(context.Background(), healthyMachine(t))

	if !report.GetRequiredChecksPassed() {
		for _, item := range report.GetChecks() {
			if item.GetOutcome() == outcomeFail {
				t.Errorf("%s failed: %s", item.GetId(), item.GetDetail())
			}
		}
		t.Fatal("a healthy machine did not pass")
	}
	if report.GetMachine().GetCgroupsV2() != true {
		t.Error("cgroup v2 was not recorded in the machine facts")
	}
	if report.GetMachine().GetStateFilesystem() != "xfs" {
		t.Errorf("state filesystem is %q, want xfs", report.GetMachine().GetStateFilesystem())
	}
	if !report.GetMachine().GetProjectQuotaSupported() {
		t.Error("prjquota was in the mount options and was not recorded")
	}
}

// Design section 7.2: a node without runsc is usable and less isolated, and the panel has
// to be told which. What must never happen is the check quietly deciding for itself -
// either by failing the installation or by saying nothing.
func TestMissingRunscIsAWarningAndNotAFailure(t *testing.T) {
	host := healthyMachine(t)
	host.lookPath = func(string) (string, error) { return "", errors.New("executable file not found in $PATH") }
	host.probeDocker = func(context.Context, string) (dockerReport, error) {
		return dockerReport{Version: "27.3.1", APIVersion: "1.47", CgroupVersion: "2",
			Runtimes: []string{"runc"}}, nil
	}

	report := preflight(context.Background(), host)

	if !report.GetRequiredChecksPassed() {
		t.Fatal("a node without runsc must still be installable")
	}
	runsc := checkNamed(t, report, "runtime.runsc")
	if runsc.GetOutcome() != outcomeWarn {
		t.Fatalf("runsc check is %v, want a warning", runsc.GetOutcome())
	}
	if report.GetMachine().GetRunscAvailable() {
		t.Fatal("the facts claim runsc is available when it is not: the panel would show " +
			"this node as isolated")
	}
	mustContain(t, runsc.GetDetail(), "runc")
}

// gVisor installed and never registered with the engine looks like success from every
// angle except the one that decides what a container runs under.
func TestRunscOnPathButNotRegisteredWithTheEngineWarns(t *testing.T) {
	host := healthyMachine(t)
	host.probeDocker = func(context.Context, string) (dockerReport, error) {
		return dockerReport{Version: "27.3.1", APIVersion: "1.47", CgroupVersion: "2",
			Runtimes: []string{"runc"}}, nil
	}

	report := preflight(context.Background(), host)

	runsc := checkNamed(t, report, "runtime.runsc")
	if runsc.GetOutcome() != outcomeWarn {
		t.Fatalf("runsc check is %v, want a warning", runsc.GetOutcome())
	}
	mustContain(t, runsc.GetRemedy(), "daemon.json")
	if report.GetMachine().GetRunscAvailable() {
		t.Fatal("runsc is not selectable, so the facts must not say it is available")
	}
}

func TestAnUnreachableDockerSocketFailsAndSaysHow(t *testing.T) {
	host := healthyMachine(t)
	host.probeDocker = func(context.Context, string) (dockerReport, error) {
		return dockerReport{Host: "unix:///var/run/docker.sock"},
			errors.New("dial unix /var/run/docker.sock: connect: no such file or directory")
	}

	report := preflight(context.Background(), host)

	if report.GetRequiredChecksPassed() {
		t.Fatal("a node with no Docker engine cannot host workloads")
	}
	socket := checkNamed(t, report, "docker.socket")
	if socket.GetOutcome() != outcomeFail {
		t.Fatalf("docker.socket is %v, want a failure", socket.GetOutcome())
	}
	if socket.GetRemedy() == "" {
		t.Fatal("a failing check with no remedy is a support ticket")
	}
}

func TestAnEngineOlderThanTheFloorFails(t *testing.T) {
	host := healthyMachine(t)
	host.probeDocker = func(context.Context, string) (dockerReport, error) {
		return dockerReport{Version: "20.10.24", APIVersion: "1.41", CgroupVersion: "2",
			Runtimes: []string{"runc"}}, nil
	}

	report := preflight(context.Background(), host)

	if report.GetRequiredChecksPassed() {
		t.Fatal("API 1.41 is below the floor and must fail")
	}
	mustContain(t, checkNamed(t, report, "docker.version").GetDetail(), minimumDockerAPI)
}

// ext4 cannot enforce a per-volume disk limit. The node still works, so this is a
// warning - but it is the warning that decides whether the panel is showing a quota it
// can apply or a number it is guessing with (design section 11.1).
func TestExt4WarnsLoudlyThatQuotasCannotBeEnforced(t *testing.T) {
	host := healthyMachine(t)
	writeFile(t, host.procRoot+"/self/mountinfo",
		"25 30 8:1 / / rw,relatime shared:1 - ext4 /dev/sda1 rw\n")

	report := preflight(context.Background(), host)

	if !report.GetRequiredChecksPassed() {
		t.Fatal("ext4 must not stop an installation")
	}
	filesystem := checkNamed(t, report, "storage.filesystem")
	if filesystem.GetOutcome() != outcomeWarn {
		t.Fatalf("ext4 produced %v, want a warning", filesystem.GetOutcome())
	}
	mustContain(t, filesystem.GetDetail(), "DISK LIMITS CANNOT BE ENFORCED")
	if report.GetMachine().GetProjectQuotaSupported() {
		t.Fatal("the facts claim project quotas work on plain ext4")
	}
}

func TestPortEightyBeingTakenFailsAndExplainsAcme(t *testing.T) {
	host := healthyMachine(t)
	host.listen = func(_, address string) (io.Closer, error) {
		if strings.HasSuffix(address, ":80") {
			return nil, errors.New("bind: address already in use")
		}
		return io.NopCloser(strings.NewReader("")), nil
	}

	report := preflight(context.Background(), host)

	if report.GetRequiredChecksPassed() {
		t.Fatal("the embedded edge needs :80")
	}
	port := checkNamed(t, report, "network.port80")
	if port.GetOutcome() != outcomeFail {
		t.Fatalf(":80 in use produced %v, want a failure", port.GetOutcome())
	}
	mustContain(t, port.GetRemedy(), "HTTP-01")
	if report.GetMachine().GetPort_80Free() {
		t.Fatal("the facts claim :80 is free when it could not be bound")
	}
}

func TestEdgeRunningTreatsPortsAsPassed(t *testing.T) {
	host := healthyMachine(t)
	host.edgeRunning = true
	// Ensure that even if host.listen would fail, it is not called because edge is running.
	host.listen = func(_, _ string) (io.Closer, error) {
		return nil, errors.New("bind: address already in use")
	}

	report := preflight(context.Background(), host)

	if !report.GetRequiredChecksPassed() {
		t.Fatal("a machine with edge running must pass required checks")
	}
	port80 := checkNamed(t, report, "network.port80")
	if port80.GetOutcome() != outcomePass {
		t.Fatalf(":80 with edge running produced %v, want pass", port80.GetOutcome())
	}
	port443 := checkNamed(t, report, "network.port443")
	if port443.GetOutcome() != outcomePass {
		t.Fatalf(":443 with edge running produced %v, want pass", port443.GetOutcome())
	}
	if !report.GetMachine().GetPort_80Free() || !report.GetMachine().GetPort_443Free() {
		t.Fatal("the facts claim ports are not free when edge is running")
	}
}

// The check exists to separate "this machine cannot reach the panel" from "the panel
// refused the token", which look identical from a terminal and send an operator to
// different places (design section 7.2).
func TestAnUnreachablePanelFailsSeparatelyFromTheToken(t *testing.T) {
	host := healthyMachine(t)
	host.panel = "https://panel.example"
	host.probePanel = func(_ context.Context, endpoint string) panelProbe {
		return panelProbe{Endpoint: endpoint, Stage: "connect",
			Err: errors.New("connect: connection refused")}
	}

	report := preflight(context.Background(), host)

	panelCheck := checkNamed(t, report, "network.panel")
	if panelCheck.GetOutcome() != outcomeFail {
		t.Fatalf("an unreachable panel produced %v, want a failure", panelCheck.GetOutcome())
	}
	mustContain(t, panelCheck.GetDetail(), "nothing is listening")
	mustContain(t, panelCheck.GetRemedy(), "tunnel")
}

// A check that did not run is not a check that passed, so it is not in the report at all.
func TestNoPanelMeansNoPanelCheck(t *testing.T) {
	report := preflight(context.Background(), healthyMachine(t))

	for _, item := range report.GetChecks() {
		if item.GetId() == "network.panel" {
			t.Fatal("doctor was given no --panel and reported on one anyway")
		}
	}
}

// Clock skew breaks TLS and ACME with symptoms that point at everything except the clock.
func TestAClockThirtySecondsOutFails(t *testing.T) {
	host := healthyMachine(t)
	host.panel = "https://panel.example"
	local := host.now()
	host.probePanel = func(_ context.Context, endpoint string) panelProbe {
		return panelProbe{
			Endpoint:   endpoint,
			Reachable:  true,
			Stage:      "tls",
			ServerTime: local.Add(-5 * time.Minute),
			LocalTime:  local,
		}
	}

	report := preflight(context.Background(), host)

	if report.GetRequiredChecksPassed() {
		t.Fatal("five minutes of skew must fail")
	}
	clock := checkNamed(t, report, "clock.synchronised")
	if clock.GetOutcome() != outcomeFail {
		t.Fatalf("clock check is %v, want a failure", clock.GetOutcome())
	}
	if report.GetMachine().GetClockOffsetMillis() == 0 {
		t.Fatal("the measured offset was not recorded in the facts")
	}
}

// Without a fingerprint the panel cannot tell this node from a copy of it, so enrolment
// has nothing to detect a clone with (design section 7.3).
func TestAMachineWithNoIdentityFails(t *testing.T) {
	host := healthyMachine(t)
	mustRemove(t, host.etcRoot+"/machine-id")
	mustRemove(t, host.sysRoot+"/class/dmi/id/product_uuid")
	mustRemove(t, host.sysRoot+"/class/dmi/id/board_serial")

	report := preflight(context.Background(), host)

	if report.GetRequiredChecksPassed() {
		t.Fatal("a node with no identity must not enrol")
	}
	identity := checkNamed(t, report, "machine.identity")
	if identity.GetOutcome() != outcomeFail {
		t.Fatalf("identity check is %v, want a failure", identity.GetOutcome())
	}
}

// Running unprivileged is one failure that causes several others, so it is reported first
// and says so.
func TestRunningAsAnOrdinaryUserFailsFirst(t *testing.T) {
	host := healthyMachine(t)
	host.uid = 1000

	report := preflight(context.Background(), host)

	if report.GetChecks()[0].GetId() != "privileges.root" {
		t.Fatalf("the first check is %q; the privilege check has to come first because it "+
			"explains the ones after it", report.GetChecks()[0].GetId())
	}
	if report.GetRequiredChecksPassed() {
		t.Fatal("an unprivileged daemon cannot bind :80 or read the Docker socket")
	}
}

// The installer parses this, so it has to be the wire format from node.proto rather than
// whatever encoding/json would make of the generated structs.
func TestTheJsonReportIsProtoJson(t *testing.T) {
	report := preflight(context.Background(), healthyMachine(t))

	var out strings.Builder
	if err := writeReportJSON(&out, report); err != nil {
		t.Fatalf("write the report: %v", err)
	}

	var parsed wisperpb.DoctorReport
	if err := protojson.Unmarshal([]byte(out.String()), &parsed); err != nil {
		t.Fatalf("the report does not parse as the proto it claims to be: %v", err)
	}
	if len(parsed.GetChecks()) != len(report.GetChecks()) {
		t.Fatalf("round trip lost checks: %d in, %d out",
			len(report.GetChecks()), len(parsed.GetChecks()))
	}
	mustContain(t, out.String(), "requiredChecksPassed")
}

// Every failure and every warning has to say what to do about it. A check that says "no"
// and stops is the thing this package exists to not be.
func TestEveryUnhappyCheckCarriesARemedy(t *testing.T) {
	host := healthyMachine(t)
	host.uid = 1000
	host.probeDocker = func(context.Context, string) (dockerReport, error) {
		return dockerReport{}, errors.New("no socket")
	}
	host.listen = func(string, string) (io.Closer, error) { return nil, errors.New("in use") }
	host.dial = func(context.Context, string, string) (io.Closer, error) {
		return nil, errors.New("network unreachable")
	}
	host.diskSpace = func(string) (int64, int64, error) { return 20 << 30, 1 << 30, nil }
	host.lookPath = func(string) (string, error) { return "", errors.New("not found") }

	report := preflight(context.Background(), host)

	for _, item := range report.GetChecks() {
		if item.GetOutcome() == outcomePass {
			continue
		}
		if strings.TrimSpace(item.GetRemedy()) == "" {
			t.Errorf("%s is %v and offers no remedy", item.GetId(), item.GetOutcome())
		}
	}
}

func TestTheTextReportNamesTheFailuresAndSaysNothingWasInstalled(t *testing.T) {
	host := healthyMachine(t)
	host.probeDocker = func(context.Context, string) (dockerReport, error) {
		return dockerReport{}, errors.New("no socket")
	}

	var out strings.Builder
	writeReportText(&out, preflight(context.Background(), host))

	mustContain(t, out.String(), "FAIL")
	mustContain(t, out.String(), "docker.socket")
	mustContain(t, out.String(), "Nothing has been installed or changed.")
}
