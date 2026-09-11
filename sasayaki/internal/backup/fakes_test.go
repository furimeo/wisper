package backup

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"sort"
	"sync"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/state"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// The three collaborators this package declares in ports.go, as maps.
//
// None of them is elaborate, and that is the argument for consumer-declared interfaces: the
// node's SQLite, the container runtime and two database servers are each replaced here in
// under a hundred lines, so a backup can be run end to end in a test with nothing installed.

// ---------------------------------------------------------------------------
// The node's SQLite, as a map.
// ---------------------------------------------------------------------------

type fakeStore struct {
	mu       sync.Mutex
	backups  map[string]*state.BackupRun
	restores map[string]*state.RestoreRun
	stages   []wisperpb.BackupStage
	progress []string
}

func newFakeStore() *fakeStore {
	return &fakeStore{
		backups:  make(map[string]*state.BackupRun),
		restores: make(map[string]*state.RestoreRun),
	}
}

func (f *fakeStore) BeginBackup(_ context.Context, run state.BackupRun) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if _, taken := f.backups[run.BackupID]; taken {
		return fmt.Errorf("backup %s: %w", run.BackupID, state.ErrAlreadyExists)
	}
	stored := run
	f.backups[run.BackupID] = &stored
	return nil
}

func (f *fakeStore) RecordBackupStage(_ context.Context, backupID string, stage wisperpb.BackupStage,
	at time.Time, detail string) error {

	f.mu.Lock()
	defer f.mu.Unlock()
	run, found := f.backups[backupID]
	if !found {
		return fmt.Errorf("backup %s: %w", backupID, state.ErrNotFound)
	}
	run.Stage = stage
	run.UpdatedAt = at
	run.Detail = detail
	f.stages = append(f.stages, stage)
	return nil
}

func (f *fakeStore) FinishBackup(_ context.Context, backupID string,
	completed *wisperpb.BackupCompleted, at time.Time) error {

	f.mu.Lock()
	defer f.mu.Unlock()
	run, found := f.backups[backupID]
	if !found {
		return fmt.Errorf("backup %s: %w", backupID, state.ErrNotFound)
	}
	run.Finished = true
	run.FinishedAt = at
	run.Success = completed.GetSuccess()
	run.Result = completed
	return nil
}

func (f *fakeStore) Backup(_ context.Context, backupID string) (state.BackupRun, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	run, found := f.backups[backupID]
	if !found {
		return state.BackupRun{}, fmt.Errorf("backup %s: %w", backupID, state.ErrNotFound)
	}
	return *run, nil
}

func (f *fakeStore) UnfinishedBackups(context.Context) ([]state.BackupRun, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	var out []state.BackupRun
	for _, run := range f.backups {
		if !run.Finished {
			out = append(out, *run)
		}
	}
	sort.Slice(out, func(a, b int) bool { return out[a].BackupID < out[b].BackupID })
	return out, nil
}

func (f *fakeStore) BeginRestore(_ context.Context, run state.RestoreRun) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if _, taken := f.restores[run.RestoreID]; taken {
		return fmt.Errorf("restore %s: %w", run.RestoreID, state.ErrAlreadyExists)
	}
	stored := run
	f.restores[run.RestoreID] = &stored
	return nil
}

func (f *fakeStore) RecordRestoreProgress(_ context.Context, restoreID string, at time.Time, detail string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	run, found := f.restores[restoreID]
	if !found {
		return fmt.Errorf("restore %s: %w", restoreID, state.ErrNotFound)
	}
	run.UpdatedAt = at
	run.Detail = detail
	f.progress = append(f.progress, detail)
	return nil
}

func (f *fakeStore) FinishRestore(_ context.Context, restoreID string,
	completed *wisperpb.RestoreCompleted, at time.Time) error {

	f.mu.Lock()
	defer f.mu.Unlock()
	run, found := f.restores[restoreID]
	if !found {
		return fmt.Errorf("restore %s: %w", restoreID, state.ErrNotFound)
	}
	run.Finished = true
	run.FinishedAt = at
	run.Success = completed.GetSuccess()
	run.Result = completed
	return nil
}

func (f *fakeStore) Restore(_ context.Context, restoreID string) (state.RestoreRun, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	run, found := f.restores[restoreID]
	if !found {
		return state.RestoreRun{}, fmt.Errorf("restore %s: %w", restoreID, state.ErrNotFound)
	}
	return *run, nil
}

func (f *fakeStore) UnfinishedRestores(context.Context) ([]state.RestoreRun, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	var out []state.RestoreRun
	for _, run := range f.restores {
		if !run.Finished {
			out = append(out, *run)
		}
	}
	sort.Slice(out, func(a, b int) bool { return out[a].RestoreID < out[b].RestoreID })
	return out, nil
}

// ---------------------------------------------------------------------------
// The container runtime, as a list of things that were asked of it.
// ---------------------------------------------------------------------------

type fakeWorkloads struct {
	mu          sync.Mutex
	running     map[string]bool
	events      []string
	failRunning error
	failPause   error
	failStop    error
	failStart   error
}

func newFakeWorkloads() *fakeWorkloads {
	return &fakeWorkloads{running: make(map[string]bool)}
}

func (f *fakeWorkloads) Running(_ context.Context, workloadID string) (bool, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.failRunning != nil {
		return false, f.failRunning
	}
	return f.running[workloadID], nil
}

func (f *fakeWorkloads) Pause(_ context.Context, workloadID string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.failPause != nil {
		return f.failPause
	}
	f.events = append(f.events, "pause:"+workloadID)
	return nil
}

func (f *fakeWorkloads) Unpause(_ context.Context, workloadID string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.events = append(f.events, "unpause:"+workloadID)
	return nil
}

func (f *fakeWorkloads) Stop(_ context.Context, workloadID string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.failStop != nil {
		return f.failStop
	}
	f.running[workloadID] = false
	f.events = append(f.events, "stop:"+workloadID)
	return nil
}

func (f *fakeWorkloads) Start(_ context.Context, workloadID string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.failStart != nil {
		return f.failStart
	}
	f.running[workloadID] = true
	f.events = append(f.events, "start:"+workloadID)
	return nil
}

func (f *fakeWorkloads) seen() []string {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]string(nil), f.events...)
}

// ---------------------------------------------------------------------------
// The database engines, as a map of name to dump.
// ---------------------------------------------------------------------------

type fakeDumps struct {
	mu       sync.Mutex
	contents map[string]string
	created  []string
	failDump error
	failLoad error
}

func newFakeDumps() *fakeDumps {
	return &fakeDumps{contents: make(map[string]string)}
}

func (f *fakeDumps) DumpDatabase(_ context.Context, _ wisperpb.DatabaseEngine, database string, out io.Writer) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.failDump != nil {
		return f.failDump
	}
	contents, found := f.contents[database]
	if !found {
		return fmt.Errorf("no database called %s", database)
	}
	_, err := io.WriteString(out, contents)
	return err
}

func (f *fakeDumps) RestoreDatabase(_ context.Context, _ wisperpb.DatabaseEngine, database string, in io.Reader) error {
	if f.failLoad != nil {
		return f.failLoad
	}
	var replayed bytes.Buffer
	if _, err := io.Copy(&replayed, in); err != nil {
		return err
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	f.contents[database] = replayed.String()
	return nil
}

func (f *fakeDumps) CreateEmptyDatabase(_ context.Context, _ wisperpb.DatabaseEngine, database string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	if _, taken := f.contents[database]; taken {
		return fmt.Errorf("%s already exists", database)
	}
	f.contents[database] = ""
	f.created = append(f.created, database)
	return nil
}
