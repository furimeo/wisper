package rpc

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"strings"
	"testing"
)

func TestFingerprintIsTheHexSha256OfTheCertificate(t *testing.T) {
	der := []byte("pretend this is a certificate")
	sum := sha256.Sum256(der)

	got := Fingerprint(der)
	if got != hex.EncodeToString(sum[:]) {
		t.Errorf("Fingerprint = %q, want the hex SHA-256", got)
	}
	if got != strings.ToLower(got) {
		t.Error("fingerprints must be lower case: the panel sends them that way and they are compared as strings")
	}
}

func TestNormalisePinAcceptsTheShapesPeoplePaste(t *testing.T) {
	want := strings.Repeat("ab", 32)
	spaced := strings.Join(splitPairs(want), ":")

	for _, given := range []string{
		want,
		strings.ToUpper(want),
		"sha256:" + want,
		spaced,
		"  " + want + "  ",
	} {
		got, err := NormalisePin(given)
		if err != nil {
			t.Fatalf("NormalisePin(%q): %v", given, err)
		}
		if got != want {
			t.Errorf("NormalisePin(%q) = %q, want %q", given, got, want)
		}
	}
}

func TestNormalisePinRefusesWhatIsNotAFingerprint(t *testing.T) {
	for _, given := range []string{"nonsense", "abcd", strings.Repeat("ab", 33)} {
		if _, err := NormalisePin(given); err == nil {
			t.Errorf("NormalisePin(%q) was accepted", given)
		}
	}
	if got, err := NormalisePin(""); err != nil || got != "" {
		t.Errorf("NormalisePin(\"\") = %q, %v; an absent pin is not a malformed one", got, err)
	}
}

func TestVerifyAgainstPinReportsTheCertificateItSaw(t *testing.T) {
	certificate := []byte("the panel's certificate")
	other := []byte("somebody else's certificate")

	var reported *FingerprintMismatch
	verify := verifyAgainstPin(Fingerprint(certificate), func(m *FingerprintMismatch) { reported = m })

	if err := verify([][]byte{certificate}, nil); err != nil {
		t.Fatalf("the pinned certificate was refused: %v", err)
	}
	if reported != nil {
		t.Fatal("a matching certificate was reported as a mismatch")
	}

	err := verify([][]byte{other}, nil)
	var mismatch *FingerprintMismatch
	if !errors.As(err, &mismatch) {
		t.Fatalf("error = %v, want a fingerprint mismatch", err)
	}
	if mismatch.Observed != Fingerprint(other) || mismatch.Expected != Fingerprint(certificate) {
		t.Errorf("mismatch = %+v, want both fingerprints", mismatch)
	}
	if reported == nil {
		t.Error("the mismatch was not reported: gRPC flattens it into a generic connection failure otherwise")
	}
	if !strings.Contains(mismatch.Error(), "re-enrol") {
		t.Error("the message does not tell an operator what to do")
	}

	// A handshake with no certificate at all is refused rather than treated as a match.
	if err := verify(nil, nil); err == nil {
		t.Error("a peer that presented no certificate was accepted")
	}
}

func TestTheObserverRecordsTheLeaf(t *testing.T) {
	certificate := []byte("the panel's certificate")
	observer := &certificateObserver{}

	if err := observer.verify([][]byte{certificate, []byte("an intermediate")}, nil); err != nil {
		t.Fatalf("observe: %v", err)
	}
	if got := observer.seen(); got != Fingerprint(certificate) {
		t.Errorf("observed %q, want the leaf %q", got, Fingerprint(certificate))
	}
	if err := observer.verify(nil, nil); err == nil {
		t.Error("a peer that presented no certificate was accepted")
	}
}

func splitPairs(hexadecimal string) []string {
	pairs := make([]string, 0, len(hexadecimal)/2)
	for index := 0; index < len(hexadecimal); index += 2 {
		pairs = append(pairs, hexadecimal[index:index+2])
	}
	return pairs
}
