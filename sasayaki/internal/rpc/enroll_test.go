package rpc

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"errors"
	"testing"

	"github.com/furimeo/wisper/sasayaki/internal/version"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

func enrolmentFor(t *testing.T, panel *panelStub) EnrolmentRequest {
	t.Helper()
	_, key, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatalf("generate key: %v", err)
	}
	return EnrolmentRequest{
		Panel:              panel.address,
		BootstrapToken:     "single-use-token",
		Key:                key,
		MachineFingerprint: "f0f1f2f3",
		Doctor:             &wisperpb.DoctorReport{RequiredChecksPassed: true},
		Hostname:           "node-1",
		AdvertiseAddresses: []string{"203.0.113.10"},
	}
}

// Trust on first use: the fingerprint stored is the one from this very handshake, not
// one fetched afterwards by a second connection that could have landed elsewhere.
func TestEnrolPinsTheCertificateItActuallySpokeTo(t *testing.T) {
	panel := startPanel(t)

	var received *wisperpb.EnrollRequest
	panel.enroll = func(_ context.Context, request *wisperpb.EnrollRequest) (*wisperpb.EnrollResponse, error) {
		received = request
		return &wisperpb.EnrollResponse{
			NodeId:                 "node-uuid",
			Credential:             "long-lived",
			PanelCertificateSha256: panel.fingerprint,
			NodeName:               "node-1",
			ProtocolVersion:        uint32(version.Protocol),
		}, nil
	}

	request := enrolmentFor(t, panel)
	credential, err := Enrol(context.Background(), request)
	if err != nil {
		t.Fatalf("enrol: %v", err)
	}

	if credential.PanelCertificateSHA256 != panel.fingerprint {
		t.Errorf("pinned %q, want the certificate the panel presented %q",
			credential.PanelCertificateSHA256, panel.fingerprint)
	}
	if credential.NodeID != "node-uuid" || credential.Token != "long-lived" {
		t.Errorf("credential = %+v, want the panel's answer", credential)
	}
	if err := credential.Validate(); err != nil {
		t.Errorf("enrolment produced a credential that cannot be used: %v", err)
	}

	// The signature is what stops whoever intercepted the token from enrolling with a
	// key they invented.
	public := request.Key.Public().(ed25519.PublicKey)
	signed := []byte(enrolmentSignaturePrefix + request.BootstrapToken + "\n" + request.MachineFingerprint)
	if !ed25519.Verify(public, signed, received.GetSignature()) {
		t.Error("the enrolment signature does not verify over the documented message")
	}
	if string(received.GetPublicKey()) != string(public) {
		t.Error("the request must carry the public key the signature was made with")
	}
	if received.GetProtocolVersion() != uint32(version.Protocol) {
		t.Error("the panel must be able to refuse an incompatible node before it holds a credential")
	}
}

// The panel says which certificate is its own. If that is not what answered, something
// else terminated the TLS, and the node walks away with nothing written.
func TestEnrolRefusesWhenThePanelNamesADifferentCertificate(t *testing.T) {
	panel := startPanel(t)

	_, somebodyElse := selfSignedCertificate(t)
	panel.enroll = func(context.Context, *wisperpb.EnrollRequest) (*wisperpb.EnrollResponse, error) {
		return &wisperpb.EnrollResponse{
			NodeId:                 "node-uuid",
			Credential:             "long-lived",
			PanelCertificateSha256: somebodyElse,
			ProtocolVersion:        uint32(version.Protocol),
		}, nil
	}

	credential, err := Enrol(context.Background(), enrolmentFor(t, panel))
	var mismatch *FingerprintMismatch
	if !errors.As(err, &mismatch) {
		t.Fatalf("enrol error = %v, want a fingerprint mismatch", err)
	}
	if credential.Token != "" {
		t.Error("a credential was returned for a handshake that could not be trusted")
	}
	if mismatch.Observed != panel.fingerprint {
		t.Errorf("observed %q, want the certificate that actually answered %q", mismatch.Observed, panel.fingerprint)
	}
}

func TestEnrolRefusesAProtocolThePanelDoesNotSpeak(t *testing.T) {
	panel := startPanel(t)
	panel.enroll = func(context.Context, *wisperpb.EnrollRequest) (*wisperpb.EnrollResponse, error) {
		return &wisperpb.EnrollResponse{
			NodeId:                 "node-uuid",
			Credential:             "long-lived",
			PanelCertificateSha256: panel.fingerprint,
			ProtocolVersion:        uint32(version.Protocol) + 5,
		}, nil
	}

	_, err := Enrol(context.Background(), enrolmentFor(t, panel))
	var mismatch *ProtocolMismatch
	if !errors.As(err, &mismatch) {
		t.Fatalf("enrol error = %v, want a protocol mismatch", err)
	}
}

// A panel that does not state a fingerprint - the common case behind a tunnel, where
// the panel does not know the certificate the tunnel presents - still gets pinned. That
// is what trust on first use means.
func TestEnrolPinsEvenWhenThePanelStatesNothing(t *testing.T) {
	panel := startPanel(t)
	panel.enroll = func(context.Context, *wisperpb.EnrollRequest) (*wisperpb.EnrollResponse, error) {
		return &wisperpb.EnrollResponse{
			NodeId:          "node-uuid",
			Credential:      "long-lived",
			ProtocolVersion: uint32(version.Protocol),
		}, nil
	}

	credential, err := Enrol(context.Background(), enrolmentFor(t, panel))
	if err != nil {
		t.Fatalf("enrol: %v", err)
	}
	if credential.PanelCertificateSHA256 != panel.fingerprint {
		t.Errorf("pinned %q, want %q", credential.PanelCertificateSHA256, panel.fingerprint)
	}
}

func TestEnrolChecksItsRequestBeforeDialling(t *testing.T) {
	_, key, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatalf("generate key: %v", err)
	}

	cases := map[string]EnrolmentRequest{
		"no panel":       {BootstrapToken: "t", Key: key, MachineFingerprint: "f"},
		"no token":       {Panel: "https://panel.test", Key: key, MachineFingerprint: "f"},
		"no key":         {Panel: "https://panel.test", BootstrapToken: "t", MachineFingerprint: "f"},
		"no fingerprint": {Panel: "https://panel.test", BootstrapToken: "t", Key: key},
	}
	for name, request := range cases {
		t.Run(name, func(t *testing.T) {
			if _, err := Enrol(context.Background(), request); err == nil {
				t.Error("enrolled with an incomplete request")
			}
		})
	}
}
