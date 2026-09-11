package rpc

import (
	"context"
	"testing"

	"google.golang.org/protobuf/types/known/timestamppb"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

func TestStatsAndLogsReachThePanel(t *testing.T) {
	panel := startPanel(t)
	node := newFakeNode()
	client, _ := startNode(t, panel.credential(), node)

	if !client.SendStat(&wisperpb.StatSample{
		TakenAt:       timestamppb.Now(),
		IntervalNanos: int64(15e9),
		Subject:       &wisperpb.StatSample_Node{Node: &wisperpb.NodeSample{CpuNanos: 42}},
	}) {
		t.Fatal("the sample was refused with an empty queue")
	}
	sample := receive(t, panel.samples, "the sample never reached the panel")
	if sample.GetNodeId() != client.NodeID() {
		t.Errorf("sample node id = %q, want it filled in by the client", sample.GetNodeId())
	}

	if !client.SendLog(&wisperpb.LogChunk{
		StreamId: "stream-1",
		Source:   wisperpb.LogSource_LOG_SOURCE_BUILD,
		Data:     []byte("compiling\n"),
		At:       timestamppb.Now(),
	}) {
		t.Fatal("the log chunk was refused with an empty queue")
	}
	chunk := receive(t, panel.logChunks, "the log chunk never reached the panel")
	if string(chunk.GetData()) != "compiling\n" {
		t.Errorf("chunk data = %q", chunk.GetData())
	}
}

func TestStatusBatchesAreAcknowledged(t *testing.T) {
	panel := startPanel(t)
	node := newFakeNode()
	client, _ := startNode(t, panel.credential(), node)
	panel.session(t)

	acknowledgement, err := client.ReportStatus(context.Background(), &wisperpb.StatusBatch{
		AppliedGeneration: 12,
		Health:            wisperpb.NodeHealth_NODE_HEALTH_HEALTHY,
	})
	if err != nil {
		t.Fatalf("report status: %v", err)
	}
	if acknowledgement.GetAccepted() != 1 {
		t.Errorf("accepted = %d, want 1", acknowledgement.GetAccepted())
	}

	batch := receive(t, panel.statusBatches, "the status batch never arrived")
	if batch.GetNodeId() != client.NodeID() {
		t.Errorf("batch node id = %q, want it filled in by the client", batch.GetNodeId())
	}
	if batch.GetObservedAt() == nil {
		t.Error("a batch with no observation time cannot be ordered against the next one")
	}
}
