package runtime

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"io"
	"strings"
	"testing"
	"time"

	"github.com/moby/moby/api/types/container"
	"github.com/moby/moby/client"
)

// frame builds one of the engine's multiplexed writes, so a test can hand the reader
// exactly the bytes a real engine would send.
func frame(stream byte, payload string) []byte {
	header := make([]byte, frameHeaderBytes)
	header[0] = stream
	binary.BigEndian.PutUint32(header[4:], uint32(len(payload)))
	return append(header, payload...)
}

// logging wires a fake whose container has no tty and whose log stream is these bytes.
func logging(t *testing.T, tty bool, body []byte) (*Docker, *client.ContainerLogsOptions) {
	t.Helper()
	api := newFake()
	asked := &client.ContainerLogsOptions{}
	api.onInspect = func(id string) (client.ContainerInspectResult, error) {
		return client.ContainerInspectResult{Container: container.InspectResponse{
			ID: id, Config: &container.Config{Tty: tty},
		}}, nil
	}
	api.onLogs = func(_ string, options client.ContainerLogsOptions) (client.ContainerLogsResult, error) {
		*asked = options
		return io.NopCloser(bytes.NewReader(body)), nil
	}
	return newDocker(t, api, newHost()), asked
}

// Framing, which is the whole reason this is not io.Copy. The predecessor pumped this
// stream straight at a socket and delivered the eight header bytes to the browser as text.
func TestLogsSeparatesStdoutFromStderrAndKeepsTheEnginesTimestamps(t *testing.T) {
	body := bytes.Join([][]byte{
		frame(1, "2026-04-01T11:00:00.5Z listening on :3000\n"),
		frame(2, "2026-04-01T11:00:01Z Error: EADDRINUSE\n"),
	}, nil)
	docker, _ := logging(t, false, body)

	stream, err := docker.Logs(context.Background(), "c1", LogOptions{})
	if err != nil {
		t.Fatalf("Logs: %v", err)
	}
	defer stream.Close()

	first, err := stream.Next()
	if err != nil {
		t.Fatalf("Next: %v", err)
	}
	if first.Stderr {
		t.Error("stdout was reported as stderr")
	}
	if string(first.Data) != "listening on :3000\n" {
		t.Errorf("data = %q, want the timestamp taken off", first.Data)
	}
	if !first.At.Equal(time.Date(2026, 4, 1, 11, 0, 0, 500_000_000, time.UTC)) {
		t.Errorf("at = %v, want the moment the engine recorded", first.At)
	}

	second, err := stream.Next()
	if err != nil {
		t.Fatalf("Next: %v", err)
	}
	if !second.Stderr {
		t.Error("stderr was reported as stdout, which is the difference between a crash and " +
			"an access log")
	}

	if _, err := stream.Next(); !errors.Is(err, io.EOF) {
		t.Errorf("after the last chunk: %v, want io.EOF", err)
	}
}

func TestAContainerThatWritesItsOwnTimestampsKeepsThem(t *testing.T) {
	// No RFC 3339 prefix from the engine here, and the line begins with something that
	// looks like a token. Eating the first word of a customer's own log format would be
	// worse than reporting no time.
	docker, _ := logging(t, false, frame(1, "INFO 2026-04-01 started\n"))

	stream, err := docker.Logs(context.Background(), "c1", LogOptions{})
	if err != nil {
		t.Fatalf("Logs: %v", err)
	}
	defer stream.Close()

	chunk, err := stream.Next()
	if err != nil {
		t.Fatalf("Next: %v", err)
	}
	if string(chunk.Data) != "INFO 2026-04-01 started\n" {
		t.Errorf("data = %q, want it untouched", chunk.Data)
	}
	if !chunk.At.IsZero() {
		t.Errorf("at = %v, want zero rather than an invented time", chunk.At)
	}
}

func TestAStreamCutMidFrameEndsRatherThanFails(t *testing.T) {
	// A container removed underneath a follow: half a header and nothing after it.
	docker, _ := logging(t, false, []byte{1, 0, 0, 0})

	stream, err := docker.Logs(context.Background(), "c1", LogOptions{Follow: true})
	if err != nil {
		t.Fatalf("Logs: %v", err)
	}
	defer stream.Close()

	if _, err := stream.Next(); !errors.Is(err, io.EOF) {
		t.Errorf("Next = %v, want io.EOF: there is nothing further to read either way", err)
	}
}

func TestAnAbsurdFrameLengthIsRefusedInsteadOfAllocated(t *testing.T) {
	header := make([]byte, frameHeaderBytes)
	header[0] = 1
	binary.BigEndian.PutUint32(header[4:], 0xffffffff)
	docker, _ := logging(t, false, header)

	stream, err := docker.Logs(context.Background(), "c1", LogOptions{})
	if err != nil {
		t.Fatalf("Logs: %v", err)
	}
	defer stream.Close()

	if _, err := stream.Next(); err == nil || errors.Is(err, io.EOF) {
		t.Errorf("Next = %v, want a refusal rather than a four-gigabyte allocation", err)
	}
}

func TestAContainerWithATtyIsReadRaw(t *testing.T) {
	docker, _ := logging(t, true, []byte("no framing here at all\n"))

	stream, err := docker.Logs(context.Background(), "c1", LogOptions{})
	if err != nil {
		t.Fatalf("Logs: %v", err)
	}
	defer stream.Close()

	chunk, err := stream.Next()
	if err != nil {
		t.Fatalf("Next: %v", err)
	}
	if string(chunk.Data) != "no framing here at all\n" {
		t.Errorf("data = %q: a tty stream read as frames turns the first eight bytes of a "+
			"log line into a length", chunk.Data)
	}
}

func TestLogOptionsReachTheEngine(t *testing.T) {
	docker, asked := logging(t, false, nil)
	since := time.Date(2026, 4, 1, 11, 0, 0, 250_000_000, time.UTC)

	stream, err := docker.Logs(context.Background(), "c1", LogOptions{Tail: 200, Follow: true, Since: since})
	if err != nil {
		t.Fatalf("Logs: %v", err)
	}
	defer stream.Close()

	if asked.Tail != "200" || !asked.Follow {
		t.Errorf("options = %+v, want the tail and the follow", asked)
	}
	if !asked.Timestamps {
		t.Error("timestamps were not requested, so a chunk arriving after a reconnect would " +
			"be stamped with the time it was read")
	}
	if !strings.HasPrefix(asked.Since, "1775041200.250000000") {
		t.Errorf("since = %q, want seconds and nanoseconds so a browser reconnecting does not "+
			"get the same second twice", asked.Since)
	}
}
