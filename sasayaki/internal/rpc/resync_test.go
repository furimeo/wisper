package rpc

import (
	"testing"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Resync is the whole reason nothing on this stream carries a delta.
//
// A node that was gone for a week and a node that reconnected twice in a second are
// caught up by the same document, so the only thing the node has to get right is saying
// where it is and then taking whatever comes back - including a generation it has
// already seen (docs/contracts/node-spec.md sections 2 and 3).

func TestReconnectAnnouncesWhereTheNodeGotTo(t *testing.T) {
	panel := startPanel(t)
	node := newFakeNode()

	startNode(t, panel.credential(), node)
	first := panel.session(t)

	if got := first.hello.GetAppliedGeneration(); got != 0 {
		t.Fatalf("a node holding nothing announced generation %d, want 0", got)
	}

	first.send(t, &wisperpb.PanelMessage{
		Payload: &wisperpb.PanelMessage_ApplySpec{ApplySpec: &wisperpb.ApplySpec{
			Spec:   &wisperpb.NodeSpec{Generation: 47},
			Reason: "deployment 412",
		}},
	})
	if stored := receive(t, node.specs, "the spec never reached the node"); stored.GetGeneration() != 47 {
		t.Fatalf("stored generation %d, want 47", stored.GetGeneration())
	}

	// The tunnel drops. The panel has no route back, so what it knows about this node on
	// the next stream is only what the next hello tells it.
	first.stop()
	second := panel.session(t)

	if got := second.hello.GetAppliedGeneration(); got != 47 {
		t.Errorf("the reconnect announced generation %d, want 47: the panel decides what to "+
			"resend from this number, and a node that understates it is a node that gets "+
			"handed work it already did", got)
	}
}

// The panel resends the whole spec on every reconnect whatever the two generations are,
// so the same number arriving twice is the ordinary case rather than a duplicate to
// discard. It has to be stored again: the bytes can differ while the number does not,
// and the disk may have drifted since the last pass (node-spec.md section 2).
func TestAResentGenerationIsAcceptedRatherThanSkipped(t *testing.T) {
	panel := startPanel(t)
	node := newFakeNode()

	startNode(t, panel.credential(), node)
	first := panel.session(t)

	resend := &wisperpb.PanelMessage{
		Payload: &wisperpb.PanelMessage_ApplySpec{ApplySpec: &wisperpb.ApplySpec{
			Spec:   &wisperpb.NodeSpec{Generation: 47, ReconcileIntervalSeconds: 15},
			Reason: "reconnect",
		}},
	}

	first.send(t, resend)
	receive(t, node.specs, "the first spec never reached the node")

	first.stop()
	second := panel.session(t)
	second.send(t, resend)

	again := receive(t, node.specs, "the resent spec was dropped instead of being applied again")
	if again.GetGeneration() != 47 {
		t.Errorf("stored generation %d, want 47", again.GetGeneration())
	}

	answer := second.expect(t, "a SpecApplied for the resend", func(m *wisperpb.NodeMessage) bool {
		return m.GetSpecApplied() != nil
	}).GetSpecApplied()
	if !answer.GetAccepted() {
		t.Errorf("the resend was refused with %q: equal to stored means accept, "+
			"and a node that refuses one never converges after a flapping tunnel",
			answer.GetRejectedReason())
	}
}
