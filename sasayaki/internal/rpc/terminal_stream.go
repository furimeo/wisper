package rpc

import (
	"context"
	"log/slog"

	"google.golang.org/protobuf/types/known/timestamppb"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// startTerminal answers a StartTerminal command by dialling the stream that carries the
// session.
//
// The two-step exists because only the node can open a connection: the panel decides a
// terminal should exist and puts that on the control stream, and the node dials back
// with a Terminal call whose first frame names the session. The panel joins the two by
// session id.
//
// Success is not acknowledged on the control stream - the arriving stream is the
// acknowledgement, and the panel is waiting for it by session id rather than by command
// id. Failure is, because the alternative is a customer watching a blank terminal until
// the panel's attach timeout expires, with nothing anywhere saying why.
func (c *Client) startTerminal(ctx context.Context, commandID string, request *wisperpb.StartTerminal) {
	if !c.beginCommand(commandID) {
		c.log.Warn("this terminal is already opening, ignoring the repeat",
			slog.String("session_id", request.GetSessionId()))
		return
	}

	go func() {
		defer c.endCommand(commandID)

		log := c.log.With(
			slog.String("session_id", request.GetSessionId()),
			slog.String("workload_id", request.GetWorkloadId()))

		stream, err := c.panel.Terminal(ctx)
		if err != nil {
			log.Error("could not open the terminal stream", slog.String("error", err.Error()))
			c.failTerminal(commandID, "could not open the terminal stream: "+err.Error())
			return
		}

		log.Info("terminal session starting")
		if err := c.handlers.Terminals.Serve(ctx, request, stream); err != nil {
			log.Error("terminal session failed", slog.String("error", err.Error()))
			c.failTerminal(commandID, err.Error())
			return
		}
		log.Info("terminal session ended")
	}()
}

func (c *Client) failTerminal(commandID, detail string) {
	c.send(&wisperpb.NodeMessage{
		Payload: &wisperpb.NodeMessage_CommandResult{
			CommandResult: &wisperpb.CommandResult{
				CommandId:  commandID,
				Ok:         false,
				Detail:     detail,
				FinishedAt: timestamppb.Now(),
			},
		},
	})
}
