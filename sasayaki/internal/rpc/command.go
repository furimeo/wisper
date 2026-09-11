package rpc

import (
	"context"
	"fmt"
	"log/slog"
	"runtime/debug"
	"time"

	"google.golang.org/protobuf/types/known/timestamppb"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// command runs one thing the panel asked for and answers with a CommandResult.
//
// Three properties, each of which is a bug that would otherwise happen:
//
//   - It runs on the daemon's context, not the stream's. A restore takes twenty minutes
//     and the tunnel will not last that long; killing the work because the connection
//     blinked would leave a half-restored volume behind.
//   - Its result goes through Client.send, which queues when there is no stream. So the
//     answer to a command whose stream died is delivered on the next one.
//   - The same command id is never run twice at once. A panel that repeated a command
//     it never saw answered gets one execution and one result, not two backups.
//
// No timeout is imposed here. The commands that need one carry their own
// (StartBuild.timeout_seconds, RunBackup.timeout_seconds), and a second timeout in a
// second place is two numbers that will eventually disagree.
func (c *Client) command(
	ctx context.Context,
	commandID string,
	description string,
	run func(context.Context) (*wisperpb.CommandResult, error),
) {
	if !c.beginCommand(commandID) {
		c.log.Warn("this command is already running, ignoring the repeat",
			slog.String("command_id", commandID), slog.String("command", description))
		return
	}

	go func() {
		defer c.endCommand(commandID)

		started := time.Now()
		c.log.Info("running command", slog.String("command", description),
			slog.String("command_id", commandID))

		result := c.execute(ctx, description, run)
		result.CommandId = commandID
		result.FinishedAt = timestamppb.Now()

		level := slog.LevelInfo
		if !result.GetOk() {
			level = slog.LevelWarn
		}
		c.log.Log(ctx, level, "command finished",
			slog.String("command", description),
			slog.Bool("ok", result.GetOk()),
			slog.Duration("took", time.Since(started)),
			slog.String("detail", result.GetDetail()))

		c.send(&wisperpb.NodeMessage{
			Payload: &wisperpb.NodeMessage_CommandResult{CommandResult: result},
		})
	}()
}

// execute calls the handler and turns anything it does into a result.
//
// Including a panic. The daemon is crash-only about its own state - the truth is on
// disk - but a nil map in the backup code must not take down routing for every customer
// on the machine. The panic is logged with its stack and reported as a failed command,
// which is both more honest and more useful than a process that vanished.
func (c *Client) execute(
	ctx context.Context,
	description string,
	run func(context.Context) (*wisperpb.CommandResult, error),
) (result *wisperpb.CommandResult) {
	defer func() {
		if recovered := recover(); recovered != nil {
			c.log.Error("command panicked",
				slog.String("command", description),
				slog.Any("panic", recovered),
				slog.String("stack", string(debug.Stack())))
			result = &wisperpb.CommandResult{
				Ok:     false,
				Detail: fmt.Sprintf("the node failed internally handling this command: %v", recovered),
			}
		}
	}()

	var err error
	result, err = run(ctx)
	if err != nil {
		return &wisperpb.CommandResult{Ok: false, Detail: err.Error()}
	}
	if result == nil {
		// A handler that reported neither an outcome nor an error did the work.
		return &wisperpb.CommandResult{Ok: true}
	}
	return result
}

// beginCommand claims a command id. It returns false when the same id is already being
// executed, which happens when the panel repeats a command it never saw answered.
func (c *Client) beginCommand(commandID string) bool {
	if commandID == "" {
		return true
	}
	c.running.Lock()
	defer c.running.Unlock()
	if _, busy := c.runningID[commandID]; busy {
		return false
	}
	c.runningID[commandID] = struct{}{}
	return true
}

func (c *Client) endCommand(commandID string) {
	if commandID == "" {
		return
	}
	c.running.Lock()
	defer c.running.Unlock()
	delete(c.runningID, commandID)
}
