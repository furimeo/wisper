package terminal

import (
	"errors"
	"strings"
	"testing"

	"github.com/furimeo/wisper/sasayaki/internal/reconcile"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Attaching, and the four ways it can refuse.

func TestAttachingAnnouncesWhatTheBrowserActuallyGot(t *testing.T) {
	test := newHarness(t)
	// The engine clamped the browser's request, which is the case the acknowledgement
	// exists for: a client whose grid disagrees with the pty renders a mess.
	test.pty.cols, test.pty.rows = 200, 50

	finished := test.start(t, &wisperpb.StartTerminal{InitialCols: 4000, InitialRows: 3})
	test.pty.stop()
	if err := finish(t, finished); err != nil {
		t.Fatalf("the session failed: %v", err)
	}

	attached := wantAttached(t, test.stream.frames())
	if attached.GetContainerId() != containerID {
		t.Errorf("attached to container %q, expected %q", attached.GetContainerId(), containerID)
	}
	if attached.GetWorkloadId() != workloadID {
		t.Errorf("attached to workload %q, expected %q", attached.GetWorkloadId(), workloadID)
	}
	if attached.GetCols() != 200 || attached.GetRows() != 50 {
		t.Errorf("announced %dx%d, expected the size the pty was given, 200x50",
			attached.GetCols(), attached.GetRows())
	}
	if !attached.GetAttachedAt().AsTime().Equal(noon) {
		t.Errorf("attached at %s, expected %s", attached.GetAttachedAt().AsTime(), noon)
	}
	if test.stream.frames()[0].GetSessionId() != sessionID {
		t.Error("the attachment does not carry the session id, so the panel cannot route it")
	}
}

func TestTheRequestedSizeReachesTheEngine(t *testing.T) {
	test := newHarness(t)

	finished := test.start(t, &wisperpb.StartTerminal{
		InitialCols: 132,
		InitialRows: 43,
		WorkingDir:  "/srv/app",
		User:        "1000:1000",
	})
	test.pty.stop()
	if err := finish(t, finished); err != nil {
		t.Fatalf("the session failed: %v", err)
	}

	attachments := test.engine.attachments()
	if len(attachments) != 1 {
		t.Fatalf("expected one attachment, got %d", len(attachments))
	}
	opened := attachments[0]
	if opened.Cols != 132 || opened.Rows != 43 {
		t.Errorf("opened the pty at %dx%d, expected 132x43", opened.Cols, opened.Rows)
	}
	if opened.WorkingDir != "/srv/app" {
		t.Errorf("opened in %q, expected /srv/app", opened.WorkingDir)
	}
	if opened.User != "1000:1000" {
		t.Errorf("opened as %q, expected 1000:1000", opened.User)
	}
}

func TestASessionWithNoIdIsRefused(t *testing.T) {
	test := newHarness(t)

	err := test.host.Serve(t.Context(), &wisperpb.StartTerminal{WorkloadId: workloadID}, test.stream)
	if err == nil {
		t.Fatal("a session with no id was accepted, so its frames could not be told apart")
	}
	if len(test.stream.frames()) != 0 {
		t.Error("frames were sent for a session that was refused")
	}
}

func TestASessionWithNoWorkloadIsRefused(t *testing.T) {
	test := newHarness(t)

	err := test.host.Serve(t.Context(), &wisperpb.StartTerminal{SessionId: sessionID}, test.stream)
	if err == nil {
		t.Fatal("a session naming no workload was accepted")
	}
	if !strings.Contains(err.Error(), sessionID) {
		t.Errorf("the refusal does not name the session: %v", err)
	}
}

// A workload with no container and an engine that cannot be asked are different answers,
// and they must not be reported as the same one: the first is "your service is not
// running" and the second is "come back in a moment" (AGENTS.md section 4.5).
func TestAMissingContainerAndAnUnreachableEngineAreDifferent(t *testing.T) {
	t.Run("no container", func(t *testing.T) {
		test := newHarness(t)
		test.engine.exists = false
		test.engine.container = reconcile.Container{}

		err := finish(t, test.start(t, nil))
		if err == nil {
			t.Fatal("a terminal was opened into a workload with no container")
		}
		if !strings.Contains(err.Error(), "no container on this node") {
			t.Errorf("the refusal does not say the container is missing: %v", err)
		}
	})

	t.Run("engine unreachable", func(t *testing.T) {
		test := newHarness(t)
		test.engine.lookupErr = errors.New("docker is not answering")

		err := finish(t, test.start(t, nil))
		if err == nil {
			t.Fatal("a terminal was opened while the engine could not be asked")
		}
		if strings.Contains(err.Error(), "no container on this node") {
			t.Errorf("an unreachable engine was reported as a missing container: %v", err)
		}
		if !strings.Contains(err.Error(), "docker is not answering") {
			t.Errorf("the engine's own words were lost: %v", err)
		}
	})
}

func TestAStoppedContainerIsRefusedWithItsStatus(t *testing.T) {
	test := newHarness(t)
	test.engine.container.Running = false
	test.engine.container.Status = "Exited (137) 4 seconds ago"

	err := finish(t, test.start(t, nil))
	if err == nil {
		t.Fatal("a shell was started in a stopped container")
	}
	if !strings.Contains(err.Error(), "Exited (137)") {
		t.Errorf("the refusal does not carry the engine's status: %v", err)
	}
}

func TestAPtyThatCannotBeOpenedIsReportedRatherThanFramed(t *testing.T) {
	test := newHarness(t)
	test.engine.openErr = errors.New("no such exec")

	err := finish(t, test.start(t, nil))
	if err == nil {
		t.Fatal("a session that never attached was reported as a success")
	}
	if len(test.stream.frames()) != 0 {
		t.Error("frames were sent for a session that never attached")
	}
	if !test.stream.sendClosed() {
		t.Error("the stream was left open after a failed attach")
	}
}

// A session that ended gives its slot back, whether it attached or not.
func TestSessionSlotsAreReleased(t *testing.T) {
	test := newHarness(t)

	finished := test.start(t, nil)
	eventually(t, "the session to attach", func() bool { return test.host.live.count() == 1 })
	test.pty.stop()
	if err := finish(t, finished); err != nil {
		t.Fatalf("the session failed: %v", err)
	}
	if held := test.host.live.count(); held != 0 {
		t.Fatalf("%d session slots are still held after the session ended", held)
	}

	test.engine.openErr = errors.New("no such exec")
	if err := finish(t, test.start(t, nil)); err == nil {
		t.Fatal("expected the second session to fail to attach")
	}
	if held := test.host.live.count(); held != 0 {
		t.Fatalf("%d session slots are still held after a failed attach", held)
	}
}

func TestAHostWithNoEngineIsRefusedAtConstruction(t *testing.T) {
	if _, err := New(Options{}); err == nil {
		t.Fatal("a terminal host with no container runtime was accepted")
	}
}
