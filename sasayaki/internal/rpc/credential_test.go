package rpc

import (
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"
)

func aCredential() Credential {
	return Credential{
		NodeID:                 "11111111-2222-3333-4444-555555555555",
		NodeName:               "node-1",
		Token:                  "a-long-lived-credential",
		Panel:                  "https://panel.example",
		PanelCertificateSHA256: strings.Repeat("ab", 32),
		EnrolledAt:             time.Now().UTC().Truncate(time.Second),
	}
}

func TestCredentialSurvivesADiskRoundTrip(t *testing.T) {
	path := filepath.Join(t.TempDir(), "wisper", "node.json")
	written := aCredential()

	if err := written.Save(path); err != nil {
		t.Fatalf("save: %v", err)
	}
	read, err := LoadCredential(path)
	if err != nil {
		t.Fatalf("load: %v", err)
	}
	if read != written {
		t.Errorf("read back %+v, want %+v", read, written)
	}

	if runtime.GOOS != "windows" {
		// The token in this file is the whole of the node's authority over the panel.
		info, err := os.Stat(path)
		if err != nil {
			t.Fatalf("stat: %v", err)
		}
		if mode := info.Mode().Perm(); mode != 0o600 {
			t.Errorf("mode = %o, want 600", mode)
		}
	}
}

// Saving over an existing credential is what re-enrolment does, and a half-written
// node.json is a node nobody can recover without an operator.
func TestSavingReplacesAnExistingCredential(t *testing.T) {
	path := filepath.Join(t.TempDir(), "node.json")
	if err := aCredential().Save(path); err != nil {
		t.Fatalf("first save: %v", err)
	}

	second := aCredential()
	second.NodeID = "99999999-2222-3333-4444-555555555555"
	if err := second.Save(path); err != nil {
		t.Fatalf("second save: %v", err)
	}

	read, err := LoadCredential(path)
	if err != nil {
		t.Fatalf("load: %v", err)
	}
	if read.NodeID != second.NodeID {
		t.Errorf("node id = %q, want the second one", read.NodeID)
	}

	// No temporary file left behind.
	entries, err := os.ReadDir(filepath.Dir(path))
	if err != nil {
		t.Fatalf("read dir: %v", err)
	}
	if len(entries) != 1 {
		names := make([]string, 0, len(entries))
		for _, entry := range entries {
			names = append(names, entry.Name())
		}
		t.Errorf("directory holds %v, want only node.json", names)
	}
}

func TestCredentialRefusesWhatCannotBeUsed(t *testing.T) {
	cases := map[string]func(*Credential){
		"no node id":       func(c *Credential) { c.NodeID = "" },
		"no token":         func(c *Credential) { c.Token = "" },
		"no panel":         func(c *Credential) { c.Panel = "" },
		"unusable panel":   func(c *Credential) { c.Panel = "ftp://panel.example" },
		"no pin over tls":  func(c *Credential) { c.PanelCertificateSHA256 = "" },
		"unreadable pin":   func(c *Credential) { c.PanelCertificateSHA256 = "not-hex" },
		"short pin":        func(c *Credential) { c.PanelCertificateSHA256 = "abcd" },
		"pin with garbage": func(c *Credential) { c.PanelCertificateSHA256 = strings.Repeat("zz", 32) },
	}
	for name, break_ := range cases {
		t.Run(name, func(t *testing.T) {
			credential := aCredential()
			break_(&credential)
			if err := credential.Validate(); err == nil {
				t.Error("validated a credential that cannot be used")
			}
		})
	}
}

// A plaintext endpoint has no certificate, so demanding a pin for one would make the
// development setup impossible to express.
func TestPlaintextNeedsNoPin(t *testing.T) {
	credential := aCredential()
	credential.Panel = "http://127.0.0.1:9090"
	credential.PanelCertificateSHA256 = ""
	if err := credential.Validate(); err != nil {
		t.Errorf("validate: %v", err)
	}
}

func TestAKeyNobodyReadsIsRefused(t *testing.T) {
	path := filepath.Join(t.TempDir(), "node.json")
	body := `{"node_id":"a","credential":"b","panel":"https://panel.example",
	          "panel_certificate_sha256":"` + strings.Repeat("ab", 32) + `","insecure":true}`
	if err := os.WriteFile(path, []byte(body), 0o600); err != nil {
		t.Fatalf("write: %v", err)
	}
	if _, err := LoadCredential(path); err == nil {
		t.Error("a setting the daemon ignores was accepted, so an operator would believe it was in effect")
	}
}

func TestMissingCredentialSaysSo(t *testing.T) {
	_, err := LoadCredential(filepath.Join(t.TempDir(), "absent.json"))
	if err == nil {
		t.Fatal("loading a credential that does not exist succeeded")
	}
	if !strings.Contains(err.Error(), "absent.json") {
		t.Errorf("error %q does not name the file", err)
	}
}

// A struct whose default formatting prints a secret prints it eventually.
func TestPrintingACredentialNeverPrintsTheToken(t *testing.T) {
	credential := aCredential()
	for _, printed := range []string{
		fmt.Sprintf("%v", credential),
		fmt.Sprintf("%s", credential),
		fmt.Sprint(credential.LogValue()),
	} {
		if strings.Contains(printed, credential.Token) {
			t.Errorf("the token leaked into %q", printed)
		}
	}
}
