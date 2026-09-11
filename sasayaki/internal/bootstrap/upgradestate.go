package bootstrap

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"time"
)

// upgradeMarkerName sits beside the credential, in a directory only root can read.
const upgradeMarkerName = "upgrade.json"

// How long a marker means anything.
//
// The marker is what tells the rollback unit "this failure is an upgrade that did not come
// back" rather than "this daemon has a problem of its own". Without a deadline, a marker
// nobody cleared would sit there for months and turn an unrelated outage in December into
// a silent downgrade to the binary from September. Fifteen minutes is far longer than a
// restart takes and far shorter than anything else.
const upgradeWindow = 15 * time.Minute

// upgradeMarker is written before a binary is replaced and removed once the daemon that
// came back has proved it works.
//
// It is a file rather than a field in SQLite because the thing that reads it - the systemd
// unit that runs when the daemon will not start - is running precisely because nothing
// else on this node is working. The fewer moving parts between "the service failed" and
// "put the old binary back", the better.
type upgradeMarker struct {
	PreviousVersion string    `json:"previous_version"`
	NewVersion      string    `json:"new_version"`
	Target          string    `json:"target"`
	PreviousBinary  string    `json:"previous_binary"`
	StartedAt       time.Time `json:"started_at"`
}

func upgradeMarkerPath(configPath string) string {
	return filepath.Join(filepath.Dir(configPath), upgradeMarkerName)
}

func writeUpgradeMarker(configPath string, marker upgradeMarker) error {
	body, err := json.MarshalIndent(marker, "", "  ")
	if err != nil {
		return fmt.Errorf("encode the upgrade marker: %w", err)
	}
	directory := filepath.Dir(configPath)
	if err := os.MkdirAll(directory, 0o700); err != nil {
		return fmt.Errorf("create %s: %w", directory, err)
	}
	return writeFileAtomically(upgradeMarkerPath(configPath), append(body, '\n'), 0o600)
}

// readUpgradeMarker returns the marker and whether there is one. A marker that cannot be
// parsed is reported as an error rather than as absent: the rollback unit deciding to do
// nothing because a file was corrupt is exactly the silence this package exists to avoid.
func readUpgradeMarker(configPath string) (upgradeMarker, bool, error) {
	raw, err := os.ReadFile(upgradeMarkerPath(configPath))
	if errors.Is(err, os.ErrNotExist) {
		return upgradeMarker{}, false, nil
	}
	if err != nil {
		return upgradeMarker{}, false, fmt.Errorf("read %s: %w", upgradeMarkerPath(configPath), err)
	}

	var marker upgradeMarker
	if err := json.Unmarshal(raw, &marker); err != nil {
		return upgradeMarker{}, false, fmt.Errorf("parse %s: %w", upgradeMarkerPath(configPath), err)
	}
	return marker, true, nil
}

func clearUpgradeMarker(configPath string) error {
	err := os.Remove(upgradeMarkerPath(configPath))
	if err == nil || errors.Is(err, os.ErrNotExist) {
		return nil
	}
	return fmt.Errorf("remove %s: %w", upgradeMarkerPath(configPath), err)
}

// stale reports whether the marker is too old to act on.
func (m upgradeMarker) stale(now time.Time) bool {
	return m.StartedAt.IsZero() || now.Sub(m.StartedAt) > upgradeWindow
}

// ConfirmUpgrade records that the binary now running is working.
//
// The daemon calls it once it has come up and completed a reconcile pass, and that is the
// whole of its job: an upgrade that has been confirmed cannot be rolled back by a later,
// unrelated failure. It is exported for `internal/daemon` and is safe to call on every
// start - a node that was not upgrading has no marker, and removing a file that is not
// there is not an error.
func ConfirmUpgrade(configPath string) error {
	if configPath == "" {
		configPath = DefaultConfigPath
	}
	return clearUpgradeMarker(configPath)
}
