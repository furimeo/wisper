package terminal

const (
	// How much of the pty is read at a time. The engine's own writes on a hijacked
	// connection are at most 16KiB, so this empties one in a single read; it is also small
	// enough that a phone on a slow link sees the first characters of a command's output
	// rather than waiting for a buffer to fill.
	readChunkBytes = 32 << 10

	// How many chunks may be waiting for the stream. This is the backpressure, and the
	// number is the whole of the policy: a process writing faster than the browser can
	// read fills this queue, the reader below blocks on the send, the pty's own buffer
	// fills, and the container's write blocks - which is what a terminal has always done
	// to a program that outruns it.
	//
	// Eight chunks is a quarter of a megabyte of headroom per session, so a `yes` in one
	// tab costs the node a bounded amount and cannot be used to make it allocate. Raising
	// it would buy nothing: a customer cannot read faster than their link.
	outputQueue = 8
)

// readOutput pumps the pty into chunks until it ends.
//
// Three properties, and every one of them is load-bearing:
//
// The bytes are copied out of the read buffer before they are handed over, because the
// buffer is reused on the next read and a chunk still queued for the stream would be
// overwritten underneath it - which is the sort of corruption that looks like a terminal
// emulator bug for a week.
//
// The send blocks. That is not an oversight; it is the backpressure, and the alternative -
// dropping a chunk, or growing a slice - would either corrupt an escape sequence or let a
// chatty process exhaust the node.
//
// It stops when quit is closed, which the pump does on its way out. Without that, a
// goroutine blocked on a send that nobody will ever receive outlives the session, holding
// its buffers, for as long as the daemon runs.
func readOutput(pty Pty, chunks chan<- []byte, failed chan<- error, quit <-chan struct{}) {
	defer close(chunks)

	buffer := make([]byte, readChunkBytes)
	for {
		read, err := pty.Read(buffer)
		if read > 0 {
			chunk := make([]byte, read)
			copy(chunk, buffer[:read])
			select {
			case chunks <- chunk:
			case <-quit:
				return
			}
		}
		if err != nil {
			// Buffered, so this never blocks even when the pump has already gone.
			failed <- err
			return
		}
	}
}
