package rpc

import (
	"context"
	"testing"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

func TestTerminalDialsBackWithTheSessionThePanelAskedFor(t *testing.T) {
	panel := startPanel(t)
	node := newFakeNode()
	startNode(t, panel.credential(), node)
	session := panel.session(t)

	session.send(t, &wisperpb.PanelMessage{
		CommandId: "cmd-terminal",
		Payload: &wisperpb.PanelMessage_StartTerminal{StartTerminal: &wisperpb.StartTerminal{
			SessionId:          "s-1",
			WorkloadId:         "w-1",
			InitialCols:        120,
			InitialRows:        40,
			IdleTimeoutSeconds: 600,
			MaxDurationSeconds: 3600,
		}},
	})

	terminal := receive(t, panel.terminals, "the node never dialled back with a terminal")
	attached := terminal.expect(t, "an attach frame", func(f *wisperpb.TerminalFrame) bool {
		return f.GetAttached() != nil
	})
	if attached.GetSessionId() != "s-1" {
		t.Errorf("session id = %q, want s-1: the panel joins the browser to the stream by it", attached.GetSessionId())
	}
	if attached.GetAttached().GetCols() != 120 {
		t.Errorf("cols = %d, want the size the browser asked for", attached.GetAttached().GetCols())
	}
}

// The stream itself is the acknowledgement, so only a failure needs one - otherwise a
// customer watches a blank pane until the panel's attach timeout expires.
func TestAFailedTerminalIsReportedOnTheControlStream(t *testing.T) {
	panel := startPanel(t)
	node := newFakeNode()
	node.terminalError = context.Canceled
	startNode(t, panel.credential(), node)
	session := panel.session(t)

	session.send(t, &wisperpb.PanelMessage{
		CommandId: "cmd-terminal",
		Payload: &wisperpb.PanelMessage_StartTerminal{StartTerminal: &wisperpb.StartTerminal{
			SessionId:  "s-2",
			WorkloadId: "w-1",
		}},
	})
	receive(t, node.terminals, "the terminal never started")

	result := resultFor(t, session, "cmd-terminal")
	if result.GetOk() {
		t.Error("a terminal that failed to attach was reported as a success")
	}
	if result.GetDetail() == "" {
		t.Error("a failure with no detail tells the customer nothing")
	}
}
