package rpc

import (
	"context"
	"errors"
	"fmt"
	"log/slog"

	"google.golang.org/protobuf/types/known/timestamppb"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// dispatch turns one frame from the panel into work.
//
// Two shapes of frame, and the difference matters:
//
//   - The ones with no reply - a spec, a nudge, a log subscription - are handled on this
//     goroutine. They are supposed to be fast, and doing them in order is free
//     correctness: two specs can never be applied backwards.
//   - The ones that answer with a CommandResult - builds, backups, databases, drains,
//     upgrades - each get their own goroutine and the daemon's context, not the
//     stream's. A build survives the tunnel dropping underneath it, and its result is
//     queued for whichever stream is up when it finishes.
func (s *session) dispatch(ctx context.Context, message *wisperpb.PanelMessage) {
	client := s.client
	commandID := message.GetCommandId()

	switch payload := message.GetPayload().(type) {
	case *wisperpb.PanelMessage_Hello:
		// The handshake already happened. A second hello is not worth dropping a
		// working stream over.
		client.log.Debug("panel sent a second hello, ignoring it")

	case *wisperpb.PanelMessage_ApplySpec:
		s.applySpec(ctx, payload.ApplySpec)

	case *wisperpb.PanelMessage_ReconcileNow:
		reason := payload.ReconcileNow.GetReason()
		client.log.Info("panel asked for an early reconcile", slog.String("reason", reason))
		client.handlers.Reconcile.ReconcileNow(reason)

	case *wisperpb.PanelMessage_StartLogStream:
		s.startLogStream(ctx, payload.StartLogStream)

	case *wisperpb.PanelMessage_StopLogStream:
		stop := payload.StopLogStream
		client.handlers.Logs.StopLogStream(stop.GetStreamId(), stop.GetReason())

	case *wisperpb.PanelMessage_StartTerminal:
		client.startTerminal(ctx, commandID, payload.StartTerminal)

	case *wisperpb.PanelMessage_StartBuild:
		request := payload.StartBuild
		client.command(ctx, commandID, "build "+request.GetBuildId(),
			func(ctx context.Context) (*wisperpb.CommandResult, error) {
				completed, err := client.handlers.Builds.Build(ctx, request)
				if err != nil {
					return nil, err
				}
				return &wisperpb.CommandResult{
					Ok:      completed.GetSuccess(),
					Detail:  completed.GetDetail(),
					Outcome: &wisperpb.CommandResult_Build{Build: completed},
				}, nil
			})

	case *wisperpb.PanelMessage_RunBackup:
		request := payload.RunBackup
		client.command(ctx, commandID, "backup "+request.GetBackupId(),
			func(ctx context.Context) (*wisperpb.CommandResult, error) {
				completed, err := client.handlers.Backups.RunBackup(ctx, request)
				if err != nil {
					return nil, err
				}
				return &wisperpb.CommandResult{
					Ok:      completed.GetSuccess(),
					Detail:  completed.GetDetail(),
					Outcome: &wisperpb.CommandResult_Backup{Backup: completed},
				}, nil
			})

	case *wisperpb.PanelMessage_RestoreBackup:
		request := payload.RestoreBackup
		client.command(ctx, commandID, "restore "+request.GetRestoreId(),
			func(ctx context.Context) (*wisperpb.CommandResult, error) {
				completed, err := client.handlers.Backups.RestoreBackup(ctx, request)
				if err != nil {
					return nil, err
				}
				return &wisperpb.CommandResult{
					Ok:      completed.GetSuccess(),
					Detail:  completed.GetDetail(),
					Outcome: &wisperpb.CommandResult_Restore{Restore: completed},
				}, nil
			})

	case *wisperpb.PanelMessage_ProvisionDatabase:
		request := payload.ProvisionDatabase
		client.command(ctx, commandID, "provision database "+request.GetGrant().GetId(),
			func(ctx context.Context) (*wisperpb.CommandResult, error) {
				provisioned, err := client.handlers.Databases.ProvisionDatabase(ctx, request)
				if err != nil {
					return nil, err
				}
				return &wisperpb.CommandResult{
					Ok:      true,
					Detail:  fmt.Sprintf("%s is ready", provisioned.GetDatabaseName()),
					Outcome: &wisperpb.CommandResult_Database{Database: provisioned},
				}, nil
			})

	case *wisperpb.PanelMessage_RotateDatabasePassword:
		request := payload.RotateDatabasePassword
		client.command(ctx, commandID, "rotate database password "+request.GetId(),
			func(ctx context.Context) (*wisperpb.CommandResult, error) {
				if err := client.handlers.Databases.RotateDatabasePassword(ctx, request); err != nil {
					return nil, err
				}
				return &wisperpb.CommandResult{
					Ok:     true,
					Detail: fmt.Sprintf("password changed for %s", request.GetUsername()),
				}, nil
			})

	case *wisperpb.PanelMessage_DropDatabase:
		request := payload.DropDatabase
		client.command(ctx, commandID, "drop database "+request.GetDatabaseName(),
			func(ctx context.Context) (*wisperpb.CommandResult, error) {
				if err := client.handlers.Databases.DropDatabase(ctx, request); err != nil {
					return nil, err
				}
				detail := fmt.Sprintf("dropped the login for %s", request.GetUsername())
				if request.GetDropData() {
					detail = fmt.Sprintf("dropped %s and its login", request.GetDatabaseName())
				}
				return &wisperpb.CommandResult{Ok: true, Detail: detail}, nil
			})

	case *wisperpb.PanelMessage_Drain:
		request := payload.Drain
		client.command(ctx, commandID, "drain",
			func(ctx context.Context) (*wisperpb.CommandResult, error) {
				report, err := client.handlers.Reconcile.Drain(ctx, request)
				if err != nil {
					return nil, err
				}
				return &wisperpb.CommandResult{
					Ok: true,
					Detail: fmt.Sprintf("evacuated %d, %d hold volumes and were left alone",
						len(report.GetEvacuatedWorkloads()), len(report.GetPinnedWorkloads())),
					Outcome: &wisperpb.CommandResult_Drain{Drain: report},
				}, nil
			})

	case *wisperpb.PanelMessage_Upgrade:
		request := payload.Upgrade
		client.command(ctx, commandID, "upgrade to "+request.GetTargetVersion(),
			func(ctx context.Context) (*wisperpb.CommandResult, error) {
				result, err := client.handlers.Upgrades.Upgrade(ctx, request)
				if err != nil {
					return nil, err
				}
				return &wisperpb.CommandResult{
					Ok:      !result.GetRolledBack(),
					Detail:  result.GetDetail(),
					Outcome: &wisperpb.CommandResult_Upgrade{Upgrade: result},
				}, nil
			})

	case nil:
		client.log.Warn("panel sent an empty frame")

	default:
		// Unreachable while both ends agree on the protocol version, which is what the
		// handshake is for. Logged rather than fatal: a frame this node cannot read is
		// not a reason to drop the ones it can.
		client.log.Warn("panel sent a frame this node does not understand",
			slog.String("frame", fmt.Sprintf("%T", payload)))
	}
}

// applySpec stores the desired state and acknowledges it.
//
// Inline on the read goroutine, and deliberately so: the acknowledgement says
// "received, understood, stored", nothing more, and doing it here means generation 48
// can never be stored before generation 47.
func (s *session) applySpec(ctx context.Context, request *wisperpb.ApplySpec) {
	client := s.client
	spec := request.GetSpec()
	generation := spec.GetGeneration()

	applied := &wisperpb.SpecApplied{
		Generation: generation,
		Accepted:   true,
		ReceivedAt: timestamppb.Now(),
	}

	if spec == nil {
		// Refused here rather than handed on: "apply nothing" and "run nothing" are
		// different instructions, and only one of them has a spec behind it.
		client.log.Error("panel sent an ApplySpec with no spec in it")
		applied.Accepted = false
		applied.RejectedReason = "the ApplySpec carried no spec"
		client.send(&wisperpb.NodeMessage{
			Payload: &wisperpb.NodeMessage_SpecApplied{SpecApplied: applied},
		})
		return
	}

	if err := client.handlers.Spec.ApplySpec(ctx, spec, request.GetReason()); err != nil {
		applied.Accepted = false
		applied.RejectedReason = err.Error()
		if errors.Is(err, ErrSpecSuperseded) {
			// Routine: the panel resends the whole spec on every reconnect, and a node
			// that reconnected twice quickly sees the older one arrive second.
			client.log.Info("ignoring a superseded spec", slog.Uint64("generation", generation))
		} else {
			client.log.Error("could not store the spec",
				slog.Uint64("generation", generation), slog.String("error", err.Error()))
		}
	} else {
		client.log.Info("spec stored",
			slog.Uint64("generation", generation), slog.String("reason", request.GetReason()))
	}

	client.send(&wisperpb.NodeMessage{
		Payload: &wisperpb.NodeMessage_SpecApplied{SpecApplied: applied},
	})
}

// startLogStream opens one subscription. A failure is answered with an ended chunk
// rather than with silence: the panel would otherwise hold a subscription open for
// output that is never coming, and the customer would watch an empty pane.
func (s *session) startLogStream(ctx context.Context, request *wisperpb.LogRequest) {
	client := s.client
	if err := client.handlers.Logs.StartLogStream(ctx, request); err != nil {
		client.log.Error("could not start a log subscription",
			slog.String("stream_id", request.GetStreamId()), slog.String("error", err.Error()))
		client.SendLog(&wisperpb.LogChunk{
			StreamId:  request.GetStreamId(),
			Source:    request.GetSource(),
			SubjectId: request.GetSubjectId(),
			Kind:      wisperpb.LogStreamKind_LOG_STREAM_KIND_STDERR,
			Data:      []byte("this node could not start the log stream: " + err.Error() + "\n"),
			At:        timestamppb.Now(),
			End:       true,
		})
	}
}
