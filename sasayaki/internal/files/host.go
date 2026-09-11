package files

import (
	"errors"
	"fmt"
	"log/slog"
	"path/filepath"
	"sync"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
)

// Building a Host, and refusing to build one that cannot answer a request.
//
// Every collaborator is required. A nil store would not fail here; it would fail the first
// time a customer opened the file manager, minutes after the daemon came up clean.

// Limits are the ceilings that stop one request from taking a node down.
//
// They are here rather than as constants because the two that matter - how much an
// extraction may write and how far a size walk may go - are the difference between a
// refusal and a full disk, and a node with a small volume and a node with a large one do
// not want the same number. Zero means "use the default", so a caller may set one and
// leave the rest alone.
type Limits struct {
	// DownloadChunkBytes is how much of a file goes into one FileChunk on the way up.
	// Large enough that a big download is not a million messages, small enough that a
	// phone on mobile data gets progress it can see.
	DownloadChunkBytes int
	// ListPageSize is the default page of a directory listing, used when the panel asks
	// for none. MaxListPageSize is the ceiling it may ask for: a customer with a
	// node_modules directory has a hundred thousand entries and a phone sent all of them
	// renders nothing at all.
	ListPageSize    int
	MaxListPageSize int
	// MaxUploadChunkBytes is the largest single chunk a session may declare. The whole
	// chunk is held in memory while its checksum is verified, so this is a bound on what
	// one upload can make the daemon allocate.
	MaxUploadChunkBytes int64
	// MaxArchiveEntries and MaxExtractBytes are the zip bomb guard, together with
	// MaxCompressionRatio. A 42-kilobyte archive that expands to petabytes is a real file
	// that has been passed around since 2001, and the answer to it is a limit rather than
	// a full disk (extract.go).
	MaxArchiveEntries   int
	MaxExtractBytes     int64
	MaxCompressionRatio int64
	// MeasureEntryBudget is how many directory entries a size walk visits before it gives
	// up and answers DirectorySize{approximate: true}. An answer marked approximate is
	// more useful than a request that never returns.
	MeasureEntryBudget int64
}

func (l Limits) withDefaults() Limits {
	if l.DownloadChunkBytes <= 0 {
		l.DownloadChunkBytes = 256 << 10
	}
	if l.ListPageSize <= 0 {
		l.ListPageSize = 200
	}
	if l.MaxListPageSize <= 0 {
		l.MaxListPageSize = 1000
	}
	if l.MaxUploadChunkBytes <= 0 {
		l.MaxUploadChunkBytes = 16 << 20
	}
	if l.MaxArchiveEntries <= 0 {
		l.MaxArchiveEntries = 200_000
	}
	if l.MaxExtractBytes <= 0 {
		l.MaxExtractBytes = 16 << 30
	}
	if l.MaxCompressionRatio <= 0 {
		l.MaxCompressionRatio = 500
	}
	if l.MeasureEntryBudget <= 0 {
		l.MeasureEntryBudget = 2_000_000
	}
	return l
}

// Options is everything a Host is made of.
type Options struct {
	// StateDir is /var/lib/wisper. Every root resolves under it and nothing outside it is
	// ever opened.
	StateDir string
	// Store is the node's SQLite: the spec that says which roots exist, and the upload
	// sessions that survive a phone losing signal.
	Store Store
	// Logger defaults to slog.Default. The daemon passes one that knows the node id.
	Logger *slog.Logger
	// Now defaults to time.Now, replaced in tests so an upload can expire without anything
	// having to sleep.
	Now func() time.Time
	// Limits are the ceilings above. Zero fields take their default.
	Limits Limits
}

func (o Options) validate() error {
	if o.Store == nil {
		return errors.New("files.Options has no Store: the list of file roots and every upload " +
			"session live there, so a file manager without one can answer nothing")
	}
	if o.StateDir == "" {
		return errors.New("files: the state directory is empty, so no file root could be resolved safely")
	}
	if !filepath.IsAbs(o.StateDir) {
		return fmt.Errorf("files: the state directory %q is not absolute, and a relative one "+
			"resolves against whatever directory the daemon happens to have been started in",
			o.StateDir)
	}
	return nil
}

// Host performs file operations for the web file manager. Safe for concurrent use: the
// control stream gives every request its own goroutine, and several of them belong to the
// same customer more often than not.
type Host struct {
	store    Store
	stateDir string
	log      *slog.Logger
	now      func() time.Time
	limits   Limits

	// cached is the parsed NodeSpec, kept because a two-gigabyte upload arrives as five
	// hundred chunks and each one has to resolve its root. Guarded by its own mutex rather
	// than an atomic pointer: it is read once per request, not once per packet, and a
	// mutex is what makes "check the generation, then reload" one decision instead of two.
	cached struct {
		sync.Mutex
		spec       spec.Spec
		generation uint64
		loaded     bool
	}

	// sessions serialises the chunks of one upload against each other. A browser sends
	// them in parallel, and two goroutines writing the same parts file while both update
	// the same SQLite row is how a resumed upload ends up claiming bytes it does not have.
	sessions sync.Map
}

// New opens a file host. Nothing is created on disk until a request arrives.
func New(options Options) (*Host, error) {
	if err := options.validate(); err != nil {
		return nil, err
	}
	host := &Host{
		store:    options.Store,
		stateDir: options.StateDir,
		log:      options.Logger,
		now:      options.Now,
		limits:   options.Limits.withDefaults(),
	}
	if host.log == nil {
		host.log = slog.Default()
	}
	if host.now == nil {
		host.now = time.Now
	}
	return host, nil
}
