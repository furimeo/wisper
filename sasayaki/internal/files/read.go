package files

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Downloading, which is a stream of chunks and not a single message.
//
// Two different features share this one operation (files.proto, ReadFile). A customer
// downloading a file gets the whole thing, chunk by chunk, and can resume from an offset
// when their connection dropped. The inline editor asks for the first megabyte of a large
// log instead of refusing to show it. Both are the same read with a different range, so
// there is one code path and not two that drift.

// readFile answers a ReadFile with a run of FileChunks, the last of which carries
// last = true. That chunk is the terminator: a download does not also send an
// OperationDone, because the last chunk already says the transfer ended.
func (h *Host) readFile(ctx context.Context, root *openRoot, request *wisperpb.ReadFile, out *replies) error {
	target, failed := resolve(root, request.GetPath(), true)
	if failed != nil {
		return failed
	}
	if request.GetOffset() < 0 || request.GetMaxBytes() < 0 {
		return refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_IO_ERROR, target,
			"a read cannot start at offset %d for %d bytes", request.GetOffset(), request.GetMaxBytes())
	}

	info, err := root.root.Lstat(systemName(target))
	if err != nil {
		return classify(ctx, err, target, "look at the file")
	}
	if info.IsDir() {
		return refuse(wisperpb.FileErrorCode_FILE_ERROR_CODE_IS_A_DIRECTORY, target,
			"this path is a directory; archive it first to download it as one file")
	}

	file, err := root.root.Open(systemName(target))
	if err != nil {
		return classify(ctx, err, target, "open the file")
	}
	defer file.Close()

	if _, err := file.Seek(request.GetOffset(), io.SeekStart); err != nil {
		return classify(ctx, err, target, "seek in the file")
	}

	source := io.Reader(file)
	if request.GetMaxBytes() > 0 {
		source = io.LimitReader(file, request.GetMaxBytes())
	}
	return h.streamChunks(ctx, source, request.GetOffset(), target, out)
}

// streamChunks cuts a reader into FileChunks and sends them.
//
// An empty range still produces one chunk, empty and marked last. A zero-byte file is a
// real file a customer may have created deliberately, and an operation that answered it
// with silence would be indistinguishable from one that hung.
func (h *Host) streamChunks(ctx context.Context, source io.Reader, start int64, target string, out *replies) error {
	size := h.limits.DownloadChunkBytes
	offset := start
	var index int64

	for {
		if failed := interrupted(ctx, target, "reading the file"); failed != nil {
			return failed
		}

		// A fresh buffer per chunk. The slice travels into a protobuf message that this
		// package no longer owns once it is sent, and reusing it to save an allocation is
		// how a download quietly serves the previous chunk twice.
		buffer := make([]byte, size)
		read, err := io.ReadFull(source, buffer)
		atEnd := errors.Is(err, io.EOF) || errors.Is(err, io.ErrUnexpectedEOF)
		if err != nil && !atEnd {
			return classify(ctx, err, target, "read the file")
		}

		digest := sha256.Sum256(buffer[:read])
		chunk := &wisperpb.FileChunk{
			Offset:     offset,
			ChunkIndex: index,
			Data:       buffer[:read],
			Last:       atEnd,
			Sha256:     hex.EncodeToString(digest[:]),
		}
		if err := out.chunk(chunk); err != nil {
			return err
		}
		if atEnd {
			return nil
		}

		offset += int64(read)
		index++
	}
}
