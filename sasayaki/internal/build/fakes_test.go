package build

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/state"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// ---------------------------------------------------------------------------
// The store, the uploads and the log sink.
// ---------------------------------------------------------------------------

// fakeStore is the build history in a map.
type fakeStore struct {
	mutex sync.Mutex
	runs  map[string]state.BuildRun
	order []string
	// Retention is what LoadSpec reports. Nil means the panel has never spoken to this
	// node, which LoadSpec answers with state.ErrNoSpec.
	Retention *wisperpb.RetentionPolicy
	// Stages records every stage transition, so a test can assert the build reported its
	// progress rather than jumping from start to finish.
	Stages []wisperpb.BuildStage
}

func newFakeStore() *fakeStore {
	return &fakeStore{runs: make(map[string]state.BuildRun)}
}

func (s *fakeStore) BeginBuild(_ context.Context, run state.BuildRun) error {
	s.mutex.Lock()
	defer s.mutex.Unlock()
	if _, present := s.runs[run.BuildID]; present {
		return fmt.Errorf("state: build %s: %w", run.BuildID, state.ErrAlreadyExists)
	}
	s.runs[run.BuildID] = run
	s.order = append(s.order, run.BuildID)
	return nil
}

func (s *fakeStore) RecordBuildStage(_ context.Context, buildID string, stage wisperpb.BuildStage,
	at time.Time, detail string) error {

	s.mutex.Lock()
	defer s.mutex.Unlock()
	run, present := s.runs[buildID]
	if !present {
		return fmt.Errorf("state: build %s: %w", buildID, state.ErrNotFound)
	}
	run.Stage = stage
	run.UpdatedAt = at
	run.Detail = detail
	s.runs[buildID] = run
	s.Stages = append(s.Stages, stage)
	return nil
}

func (s *fakeStore) FinishBuild(_ context.Context, buildID string,
	completed *wisperpb.BuildCompleted, at time.Time) error {

	s.mutex.Lock()
	defer s.mutex.Unlock()
	run, present := s.runs[buildID]
	if !present {
		return fmt.Errorf("state: build %s: %w", buildID, state.ErrNotFound)
	}
	run.Finished = true
	run.FinishedAt = at
	run.Success = completed.GetSuccess()
	run.Detail = completed.GetDetail()
	run.Result = completed
	s.runs[buildID] = run
	return nil
}

func (s *fakeStore) Build(_ context.Context, buildID string) (state.BuildRun, error) {
	s.mutex.Lock()
	defer s.mutex.Unlock()
	run, present := s.runs[buildID]
	if !present {
		return state.BuildRun{}, fmt.Errorf("state: build %s: %w", buildID, state.ErrNotFound)
	}
	return run, nil
}

func (s *fakeStore) UnfinishedBuilds(context.Context) ([]state.BuildRun, error) {
	s.mutex.Lock()
	defer s.mutex.Unlock()
	var out []state.BuildRun
	for _, id := range s.order {
		if run := s.runs[id]; !run.Finished {
			out = append(out, run)
		}
	}
	return out, nil
}

func (s *fakeStore) LoadSpec(context.Context) (state.StoredSpec, error) {
	s.mutex.Lock()
	defer s.mutex.Unlock()
	if s.Retention == nil {
		return state.StoredSpec{}, state.ErrNoSpec
	}
	return state.StoredSpec{Spec: &wisperpb.NodeSpec{Retention: s.Retention}}, nil
}

// fakeUploads answers with paths a test put on disk itself.
type fakeUploads struct {
	files map[string]string
	err   error
}

func (u fakeUploads) ArchivePath(_ context.Context, sessionID string) (string, error) {
	if u.err != nil {
		return "", u.err
	}
	path, present := u.files[sessionID]
	if !present {
		return "", errors.New("no such upload session")
	}
	return path, nil
}

// fakeSink collects the chunks a build pushed at the panel.
type fakeSink struct {
	mutex sync.Mutex
	sent  []*wisperpb.LogChunk
	// Refuse makes every offer fail, which is how dropped_bytes is tested.
	Refuse bool
}

func (s *fakeSink) SendLog(chunk *wisperpb.LogChunk) bool {
	s.mutex.Lock()
	defer s.mutex.Unlock()
	if s.Refuse {
		return false
	}
	s.sent = append(s.sent, chunk)
	return true
}

func (s *fakeSink) lines() []string {
	s.mutex.Lock()
	defer s.mutex.Unlock()
	out := make([]string, 0, len(s.sent))
	for _, chunk := range s.sent {
		if len(chunk.GetData()) > 0 {
			out = append(out, string(chunk.GetData()))
		}
	}
	return out
}

func (s *fakeSink) text() string {
	joined := ""
	for _, line := range s.lines() {
		joined += line
	}
	return joined
}
