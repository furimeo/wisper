package bootstrap

import (
	"crypto/ed25519"
	"crypto/rand"
	"crypto/x509"
	"encoding/pem"
	"fmt"
	"os"
	"path/filepath"
)

// pemBlockType is what the file says it holds. "PRIVATE KEY" rather than "ED25519
// PRIVATE KEY" because the body is PKCS#8, and PKCS#8 names the algorithm inside itself.
const pemBlockType = "PRIVATE KEY"

// loadOrCreateNodeKey returns this node's permanent Ed25519 identity.
//
// The key is not the credential. The credential in node.json is what authenticates every
// call and the panel can reissue it; this is what the node signed its enrolment with, and
// the panel recorded the public half against the node record (node.public_key). Keeping
// it means a re-enrolment presents the same identity rather than looking like a different
// machine wearing the same name - and it means the private half genuinely never leaves
// the node, which is what the panel's schema comment promises.
//
// Created on first use with 0600 in a 0700 directory. Read back on every subsequent
// enrolment.
func loadOrCreateNodeKey(path string) (ed25519.PrivateKey, error) {
	existing, err := os.ReadFile(path)
	switch {
	case err == nil:
		key, err := parseNodeKey(existing)
		if err != nil {
			return nil, fmt.Errorf("read the node key %s: %w. Delete it to enrol with a new "+
				"identity, and expect the panel to show this node's public key changing", path, err)
		}
		return key, nil

	case os.IsNotExist(err):
		return createNodeKey(path)

	default:
		return nil, fmt.Errorf("read the node key %s: %w", path, err)
	}
}

func createNodeKey(path string) (ed25519.PrivateKey, error) {
	_, private, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		return nil, fmt.Errorf("generate an Ed25519 key: %w", err)
	}

	encoded, err := x509.MarshalPKCS8PrivateKey(private)
	if err != nil {
		return nil, fmt.Errorf("encode the node key: %w", err)
	}
	body := pem.EncodeToMemory(&pem.Block{Type: pemBlockType, Bytes: encoded})

	directory := filepath.Dir(path)
	if err := os.MkdirAll(directory, 0o700); err != nil {
		return nil, fmt.Errorf("create %s: %w", directory, err)
	}
	if err := writeFileAtomically(path, body, 0o600); err != nil {
		return nil, fmt.Errorf("write the node key: %w", err)
	}
	return private, nil
}

func parseNodeKey(body []byte) (ed25519.PrivateKey, error) {
	block, _ := pem.Decode(body)
	if block == nil {
		return nil, fmt.Errorf("no PEM block")
	}
	if block.Type != pemBlockType {
		return nil, fmt.Errorf("the PEM block is a %q, not a %q", block.Type, pemBlockType)
	}

	parsed, err := x509.ParsePKCS8PrivateKey(block.Bytes)
	if err != nil {
		return nil, err
	}
	key, isEd25519 := parsed.(ed25519.PrivateKey)
	if !isEd25519 {
		return nil, fmt.Errorf("the key is a %T, not an Ed25519 key", parsed)
	}
	return key, nil
}
