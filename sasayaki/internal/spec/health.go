package spec

import (
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// RestartMode is what Docker does when a container exits.
type RestartMode string

const (
	// RestartNever is also what an unspecified mode becomes, and that is safe here in a way
	// it would not be in a plain container runtime: the reconcile loop runs every fifteen
	// seconds whatever happens, so a workload the panel wants running is brought back by
	// the loop even with no restart policy at all. Docker's own default is the same value,
	// so nothing surprising happens either.
	RestartNever     RestartMode = "NEVER"
	RestartOnFailure RestartMode = "ON_FAILURE"
	RestartAlways    RestartMode = "ALWAYS"
	// RestartUnlessStopped survives a daemon restart but not an explicit stop, which is
	// what a customer who pressed "stop" means.
	RestartUnlessStopped RestartMode = "UNLESS_STOPPED"
)

// Restart is the restart policy for one workload.
type Restart struct {
	Mode RestartMode
	// RestartOnFailure only. Beyond this the workload is reported as crash-looping instead
	// of being restarted forever, so the customer is told rather than left wondering.
	MaxRetries int32
}

// Health is Docker's own health check, configured by the panel rather than baked into an
// image the customer may not control.
//
// An unhealthy container is reported, not killed. The panel decides: a failing check during
// a slow migration is not a reason to restart a database-backed app in a loop.
type Health struct {
	// argv run inside the container. The node adds Docker's CMD marker; the panel does not.
	Test     []string
	Interval time.Duration
	Timeout  time.Duration
	Retries  int32
	// Grace period after start during which failures do not count. Without it, anything
	// that takes ten seconds to boot is permanently unhealthy.
	StartPeriod time.Duration
}

// IsSet reports whether the panel asked for a health check at all.
//
// An empty Test disables it, which is the proto's own rule and not an invention here. It is
// a method rather than a pointer field so a caller cannot dereference the absence.
func (h Health) IsSet() bool { return len(h.Test) > 0 }

// SiteOptions is how the edge serves a static site. These are Caddy file_server settings,
// in the spec because they are per-site decisions the customer makes.
type SiteOptions struct {
	// Serve IndexFile for any path that does not exist, instead of a 404. Every
	// client-side-routed site needs this and no server-rendered one does.
	SPAFallback bool
	// Empty means index.html.
	IndexFile string
	// Served with a 404 status when there is no SPA fallback. Empty means Caddy's own.
	NotFoundFile string
	// Off unless the customer asks: an accidental directory listing is a data leak.
	DirectoryListing bool
}

// Index is the file to serve for a directory, with the default applied.
//
// The default lives here rather than in the edge package so that the reconciler's diff and
// the Caddy config agree on what an empty string means; two places deciding that
// independently is a route that is rewritten on every pass.
func (s SiteOptions) Index() string {
	if s.IndexFile == "" {
		return "index.html"
	}
	return s.IndexFile
}

func restartFromProto(message *wisperpb.RestartPolicy) Restart {
	mode := RestartNever
	switch message.GetMode() {
	case wisperpb.RestartPolicyMode_RESTART_POLICY_MODE_ON_FAILURE:
		mode = RestartOnFailure
	case wisperpb.RestartPolicyMode_RESTART_POLICY_MODE_ALWAYS:
		mode = RestartAlways
	case wisperpb.RestartPolicyMode_RESTART_POLICY_MODE_UNLESS_STOPPED:
		mode = RestartUnlessStopped
	}
	return Restart{Mode: mode, MaxRetries: message.GetMaxRetries()}
}

func healthFromProto(message *wisperpb.HealthCheck) Health {
	return Health{
		Test:        append([]string(nil), message.GetTest()...),
		Interval:    seconds(message.GetIntervalSeconds()),
		Timeout:     seconds(message.GetTimeoutSeconds()),
		Retries:     message.GetRetries(),
		StartPeriod: seconds(message.GetStartPeriodSeconds()),
	}
}

func siteOptionsFromProto(message *wisperpb.SiteOptions) SiteOptions {
	return SiteOptions{
		SPAFallback:      message.GetSpaFallback(),
		IndexFile:        message.GetIndexFile(),
		NotFoundFile:     message.GetNotFoundFile(),
		DirectoryListing: message.GetDirectoryListing(),
	}
}
