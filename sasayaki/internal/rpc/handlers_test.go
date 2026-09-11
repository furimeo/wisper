package rpc

import (
	"strings"
	"testing"
)

// A command the panel can send and nobody answers is a stub with extra steps, so the
// daemon does not start with one.
func TestAMissingHandlerIsRefusedAtConstruction(t *testing.T) {
	node := newFakeNode()
	handlers := node.handlers()
	handlers.Backups = nil
	handlers.Files = nil

	_, err := New(aCredential(), handlers)
	if err == nil {
		t.Fatal("a client was built with two commands nothing can answer")
	}
	for _, named := range []string{"Backups", "Files"} {
		if !strings.Contains(err.Error(), named) {
			t.Errorf("error %q does not name the missing handler %s", err, named)
		}
	}
}

func TestAnUnusableCredentialIsRefusedAtConstruction(t *testing.T) {
	credential := aCredential()
	credential.PanelCertificateSHA256 = ""

	if _, err := New(credential, newFakeNode().handlers()); err == nil {
		t.Fatal("a client was built for a TLS endpoint with nothing pinned")
	}
}

// The plaintext endpoint is the one case with no certificate. It has to work - it is
// what `make run-dev` uses - and it has to be the only case.
func TestAPlaintextClientIsBuiltWithoutAPin(t *testing.T) {
	credential := aCredential()
	credential.Panel = "http://127.0.0.1:9090"
	credential.PanelCertificateSHA256 = ""

	client, err := New(credential, newFakeNode().handlers())
	if err != nil {
		t.Fatalf("new client: %v", err)
	}
	defer client.Close()

	if client.Connected() {
		t.Error("a client that has not run yet reported itself connected")
	}
	if client.NodeID() != credential.NodeID {
		t.Errorf("node id = %q", client.NodeID())
	}
}

// A pinned fingerprint and a plaintext endpoint cannot both be honoured. Guessing which
// one the operator meant either drops the pin or refuses to connect for no reason.
func TestPlaintextWithAPinIsRefused(t *testing.T) {
	credential := aCredential()
	credential.Panel = "http://127.0.0.1:9090"

	if _, err := New(credential, newFakeNode().handlers()); err == nil {
		t.Fatal("a plaintext endpoint with a pinned certificate was accepted")
	}
}
