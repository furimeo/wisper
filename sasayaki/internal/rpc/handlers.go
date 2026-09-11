package rpc

import (
	"context"
	"errors"
	"fmt"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// What this package needs from the rest of the daemon.
//
// Every interface below is declared here, by the consumer, and implemented in the
// package that owns the work: the spec belongs to state, terminals to terminal, builds
// to build, and so on. Declaring them at the point of use is what keeps the dependency
// pointing one way - rpc knows what a command is, and the packages that do the work
// know nothing about gRPC at all. It is also what makes this package testable against a
// panel and a set of fakes, with no Docker anywhere.
//
// None of them is optional. Handlers.validate refuses a nil field at construction,
// because a command the panel can send and nobody answers is a stub with extra steps.

// HelloSource describes this node in the first frame of every control stream.
//
// It is asked again on every reconnect, not just at startup: RAM gets added, disks get
// replaced with smaller ones and runsc gets uninstalled, and a panel placing workloads
// against facts from a week ago places them onto a machine that cannot hold them.
type HelloSource interface {
	// Hello fills everything except protocol_version and fresh_start, which belong to
	// the connection rather than to the node and are stamped here.
	//
	// Return an error only when the node genuinely cannot describe itself. A doctor
	// check that failed is content for the report, not an error: the panel needs to be
	// told about a node with no runsc, and a node that refuses to connect because it is
	// unhealthy is a node nobody can see is unhealthy.
	Hello(ctx context.Context) (*wisperpb.NodeHello, error)
}

// HeartbeatSource answers "is this node alive, and is it keeping up".
type HeartbeatSource interface {
	// Heartbeat is called on the interval the panel asked for. It must not block: a
	// heartbeat that waits on Docker turns a Docker outage into a node the panel
	// believes is dead. Report NODE_HEALTH_DEGRADED instead.
	//
	// sent_at is stamped here, so clock skew is measured against the moment the frame
	// left rather than the moment the numbers in it were gathered.
	Heartbeat(ctx context.Context) *wisperpb.Heartbeat
}

// ErrSpecSuperseded is what a SpecReceiver returns for a spec older than the one it has
// already applied. It is a normal event on a flapping tunnel - the panel resends the
// whole spec on every reconnect - so it is answered with SpecApplied{accepted: false}
// and logged calmly, not as a failure.
//
// The storage layer has its own name for the same refusal, state.ErrSupersededGeneration,
// and the two are deliberately not the same value: rpc must not import state, or the
// transport and the disk stop being separable. Whoever implements SpecReceiver in the
// composition root therefore has to translate - errors.Is against the state sentinel,
// wrapped in this one. Skipping that step does not break the acknowledgement, which is
// why it is written down here: it downgrades a routine resend into an "could not store
// the spec" line at error level, and an operator then goes looking for a disk fault that
// does not exist.
var ErrSpecSuperseded = errors.New("spec generation is below the applied generation")

// SpecReceiver takes the desired state and writes it down.
type SpecReceiver interface {
	// ApplySpec stores the spec durably and returns. It does not converge: the panel is
	// waiting for an acknowledgement that the spec arrived and was understood, and
	// convergence takes minutes while an acknowledgement must take milliseconds. The
	// reconcile loop picks it up from disk on its next pass.
	//
	// Called on the control stream's read goroutine, one spec at a time, so
	// generations cannot be applied out of order.
	ApplySpec(ctx context.Context, spec *wisperpb.NodeSpec, reason string) error
}

// Reconciler is the convergence loop, as seen from the control stream.
type Reconciler interface {
	// ReconcileNow wakes the loop early. It must return immediately - it is a nudge, not
	// the work - and losing it entirely must be harmless, because the fifteen-second
	// tick would have got there anyway.
	ReconcileNow(reason string)

	// Drain stops accepting new workloads and, when the request says so, stops the ones
	// with no volume so the panel can place them elsewhere. Anything holding a volume is
	// listed in the report and never moved: a silent migration is silent data loss
	// (design section 7.7).
	Drain(ctx context.Context, request *wisperpb.DrainNode) (*wisperpb.DrainReport, error)
}

// TerminalStream is the terminal package's view of the gRPC stream this package opened
// for it. Exactly the three methods a session needs, so there is no way to write an
// unframed byte at it - which is the mistake that broke the predecessor's terminal.
type TerminalStream interface {
	Send(*wisperpb.TerminalFrame) error
	Recv() (*wisperpb.TerminalFrame, error)
	CloseSend() error
}

// TerminalHost runs one interactive session.
type TerminalHost interface {
	// Serve owns the stream from the first frame to the last: it sends TerminalAttached,
	// pumps bytes both ways, honours resize frames, and finishes by sending
	// TerminalExit. Returning is what closes the session down.
	//
	// The stream is already open when this is called, so a failure to attach is reported
	// by returning an error and the panel is told the command failed rather than waiting
	// for a terminal that will never appear.
	Serve(ctx context.Context, request *wisperpb.StartTerminal, stream TerminalStream) error
}

// Builder turns a source tree into something runnable (design section 5.5).
type Builder interface {
	// Build runs to completion and returns what happened. It honours the timeout in the
	// request; this package imposes none of its own, because two timeouts on one
	// operation eventually disagree.
	Build(ctx context.Context, request *wisperpb.StartBuild) (*wisperpb.BuildCompleted, error)
}

// BackupRunner takes snapshots and puts them back (design section 8.3).
type BackupRunner interface {
	RunBackup(ctx context.Context, request *wisperpb.RunBackup) (*wisperpb.BackupCompleted, error)
	RestoreBackup(ctx context.Context, request *wisperpb.RestoreBackup) (*wisperpb.RestoreCompleted, error)
}

// DatabaseOperator manages customer databases inside the shared engines on this node
// (design section 8.1).
type DatabaseOperator interface {
	// ProvisionDatabase creates the database, the role and the grant. The password comes
	// from the panel and is never sent back: the reply carries what a connection string
	// needs and nothing more.
	ProvisionDatabase(ctx context.Context, request *wisperpb.ProvisionDatabase) (*wisperpb.DatabaseProvisioned, error)
	RotateDatabasePassword(ctx context.Context, request *wisperpb.RotateDatabasePassword) error
	DropDatabase(ctx context.Context, request *wisperpb.DropDatabase) error
}

// Upgrader replaces the running binary (design section 7.5).
type Upgrader interface {
	// Upgrade verifies the checksum before it replaces anything and rolls back to the
	// previous binary if the new one does not come up.
	//
	// A successful upgrade restarts the process, so this may never return and the panel
	// may never see a CommandResult for it. That is expected: the panel learns the
	// upgrade worked from the version in the next NodeHello. A result that does arrive
	// therefore usually means a rollback, which is precisely the case that needs saying
	// out loud.
	Upgrade(ctx context.Context, request *wisperpb.UpgradeNode) (*wisperpb.UpgradeResult, error)
}

// LogFeeds starts and stops the subscriptions whose output goes up the LogStream call.
type LogFeeds interface {
	// StartLogStream begins pushing chunks for request.stream_id through Client.SendLog.
	// It returns as soon as the subscription exists; the reading happens elsewhere.
	StartLogStream(ctx context.Context, request *wisperpb.LogRequest) error

	// StopLogStream ends one subscription. It is called for a stream that has already
	// finished - the last browser closing and the container exiting race - so it must be
	// idempotent and must not report an unknown stream id as a problem.
	StopLogStream(streamID string, reason string)
}

// FileEvents is where a file operation writes its answers. Sends are serialised across
// every concurrent operation on the node, so an implementation may call it from any
// goroutine.
type FileEvents interface {
	Send(*wisperpb.FileEvent) error
}

// FileHost performs one file operation for the web file manager (design section 8.2).
type FileHost interface {
	// Handle runs one request and emits every event answering it, finishing with an
	// OperationDone or a FileError - the panel cannot tell "finished" from "still
	// working" otherwise.
	//
	// Each request gets its own goroutine and its own context. A CancelRequest from the
	// panel cancels that context, so an operation that can be interrupted - a large
	// read, a directory walk - must watch ctx.Done() and finish with
	// FILE_ERROR_CODE_CANCELLED. Cancel itself never reaches this method; it is answered
	// by the stream.
	//
	// Returning an error is for the case where no FileError could be sent. Anything the
	// customer should see belongs in a FileError event.
	Handle(ctx context.Context, request *wisperpb.FileRequest, events FileEvents) error
}

// Handlers is the whole of what the daemon injects. One struct rather than eleven
// constructor parameters, so adding a command is a field rather than a signature change
// in the composition root.
type Handlers struct {
	Hello     HelloSource
	Heartbeat HeartbeatSource
	Spec      SpecReceiver
	Reconcile Reconciler
	Terminals TerminalHost
	Builds    Builder
	Backups   BackupRunner
	Databases DatabaseOperator
	Upgrades  Upgrader
	Logs      LogFeeds
	Files     FileHost
}

// validate is called by New. A missing handler is caught when the daemon starts, in one
// error naming the field, rather than as a nil dereference the first time a customer
// presses a button.
func (h Handlers) validate() error {
	missing := make([]string, 0, 11)
	check := func(name string, provided bool) {
		if !provided {
			missing = append(missing, name)
		}
	}
	check("Hello", h.Hello != nil)
	check("Heartbeat", h.Heartbeat != nil)
	check("Spec", h.Spec != nil)
	check("Reconcile", h.Reconcile != nil)
	check("Terminals", h.Terminals != nil)
	check("Builds", h.Builds != nil)
	check("Backups", h.Backups != nil)
	check("Databases", h.Databases != nil)
	check("Upgrades", h.Upgrades != nil)
	check("Logs", h.Logs != nil)
	check("Files", h.Files != nil)

	if len(missing) > 0 {
		return fmt.Errorf("rpc.Handlers is missing %v: the panel can send every one of these commands, "+
			"so leaving one unanswered is a node that ignores it silently", missing)
	}
	return nil
}
