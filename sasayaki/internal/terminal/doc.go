// Package terminal is the web shell: one framed, bidirectional stream between a
// customer's browser and a pty inside their own container.
//
// There is no SSH, no SFTP and no WebDAV in wisper, so this is the only shell a customer
// will ever get, and most of them are holding a phone while they use it (design section
// 8.2).
//
// # Why every byte is inside a frame
//
// The predecessor pumped a pty through a WebSocket with io.Copy and no framing. Three
// things followed from that one decision, and all three are named in the design document
// as the reason this package exists (design section 11.5):
//
//   - a window resize was indistinguishable from keystrokes, so it either corrupted the
//     input or was never implemented at all;
//   - the exit code had nowhere to go, so a shell that exited 130 looked exactly like a
//     terminal that had stopped responding;
//   - the engine's own eight-byte stream header was copied to the browser as text.
//
// So nothing here is ever a bare byte slice with an implied meaning. Everything leaving
// this package is a wisperpb.TerminalFrame carrying its session id and exactly one of
// TerminalAttached, Data, TerminalResize or TerminalExit, built in frames.go and nowhere
// else. The interface this package is handed - rpc.TerminalStream - has no method that
// takes bytes, so there is no way to make the old mistake by accident.
//
// # The shape of one session
//
// Serve owns a stream from the first frame to the last and runs three goroutines:
//
//	receive.go   stream.Recv -> pty:    keystrokes, window resizes
//	output.go    pty.Read    -> chunks: whatever the process wrote, verbatim
//	pump.go      the select loop, and the only goroutine that ever calls Send
//
// One sender, because a gRPC stream permits exactly one. That is also what makes the
// ordering guarantee cheap: every Data frame a session produces is sent before its
// TerminalExit, because the same goroutine drains the output channel before it builds the
// exit frame.
//
// # Backpressure
//
// A process that writes faster than a phone on mobile data can read it must not make the
// node allocate without limit. The output goroutine reads into a fixed buffer and hands
// each chunk over a small bounded channel, so when the stream stops accepting frames the
// channel fills, the reader blocks, the pty's own buffer fills and the container's write
// blocks - which is the correct answer, and the one a shell has given since terminals were
// made of paper. A session's outstanding output is bounded by outputQueue+2 chunks of
// readChunkBytes each, and MaxSessions bounds the number of sessions.
//
// # Ending
//
// A session that ends because its process ended reports the process's exit code and no
// reason. A session ended by the platform - the idle timeout, the maximum duration, the
// panel hanging up, the node shutting down, the container dying underneath it - reports
// 137 and says which, because TerminalExit.reason exists precisely so the UI can show
// "the container stopped" instead of a terminal that quietly froze.
//
// Closing the pty is what cleans up: there is no Engine API call that kills an exec, and
// taking its terminal away is what makes a shell reading from a closed stdin exit on its
// own. A process that ignores that survives inside the customer's own container until the
// container stops, which is the honest limit of what a control plane can do here.
package terminal
