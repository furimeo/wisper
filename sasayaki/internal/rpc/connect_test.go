package rpc

import (
	"testing"
	"time"

	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"

	"github.com/furimeo/wisper/sasayaki/internal/version"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

func TestHandshakeCarriesTheCredentialAndTheProtocol(t *testing.T) {
	panel := startPanel(t)
	node := newFakeNode()
	node.appliedGeneration.Store(47)

	client, _ := startNode(t, panel.credential(), node)
	session := panel.session(t)

	if got := session.hello.GetProtocolVersion(); got != uint32(version.Protocol) {
		t.Errorf("hello announced protocol %d, want %d", got, version.Protocol)
	}
	if !session.hello.GetFreshStart() {
		t.Error("the first connection of a process must say so: the panel logs a restart differently from a reconnect")
	}
	if got := session.hello.GetAppliedGeneration(); got != 47 {
		t.Errorf("hello announced generation %d, want 47 - the panel decides whether to resend from this", got)
	}
	if session.hello.GetMachine() == nil || session.hello.GetDoctor() == nil {
		t.Error("hello must carry the machine facts and the doctor report on every connection, not only at enrolment")
	}

	metadata := panel.lastMetadata()
	if got := metadata.Get(MetadataNodeID); len(got) != 1 || got[0] != client.NodeID() {
		t.Errorf("%s = %v, want the node id", MetadataNodeID, got)
	}
	if got := metadata.Get(MetadataNodeToken); len(got) != 1 || got[0] != "a-long-lived-credential" {
		t.Errorf("%s carried %v, want the credential", MetadataNodeToken, got)
	}
	if got := metadata.Get(MetadataProtocol); len(got) != 1 || got[0] != "1" {
		t.Errorf("%s = %v, want the protocol version so the panel can refuse before parsing a frame", MetadataProtocol, got)
	}

	waitFor(t, "the client to report itself connected", client.Connected)
}

func TestSpecIsStoredAndAcknowledged(t *testing.T) {
	panel := startPanel(t)
	node := newFakeNode()

	startNode(t, panel.credential(), node)
	session := panel.session(t)

	session.send(t, &wisperpb.PanelMessage{
		CommandId: "cmd-1",
		Payload: &wisperpb.PanelMessage_ApplySpec{ApplySpec: &wisperpb.ApplySpec{
			Spec:   &wisperpb.NodeSpec{Generation: 12},
			Reason: "deployment 412",
		}},
	})

	stored := receive(t, node.specs, "the spec never reached the node")
	if stored.GetGeneration() != 12 {
		t.Errorf("stored generation %d, want 12", stored.GetGeneration())
	}

	answer := session.expect(t, "a SpecApplied", func(m *wisperpb.NodeMessage) bool {
		return m.GetSpecApplied() != nil
	}).GetSpecApplied()
	if !answer.GetAccepted() {
		t.Errorf("spec was rejected: %s", answer.GetRejectedReason())
	}
	if answer.GetGeneration() != 12 {
		t.Errorf("acknowledged generation %d, want 12", answer.GetGeneration())
	}
	if answer.GetReceivedAt() == nil {
		t.Error("SpecApplied must be stamped: the panel measures how long a node took to answer")
	}
}

func TestSupersededSpecIsRejectedWithItsReason(t *testing.T) {
	panel := startPanel(t)
	node := newFakeNode()
	node.specError = ErrSpecSuperseded

	startNode(t, panel.credential(), node)
	session := panel.session(t)

	session.send(t, &wisperpb.PanelMessage{
		Payload: &wisperpb.PanelMessage_ApplySpec{ApplySpec: &wisperpb.ApplySpec{
			Spec: &wisperpb.NodeSpec{Generation: 3},
		}},
	})

	answer := session.expect(t, "a SpecApplied", func(m *wisperpb.NodeMessage) bool {
		return m.GetSpecApplied() != nil
	}).GetSpecApplied()
	if answer.GetAccepted() {
		t.Fatal("a spec older than the applied one must not be accepted")
	}
	if answer.GetRejectedReason() == "" {
		t.Error("a rejection with no reason leaves the panel waiting for a convergence that will never come")
	}
}

// "Apply nothing" and "run nothing" are different instructions. An empty NodeSpec is
// the second one and is legitimate; a missing one is a mistake and is refused before it
// reaches the package that would have to guess.
func TestAnApplySpecWithNoSpecIsRefused(t *testing.T) {
	panel := startPanel(t)
	node := newFakeNode()

	startNode(t, panel.credential(), node)
	session := panel.session(t)

	session.send(t, &wisperpb.PanelMessage{
		Payload: &wisperpb.PanelMessage_ApplySpec{ApplySpec: &wisperpb.ApplySpec{Reason: "broken panel"}},
	})

	answer := session.expect(t, "a SpecApplied", func(m *wisperpb.NodeMessage) bool {
		return m.GetSpecApplied() != nil
	}).GetSpecApplied()
	if answer.GetAccepted() {
		t.Error("an ApplySpec with no spec was accepted")
	}
	select {
	case spec := <-node.specs:
		t.Errorf("the state package was handed %v", spec)
	default:
	}
}

func TestHeartbeatsFollowThePanelsInterval(t *testing.T) {
	panel := startPanel(t)
	panel.heartbeatSeconds = 1
	node := newFakeNode()

	startNode(t, panel.credential(), node)
	session := panel.session(t)

	beat := session.expect(t, "a heartbeat", func(m *wisperpb.NodeMessage) bool {
		return m.GetHeartbeat() != nil
	}).GetHeartbeat()

	if beat.GetSentAt() == nil {
		t.Error("a heartbeat with no timestamp cannot show clock skew, which is the other half of what it is for")
	}
	if beat.GetHealth() != wisperpb.NodeHealth_NODE_HEALTH_HEALTHY {
		t.Errorf("health = %v, want healthy", beat.GetHealth())
	}
}

func TestDaemonStartIsAnnouncedOnce(t *testing.T) {
	panel := startPanel(t)
	node := newFakeNode()

	startNode(t, panel.credential(), node)
	session := panel.session(t)

	event := session.expect(t, "a daemon-started event", func(m *wisperpb.NodeMessage) bool {
		return m.GetEvent().GetKind() == wisperpb.NodeEventKind_NODE_EVENT_KIND_DAEMON_STARTED
	}).GetEvent()
	if event.GetAt() == nil {
		t.Error("an event with no timestamp cannot be placed on a node's timeline")
	}

	// Reconnecting is not starting. The panel distinguishes a flaky tunnel from a node
	// in a restart loop, and this is where that distinction is made.
	session.stop()
	second := panel.session(t)
	if second.hello.GetFreshStart() {
		t.Error("a reconnect claimed to be a fresh start")
	}
}

func TestReconcileAndLogSubscriptionsNeedNoReply(t *testing.T) {
	panel := startPanel(t)
	node := newFakeNode()

	startNode(t, panel.credential(), node)
	session := panel.session(t)

	session.send(t, &wisperpb.PanelMessage{
		Payload: &wisperpb.PanelMessage_ReconcileNow{ReconcileNow: &wisperpb.ReconcileNow{Reason: "domain added"}},
	})
	if reason := receive(t, node.reconciles, "the reconcile nudge never arrived"); reason != "domain added" {
		t.Errorf("reconcile reason = %q", reason)
	}

	session.send(t, &wisperpb.PanelMessage{
		Payload: &wisperpb.PanelMessage_StartLogStream{StartLogStream: &wisperpb.LogRequest{
			StreamId: "stream-1",
			Source:   wisperpb.LogSource_LOG_SOURCE_CONTAINER,
			Follow:   true,
		}},
	})
	if started := receive(t, node.logStarts, "the log subscription never started"); started.GetStreamId() != "stream-1" {
		t.Errorf("stream id = %q", started.GetStreamId())
	}

	session.send(t, &wisperpb.PanelMessage{
		Payload: &wisperpb.PanelMessage_StopLogStream{StopLogStream: &wisperpb.StopLogStream{
			StreamId: "stream-1",
			Reason:   "nobody is watching",
		}},
	})
	if stopped := receive(t, node.logStops, "the log subscription was never stopped"); stopped != "stream-1" {
		t.Errorf("stopped stream id = %q", stopped)
	}
}

func TestProtocolMismatchKeepsTheStreamClosedAndSaysWhy(t *testing.T) {
	panel := startPanel(t)
	panel.protocol = uint32(version.Protocol) + 1
	node := newFakeNode()

	client, logs := startNode(t, panel.credential(), node)

	waitFor(t, "the node to give up on the first stream", func() bool {
		return panel.connectAttempts.Load() >= 2
	})
	if client.Connected() {
		t.Error("the node treated a version it cannot speak as a working stream")
	}
	if !logs.contains("needs upgrading") {
		t.Errorf("nothing in the log tells an operator to upgrade the node:\n%s", logs.dump())
	}

	// Never gives up. Even for this, because an operator upgrading the panel back is a
	// perfectly ordinary way for it to be fixed.
	before := panel.connectAttempts.Load()
	waitFor(t, "the node to keep trying", func() bool {
		return panel.connectAttempts.Load() > before
	})
}

func TestRejectedCredentialRetriesForeverAtTheCeiling(t *testing.T) {
	panel := startPanel(t)
	panel.connectStatus = status.Error(codes.Unauthenticated, "this credential was revoked")
	node := newFakeNode()

	_, logs := startNode(t, panel.credential(), node)

	waitFor(t, "the node to be refused twice", func() bool {
		return panel.connectAttempts.Load() >= 2
	})
	if !logs.contains("the panel refused this node") {
		t.Errorf("a refused credential must be reported as such, not as a network problem:\n%s", logs.dump())
	}

	// The ceiling in this test is 80ms, so several attempts inside a second prove it is
	// still trying and not spinning.
	time.Sleep(400 * time.Millisecond)
	attempts := panel.connectAttempts.Load()
	if attempts < 2 {
		t.Errorf("the node gave up after %d attempts", attempts)
	}
	if attempts > 40 {
		t.Errorf("the node made %d attempts in 400ms: it is not backing off", attempts)
	}
}
