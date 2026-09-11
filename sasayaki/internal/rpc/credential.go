package rpc

import (
	"bytes"
	"encoding/json"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"time"
)

// Credential is everything a node needs to prove who it is and find the panel again:
// the contents of /etc/wisper/node.json.
//
// It is declared here rather than in the package that writes the file because it is the
// input to every dial in this package, and a struct owned by the caller would mean the
// wire format and the file format could drift apart. Enrolment produces one of these,
// `sasayaki enroll` writes it, `sasayaki run` reads it, and nothing else in the daemon
// needs to know what is in it.
type Credential struct {
	// From EnrollResponse. Opaque to the node: it goes back up in metadata unchanged.
	NodeID string `json:"node_id"`

	// What an administrator named this node. Used in log lines, and as the phrase
	// `uninstall --purge` demands before it destroys anything.
	NodeName string `json:"node_name"`

	// The long-lived credential. Never logged - see LogValue below - and never sent
	// anywhere except in the wisper-node-token metadata header.
	Token string `json:"credential"`

	// The --panel value, in the form ParseEndpoint accepts.
	Panel string `json:"panel"`

	// Hex SHA-256 of the panel's TLS certificate, captured at enrolment. Empty only for
	// a plaintext endpoint, which has no certificate to pin.
	PanelCertificateSHA256 string `json:"panel_certificate_sha256"`

	EnrolledAt time.Time `json:"enrolled_at"`
}

// LoadCredential reads node.json.
//
// A missing file is reported as itself rather than as an empty credential: "this node
// has not been enrolled" and "this node was enrolled with an empty token" need
// different answers, and only one of them is a mistake.
func LoadCredential(path string) (Credential, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return Credential{}, fmt.Errorf("read node credential %s: %w", path, err)
	}

	var credential Credential
	decoder := json.NewDecoder(bytes.NewReader(raw))
	// A key nobody reads is a setting an operator believes is in effect. Refuse it here,
	// where the file name is still in hand, rather than ignoring it silently.
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&credential); err != nil {
		return Credential{}, fmt.Errorf("parse node credential %s: %w", path, err)
	}
	if err := credential.Validate(); err != nil {
		return Credential{}, fmt.Errorf("node credential %s: %w", path, err)
	}
	return credential, nil
}

// Save writes node.json 0600, atomically.
//
// Atomically because the alternative is a node that was interrupted mid-write coming
// back with half a credential and no way to tell that is what happened; 0600 because
// the token in it is the whole of this node's authority over the panel.
func (c Credential) Save(path string) error {
	if err := c.Validate(); err != nil {
		return err
	}

	body, err := json.MarshalIndent(c, "", "  ")
	if err != nil {
		return fmt.Errorf("encode node credential: %w", err)
	}
	body = append(body, '\n')

	directory := filepath.Dir(path)
	if err := os.MkdirAll(directory, 0o700); err != nil {
		return fmt.Errorf("create %s: %w", directory, err)
	}

	// In the same directory, so the rename is on one filesystem and therefore atomic.
	temporary, err := os.CreateTemp(directory, ".node-*.json")
	if err != nil {
		return fmt.Errorf("create temporary credential in %s: %w", directory, err)
	}
	temporaryName := temporary.Name()
	defer os.Remove(temporaryName)

	if err := temporary.Chmod(0o600); err != nil {
		temporary.Close()
		return fmt.Errorf("restrict permissions on %s: %w", temporaryName, err)
	}
	if _, err := temporary.Write(body); err != nil {
		temporary.Close()
		return fmt.Errorf("write %s: %w", temporaryName, err)
	}
	if err := temporary.Sync(); err != nil {
		temporary.Close()
		return fmt.Errorf("flush %s: %w", temporaryName, err)
	}
	if err := temporary.Close(); err != nil {
		return fmt.Errorf("close %s: %w", temporaryName, err)
	}
	if err := os.Rename(temporaryName, path); err != nil {
		return fmt.Errorf("install %s: %w", path, err)
	}
	return nil
}

// Validate refuses a credential that cannot be used, at the point it is read, rather
// than letting it fail later as an unauthenticated stream that reconnects forever.
func (c Credential) Validate() error {
	if c.NodeID == "" {
		return fmt.Errorf("node_id is empty")
	}
	if c.Token == "" {
		return fmt.Errorf("credential is empty")
	}
	if c.Panel == "" {
		return fmt.Errorf("panel is empty")
	}
	endpoint, err := ParseEndpoint(c.Panel)
	if err != nil {
		return err
	}
	pin, err := NormalisePin(c.PanelCertificateSHA256)
	if err != nil {
		return err
	}
	if pin == "" && !endpoint.Plaintext {
		return fmt.Errorf("panel_certificate_sha256 is empty for a TLS endpoint: this node was never pinned, re-enrol it")
	}
	return nil
}

// Endpoint is where this credential says the panel is.
func (c Credential) Endpoint() (Endpoint, error) {
	return ParseEndpoint(c.Panel)
}

// Pin is the normalised fingerprint, empty for a plaintext endpoint.
func (c Credential) Pin() (string, error) {
	return NormalisePin(c.PanelCertificateSHA256)
}

// String keeps the token out of anything printed with %v or %s. A struct whose default
// formatting leaks a secret leaks it eventually.
func (c Credential) String() string {
	name := c.NodeName
	if name == "" {
		name = "unnamed"
	}
	return fmt.Sprintf("node %s (%s) at %s", c.NodeID, name, c.Panel)
}

// LogValue does the same for slog, which does not call String on a struct.
func (c Credential) LogValue() slog.Value {
	return slog.GroupValue(
		slog.String("node_id", c.NodeID),
		slog.String("node_name", c.NodeName),
		slog.String("panel", c.Panel),
	)
}
