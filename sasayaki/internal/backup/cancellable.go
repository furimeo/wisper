package backup

import (
	"context"
	"io"
)

// Making io.Copy cancellable.
//
// The standard library's copy has no context, and every large operation in this package is one:
// reading a volume, streaming an archive to an object store, pulling forty gigabytes back down
// again. A copy that keeps going after the daemon has been asked to stop is a systemd stop that
// times out and becomes a SIGKILL in the middle of writing a customer's data - and a restore
// that keeps going after the panel cancelled it is worse than that.
//
// Wrapping the reader rather than the writer is deliberate: it works for a file, a network
// stream and a decompressor alike, and it costs one comparison per buffer.

func contextReader(ctx context.Context, inner io.Reader) io.Reader {
	return &cancellable{ctx: ctx, inner: inner}
}

type cancellable struct {
	ctx   context.Context
	inner io.Reader
}

func (c *cancellable) Read(p []byte) (int, error) {
	if err := c.ctx.Err(); err != nil {
		return 0, err
	}
	return c.inner.Read(p)
}
