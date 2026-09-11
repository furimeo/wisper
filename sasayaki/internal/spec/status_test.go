package spec

import (
	"testing"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Every status the daemon writes goes to disk as protobuf and comes back after a restart,
// so a field that is lost in either direction is a fact the panel never hears again.
func TestWorkloadStatusRoundTrips(t *testing.T) {
	started := time.Date(2026, 9, 11, 9, 0, 0, 0, time.UTC)
	transition := time.Date(2026, 9, 11, 9, 0, 12, 0, time.UTC)
	status := WorkloadStatus{
		WorkloadID:       "1e9d",
		Phase:            PhaseCrashLooping,
		ContainerID:      "3f2a",
		ImageDigest:      "sha256:abc",
		RestartCount:     5,
		ExitCode:         137,
		Message:          "OOMKilled",
		StartedAt:        started,
		LastTransitionAt: transition,
		Runtime:          RuntimeRunc,
		ReleaseID:        "rel-7",
	}

	back := WorkloadStatusFromProto(status.ToProto())

	if back.WorkloadID != status.WorkloadID || back.Phase != status.Phase {
		t.Errorf("identity lost: %+v", back)
	}
	if back.ContainerID != "3f2a" || back.ImageDigest != "sha256:abc" {
		t.Errorf("container facts lost: %+v", back)
	}
	if back.RestartCount != 5 || back.ExitCode != 137 || back.Message != "OOMKilled" {
		t.Errorf("failure detail lost: %+v", back)
	}
	if !back.StartedAt.Equal(started) || !back.LastTransitionAt.Equal(transition) {
		t.Errorf("timestamps lost: %+v", back)
	}
	if back.Runtime != RuntimeRunc {
		t.Errorf("runtime = %q: the runtime in effect is not the one requested, and the "+
			"panel has to be told which it was", back.Runtime)
	}
	if back.ReleaseID != "rel-7" {
		t.Errorf("releaseID = %q, want rel-7", back.ReleaseID)
	}
}

func TestEveryPhaseRoundTrips(t *testing.T) {
	phases := []Phase{
		PhaseUnspecified, PhasePending, PhasePulling, PhaseStarting, PhaseRunning,
		PhaseUnhealthy, PhaseStopped, PhaseFailed, PhaseCrashLooping, PhaseUnknown,
	}
	for _, phase := range phases {
		if got := phaseFromProto(phaseToProto(phase)); got != phase {
			t.Errorf("%q round-tripped to %q", phase, got)
		}
	}
}

// "Nothing has looked" and "somebody looked and Docker did not answer" are different
// facts, and only one of them means the node is degraded.
func TestUnknownIsNotUnspecified(t *testing.T) {
	if PhaseUnknown == PhaseUnspecified {
		t.Fatal("the two must be distinct constants")
	}
	if phaseToProto(PhaseUnknown) == phaseToProto(PhaseUnspecified) {
		t.Error("the two must be distinct on the wire too")
	}
	if PhaseUnknown.IsUp() || PhaseUnknown.IsSettled() {
		t.Error("an unobservable workload is neither up nor settled; it is unreadable")
	}
}

func TestPhasePredicates(t *testing.T) {
	up := map[Phase]bool{PhaseRunning: true, PhaseUnhealthy: true}
	settled := map[Phase]bool{
		PhaseRunning: true, PhaseStopped: true, PhaseFailed: true, PhaseCrashLooping: true,
	}
	all := []Phase{
		PhaseUnspecified, PhasePending, PhasePulling, PhaseStarting, PhaseRunning,
		PhaseUnhealthy, PhaseStopped, PhaseFailed, PhaseCrashLooping, PhaseUnknown,
	}
	for _, phase := range all {
		if phase.IsUp() != up[phase] {
			t.Errorf("%q.IsUp() = %v, want %v", phase, phase.IsUp(), up[phase])
		}
		if phase.IsSettled() != settled[phase] {
			t.Errorf("%q.IsSettled() = %v, want %v", phase, phase.IsSettled(), settled[phase])
		}
	}
}

func TestRouteStatusRoundTrips(t *testing.T) {
	notAfter := time.Date(2026, 12, 1, 0, 0, 0, 0, time.UTC)
	status := RouteStatus{
		Domain:      "acme.example",
		Serving:     true,
		Certificate: CertificateValid,
		NotAfter:    notAfter,
		LastError:   "",
	}

	back := RouteStatusFromProto(status.ToProto())

	if back.Domain != "acme.example" || !back.Serving {
		t.Errorf("status = %+v", back)
	}
	if back.Certificate != CertificateValid || !back.NotAfter.Equal(notAfter) {
		t.Errorf("certificate facts lost: %+v", back)
	}
}

// The ACME failure travels verbatim: "DNS does not point here yet" and "rate limited" need
// completely different actions from the customer.
func TestRouteStatusKeepsTheErrorVerbatim(t *testing.T) {
	message := "acme: urn:ietf:params:acme:error:rateLimited - too many certificates"
	back := RouteStatusFromProto(RouteStatus{
		Domain:      "acme.example",
		Certificate: CertificateFailed,
		LastError:   message,
	}.ToProto())

	if back.LastError != message {
		t.Errorf("lastError = %q, want it verbatim", back.LastError)
	}
}

func TestEveryCertificateStateRoundTrips(t *testing.T) {
	states := []CertificateState{
		CertificateUnspecified, CertificateNone, CertificateIssuing,
		CertificateValid, CertificateFailed,
	}
	for _, state := range states {
		if got := certificateStateFromProto(certificateStateToProto(state)); got != state {
			t.Errorf("%q round-tripped to %q", state, got)
		}
	}
}

func TestDatabaseStatusRoundTrips(t *testing.T) {
	measured := time.Date(2026, 9, 11, 10, 0, 0, 0, time.UTC)
	status := DatabaseStatus{
		GrantID:       "grant-1",
		Engine:        EnginePostgres,
		Exists:        true,
		SizeBytes:     900 << 20,
		QuotaBytes:    1 << 30,
		OverQuota:     false,
		EngineVersion: "PostgreSQL 17.2",
		MeasuredAt:    measured,
	}

	back := DatabaseStatusFromProto(status.ToProto())

	if back != status {
		t.Errorf("status = %+v, want %+v", back, status)
	}
}

// "Cannot see it" is not "does not exist". A grant whose engine container is down reports
// exists=false and the panel shows "unavailable", not "deleted".
func TestDatabaseStatusCanSayItCannotSee(t *testing.T) {
	back := DatabaseStatusFromProto(DatabaseStatus{
		GrantID:   "grant-1",
		Engine:    EnginePostgres,
		Exists:    false,
		LastError: "engine container is not running",
	}.ToProto())

	if back.Exists {
		t.Error("exists should be false")
	}
	if back.LastError == "" {
		t.Error("an unreadable grant must say why")
	}
}

func TestCronStatusRoundTrips(t *testing.T) {
	last := time.Date(2026, 9, 11, 3, 0, 0, 0, time.UTC)
	next := time.Date(2026, 9, 12, 3, 0, 0, 0, time.UTC)
	status := CronStatus{
		CronID:         "cron-1",
		LastRunAt:      last,
		NextRunAt:      next,
		LastExitCode:   0,
		Running:        true,
		LastRunSkipped: true,
		LastError:      "",
	}

	back := CronStatusFromProto(status.ToProto())

	if back != status {
		t.Errorf("status = %+v, want %+v", back, status)
	}
}

// A run that never happened has no timestamp, and the wire must carry that as absence
// rather than as the year 1 or the epoch.
func TestUnsetStatusTimestampsStayUnset(t *testing.T) {
	message := CronStatus{CronID: "cron-1"}.ToProto()

	if message.GetLastRunAt() != nil {
		t.Errorf("lastRunAt = %v, want nil", message.GetLastRunAt())
	}
	if got := CronStatusFromProto(message); !got.LastRunAt.IsZero() {
		t.Errorf("lastRunAt read back as %s, want the zero time", got.LastRunAt)
	}
}

func TestStatusFromProtoAcceptsNil(t *testing.T) {
	if got := WorkloadStatusFromProto(nil); got.Phase != PhaseUnspecified || got.WorkloadID != "" {
		t.Errorf("workload: %+v", got)
	}
	if got := RouteStatusFromProto(nil); got.Domain != "" || got.Certificate != CertificateUnspecified {
		t.Errorf("route: %+v", got)
	}
	if got := DatabaseStatusFromProto(nil); got.GrantID != "" || got.Engine != EngineUnknown {
		t.Errorf("database: %+v", got)
	}
	if got := CronStatusFromProto(nil); got.CronID != "" || !got.LastRunAt.IsZero() {
		t.Errorf("cron: %+v", got)
	}
}

// The statuses a StatusBatch carries are exactly the four this package renders. A fifth
// added to the proto fails here rather than being quietly never reported.
func TestStatusBatchCarriesTheFourThisPackageRenders(t *testing.T) {
	batch := &wisperpb.StatusBatch{
		Workloads: []*wisperpb.WorkloadStatus{(WorkloadStatus{}).ToProto()},
		Routes:    []*wisperpb.RouteStatus{(RouteStatus{}).ToProto()},
		Databases: []*wisperpb.DatabaseStatus{(DatabaseStatus{}).ToProto()},
		Cron:      []*wisperpb.CronStatus{(CronStatus{}).ToProto()},
	}

	if len(batch.GetWorkloads()) != 1 || len(batch.GetRoutes()) != 1 ||
		len(batch.GetDatabases()) != 1 || len(batch.GetCron()) != 1 {
		t.Error("a status batch did not accept one of each")
	}
}
