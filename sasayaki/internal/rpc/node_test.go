package rpc

import (
	"context"
	"errors"
	"sync/atomic"
	"time"

	"google.golang.org/protobuf/types/known/timestamppb"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// fakeNode stands in for every package this one dispatches to: state, reconcile, build,
// backup, dbengine, terminal, stats, files and bootstrap.
//
// One type implementing all eleven interfaces rather than eleven types, because a test
// asserting that a build was started and its result delivered needs both halves in one
// place, and because the interfaces are small enough that the whole fake is shorter
// than the constructors for the alternative.
type fakeNode struct {
	appliedGeneration atomic.Uint64
	helloError        error

	heartbeats atomic.Int64
	specs      chan *wisperpb.NodeSpec
	specError  error
	reconciles chan string
	drains     chan *wisperpb.DrainNode

	builds        chan *wisperpb.StartBuild
	buildGate     chan struct{}
	backups       chan *wisperpb.RunBackup
	restores      chan *wisperpb.RestoreBackup
	provisions    chan *wisperpb.ProvisionDatabase
	rotations     chan *wisperpb.RotateDatabasePassword
	dropped       chan *wisperpb.DropDatabase
	upgrades      chan *wisperpb.UpgradeNode
	logStarts     chan *wisperpb.LogRequest
	logStops      chan string
	terminals     chan *wisperpb.StartTerminal
	terminalError error
	fileHandler   func(context.Context, *wisperpb.FileRequest, FileEvents) error
	files         chan *wisperpb.FileRequest
}

func newFakeNode() *fakeNode {
	return &fakeNode{
		specs:      make(chan *wisperpb.NodeSpec, 8),
		reconciles: make(chan string, 8),
		drains:     make(chan *wisperpb.DrainNode, 4),
		builds:     make(chan *wisperpb.StartBuild, 4),
		buildGate:  make(chan struct{}),
		backups:    make(chan *wisperpb.RunBackup, 4),
		restores:   make(chan *wisperpb.RestoreBackup, 4),
		provisions: make(chan *wisperpb.ProvisionDatabase, 4),
		rotations:  make(chan *wisperpb.RotateDatabasePassword, 4),
		dropped:    make(chan *wisperpb.DropDatabase, 4),
		upgrades:   make(chan *wisperpb.UpgradeNode, 4),
		logStarts:  make(chan *wisperpb.LogRequest, 4),
		logStops:   make(chan string, 4),
		terminals:  make(chan *wisperpb.StartTerminal, 4),
		files:      make(chan *wisperpb.FileRequest, 8),
	}
}

func (f *fakeNode) handlers() Handlers {
	return Handlers{
		Hello:     f,
		Heartbeat: f,
		Spec:      f,
		Reconcile: f,
		Terminals: f,
		Builds:    f,
		Backups:   f,
		Databases: f,
		Upgrades:  f,
		Logs:      f,
		Files:     f,
	}
}

func (f *fakeNode) Hello(context.Context) (*wisperpb.NodeHello, error) {
	if f.helloError != nil {
		return nil, f.helloError
	}
	return &wisperpb.NodeHello{
		AppliedGeneration: f.appliedGeneration.Load(),
		Machine:           &wisperpb.MachineFacts{Hostname: "test-node", CpuCores: 4},
		Doctor:            &wisperpb.DoctorReport{RequiredChecksPassed: true},
	}, nil
}

func (f *fakeNode) Heartbeat(context.Context) *wisperpb.Heartbeat {
	f.heartbeats.Add(1)
	return &wisperpb.Heartbeat{
		AppliedGeneration: f.appliedGeneration.Load(),
		Health:            wisperpb.NodeHealth_NODE_HEALTH_HEALTHY,
		RunningWorkloads:  1,
	}
}

func (f *fakeNode) ApplySpec(_ context.Context, spec *wisperpb.NodeSpec, _ string) error {
	if f.specError != nil {
		return f.specError
	}
	f.appliedGeneration.Store(spec.GetGeneration())
	f.specs <- spec
	return nil
}

func (f *fakeNode) ReconcileNow(reason string) { f.reconciles <- reason }

func (f *fakeNode) Drain(_ context.Context, request *wisperpb.DrainNode) (*wisperpb.DrainReport, error) {
	f.drains <- request
	return &wisperpb.DrainReport{EvacuatedWorkloads: []string{"w-1"}, Complete: true}, nil
}

func (f *fakeNode) Serve(ctx context.Context, request *wisperpb.StartTerminal, stream TerminalStream) error {
	f.terminals <- request
	if f.terminalError != nil {
		return f.terminalError
	}
	if err := stream.Send(&wisperpb.TerminalFrame{
		SessionId: request.GetSessionId(),
		Payload: &wisperpb.TerminalFrame_Attached{Attached: &wisperpb.TerminalAttached{
			WorkloadId:  request.GetWorkloadId(),
			ContainerId: "container-1",
			Cols:        request.GetInitialCols(),
			Rows:        request.GetInitialRows(),
			AttachedAt:  timestamppb.Now(),
		}},
	}); err != nil {
		return err
	}
	<-ctx.Done()
	return nil
}

// Build blocks until the test releases the gate, which is how "the stream dropped while
// a command was running" is made to happen at a known moment.
func (f *fakeNode) Build(ctx context.Context, request *wisperpb.StartBuild) (*wisperpb.BuildCompleted, error) {
	f.builds <- request
	select {
	case <-f.buildGate:
	case <-ctx.Done():
		return nil, ctx.Err()
	case <-time.After(howLong):
		return nil, errors.New("the test never released the build")
	}
	return &wisperpb.BuildCompleted{
		BuildId:   request.GetBuildId(),
		Success:   true,
		ReleaseId: "release-1",
		Detail:    "built",
	}, nil
}

func (f *fakeNode) RunBackup(_ context.Context, request *wisperpb.RunBackup) (*wisperpb.BackupCompleted, error) {
	f.backups <- request
	return &wisperpb.BackupCompleted{BackupId: request.GetBackupId(), Success: true, RestorePointId: "rp-1"}, nil
}

func (f *fakeNode) RestoreBackup(_ context.Context, request *wisperpb.RestoreBackup) (*wisperpb.RestoreCompleted, error) {
	f.restores <- request
	return &wisperpb.RestoreCompleted{RestoreId: request.GetRestoreId(), Success: true}, nil
}

func (f *fakeNode) ProvisionDatabase(_ context.Context, request *wisperpb.ProvisionDatabase) (*wisperpb.DatabaseProvisioned, error) {
	f.provisions <- request
	return &wisperpb.DatabaseProvisioned{
		Id:           request.GetGrant().GetId(),
		DatabaseName: request.GetGrant().GetDatabaseName(),
		Host:         "127.0.0.1",
		Port:         5432,
	}, nil
}

func (f *fakeNode) RotateDatabasePassword(_ context.Context, request *wisperpb.RotateDatabasePassword) error {
	f.rotations <- request
	return nil
}

func (f *fakeNode) DropDatabase(_ context.Context, request *wisperpb.DropDatabase) error {
	f.dropped <- request
	return nil
}

func (f *fakeNode) Upgrade(_ context.Context, request *wisperpb.UpgradeNode) (*wisperpb.UpgradeResult, error) {
	f.upgrades <- request
	return &wisperpb.UpgradeResult{
		PreviousVersion: "v0.0.1",
		NewVersion:      request.GetTargetVersion(),
		RolledBack:      false,
		Detail:          "upgraded",
	}, nil
}

func (f *fakeNode) StartLogStream(_ context.Context, request *wisperpb.LogRequest) error {
	f.logStarts <- request
	return nil
}

func (f *fakeNode) StopLogStream(streamID, _ string) { f.logStops <- streamID }

func (f *fakeNode) Handle(ctx context.Context, request *wisperpb.FileRequest, events FileEvents) error {
	f.files <- request
	if f.fileHandler != nil {
		return f.fileHandler(ctx, request, events)
	}
	return events.Send(&wisperpb.FileEvent{
		RequestId: request.GetRequestId(),
		Result:    &wisperpb.FileEvent_Done{Done: &wisperpb.OperationDone{FinishedAt: timestamppb.Now()}},
	})
}
