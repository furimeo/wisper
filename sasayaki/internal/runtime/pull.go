package runtime

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"strings"
	"sync"
	"time"

	"github.com/moby/moby/api/types/jsonstream"
	"github.com/moby/moby/client"

	"github.com/furimeo/wisper/sasayaki/internal/reconcile"
)

const (
	// How long a failed pull is remembered before it is tried again.
	//
	// The reconcile loop comes back every fifteen seconds. A registry that is refusing -
	// a wrong tag, a private image with no credentials, Docker Hub's anonymous rate
	// limit - would otherwise be asked four times a minute per workload, which turns a
	// temporary refusal into a permanent one. The failure is reported on every pass in
	// between; only the request to the registry is held back.
	pullRetryAfter = 60 * time.Second

	// The whole budget for one pull. A registry that has been dribbling bytes for an
	// hour is not going to finish, and the goroutine holding the entry would keep the
	// workload in PULLING forever with nothing to show.
	pullTimeout = 60 * time.Minute
)

// pull is one image being fetched, or one that has finished failing.
type pull struct {
	ref string
	// progress is what to show the customer: "Downloading 42%", "Extracting 91%".
	progress string
	// done is set when the goroutine has finished. A finished pull that succeeded is
	// removed from the table entirely, so a done entry always carries an error.
	done     bool
	err      error
	finished time.Time
}

// pullTable is every pull this daemon has in flight or has recently failed.
type pullTable struct {
	sync.Mutex
	entries map[string]*pull
}

// pullState answers EnsureImage from the table alone, when the table can answer.
//
// decided false means there is nothing in flight and nothing recently broken, so the
// caller should look at what is on disk.
func (d *Docker) pullState(ref string) (state reconcile.ImageState, decided bool, err error) {
	d.pulls.Lock()
	defer d.pulls.Unlock()

	entry, running := d.pulls.entries[ref]
	if !running {
		return reconcile.ImageState{}, false, nil
	}
	if !entry.done {
		return reconcile.ImageState{Progress: entry.progress}, true, nil
	}
	if d.now().Sub(entry.finished) < pullRetryAfter {
		return reconcile.ImageState{}, true, entry.err
	}
	// The cool-down is over. Forget it and let the caller start again.
	delete(d.pulls.entries, ref)
	return reconcile.ImageState{}, false, nil
}

// startPull puts a pull in flight and returns the first thing to show for it.
//
// The goroutine hangs off the Docker's lifetime rather than the caller's context. The
// caller is a reconcile pass that returns in milliseconds; tying a twenty-minute pull to
// it would cancel the pull the instant the pass finished, and the next pass would start
// another one, forever.
func (d *Docker) startPull(ref string) reconcile.ImageState {
	d.pulls.Lock()
	defer d.pulls.Unlock()

	if entry, running := d.pulls.entries[ref]; running && !entry.done {
		return reconcile.ImageState{Progress: entry.progress}
	}

	entry := &pull{ref: ref, progress: "pulling"}
	d.pulls.entries[ref] = entry
	d.log.Info("pulling an image", slog.String("image", ref))
	go d.fetch(entry)
	return reconcile.ImageState{Progress: entry.progress}
}

// fetch is the pull itself, on its own goroutine.
func (d *Docker) fetch(entry *pull) {
	ctx, cancel := context.WithTimeout(d.lifetime, pullTimeout)
	defer cancel()

	started := d.now()
	err := d.consumePull(ctx, entry)

	d.pulls.Lock()
	defer d.pulls.Unlock()
	if err == nil {
		// Nothing to remember. The next pass inspects the image, finds it, and reports
		// the digest the engine actually has - which is a better answer than anything
		// this goroutine could cache.
		delete(d.pulls.entries, entry.ref)
		d.log.Info("pulled an image",
			slog.String("image", entry.ref),
			slog.Duration("took", d.now().Sub(started)))
		return
	}
	entry.done = true
	entry.err = err
	entry.finished = d.now()
	d.log.Warn("could not pull an image",
		slog.String("image", entry.ref),
		slog.Duration("after", d.now().Sub(started)),
		slog.String("error", err.Error()))
}

// consumePull runs the request and follows the progress stream to its end.
func (d *Docker) consumePull(ctx context.Context, entry *pull) error {
	response, err := d.api.ImagePull(ctx, entry.ref, client.ImagePullOptions{})
	if err != nil {
		return fmt.Errorf("runtime: pull %s: %w", entry.ref, err)
	}
	return d.followPull(ctx, entry, response)
}

// followPull reads the engine's progress stream, keeping the last position visible to
// whoever asks and turning the stream's own error message into the pull's failure.
//
// Split out from consumePull so it can be exercised against a canned stream: everything
// interesting about a pull - the percentage arithmetic, an error arriving as a message
// rather than as a transport failure - happens in here.
func (d *Docker) followPull(ctx context.Context, entry *pull, stream jsonMessages) error {
	defer stream.Close()

	progress := newProgress()
	for message, err := range stream.JSONMessages(ctx) {
		if err != nil {
			return fmt.Errorf("runtime: pull %s: %w", entry.ref, err)
		}
		if message.Error != nil {
			// The registry refused, and it says so in the stream rather than in the HTTP
			// status. Verbatim, because "manifest unknown" and "toomanyrequests" need
			// completely different actions from whoever reads it.
			return fmt.Errorf("runtime: pull %s: %s", entry.ref, message.Error.Message)
		}
		if rendered := progress.observe(message); rendered != "" {
			d.pulls.Lock()
			entry.progress = rendered
			d.pulls.Unlock()
		}
	}
	if err := ctx.Err(); err != nil {
		return fmt.Errorf("runtime: pull %s: %w", entry.ref, errors.Join(err, context.Cause(ctx)))
	}
	return nil
}

// progressReport turns a stream of per-layer messages into one line a person can read.
//
// Per layer, because that is how the engine reports it: a dozen concurrent downloads each
// with their own current and total. Summing them gives the number a customer watching a
// deployment actually wants, which is how far the whole thing has got, and the status
// word in front of it distinguishes the download from the decompression that follows and
// takes just as long.
type progressReport struct {
	layers map[string]jsonstream.Progress
	status string
}

func newProgress() *progressReport {
	return &progressReport{layers: make(map[string]jsonstream.Progress)}
}

// observe folds one message in and returns the line to show, or "" when this message
// changed nothing worth redrawing.
func (p *progressReport) observe(message jsonstream.Message) string {
	status := strings.TrimSpace(message.Status)
	if status == "" {
		return ""
	}
	// Only the phases with a size behind them contribute to the bar. "Pulling from
	// library/nginx", "Waiting" and "Already exists" are noise in an average.
	if message.ID != "" && message.Progress != nil && message.Progress.Total > 0 {
		p.layers[message.ID] = *message.Progress
	}
	p.status = status

	var current, total int64
	for _, layer := range p.layers {
		current += layer.Current
		total += layer.Total
	}
	if total <= 0 {
		return p.status
	}
	if current > total {
		current = total
	}
	return fmt.Sprintf("%s %d%%", p.status, current*100/total)
}
