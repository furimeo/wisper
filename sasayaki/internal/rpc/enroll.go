package rpc

import (
	"context"
	"crypto/ed25519"
	"fmt"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/version"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// The domain separator in the enrolment signature. Without one, a signature produced
// for some other purpose by the same key could be replayed as an enrolment; with one,
// what is signed can only ever have been meant as "this key, enrolling with this token,
// from this machine".
const enrolmentSignaturePrefix = "wisper-enroll-v1\n"

// EnrolmentRequest is everything the panel needs to admit a new node, gathered by the
// bootstrap package and put on the wire by this one.
type EnrolmentRequest struct {
	// The --panel value, in the form ParseEndpoint accepts.
	Panel string

	// Single-use, fifteen-minute TTL. Read from a file or from stdin, never from argv,
	// which every user on the machine can read out of ps.
	BootstrapToken string

	// The node's permanent identity. It outlives any credential, which is what makes
	// the signature below provable rather than decorative.
	Key ed25519.PrivateKey

	// Hex SHA-256 over machine-id and hardware serials. The panel uses it to notice a
	// cloned VM: one fingerprint arriving from two addresses suspends both rather than
	// quietly splitting a workload between them (design section 7.3).
	MachineFingerprint string

	// The preflight result. May be nil when an operator passed --skip-doctor, and the
	// panel then has nothing to show on the node's page - which is the cost of skipping
	// it, not a failure.
	Doctor *wisperpb.DoctorReport

	Hostname           string
	AdvertiseAddresses []string
}

// Enrol joins a panel and returns the credential to write to node.json.
//
// This is where trust on first use happens. The connection has no pin - there cannot be
// one yet - so the certificate that answers is recorded during the handshake, compared
// with what the panel says its certificate is, and stored. From this call onwards the
// node will refuse anything else (pin.go).
//
// Nothing is written to disk here. Enrolment either produces a complete credential or
// an error, and a node that half-enrolled is a node an operator has to clean up by
// hand.
func Enrol(ctx context.Context, request EnrolmentRequest) (Credential, error) {
	if err := request.validate(); err != nil {
		return Credential{}, err
	}

	endpoint, err := ParseEndpoint(request.Panel)
	if err != nil {
		return Credential{}, err
	}

	var observer *certificateObserver
	if !endpoint.Plaintext {
		observer = &certificateObserver{}
	}

	connection, err := dial(dialSpec{endpoint: endpoint, observer: observer})
	if err != nil {
		return Credential{}, err
	}
	defer connection.Close()

	public := request.Key.Public().(ed25519.PublicKey)
	response, err := wisperpb.NewNodeServiceClient(connection).Enroll(ctx, &wisperpb.EnrollRequest{
		BootstrapToken:     request.BootstrapToken,
		PublicKey:          public,
		Signature:          enrolmentSignature(request.Key, request.BootstrapToken, request.MachineFingerprint),
		MachineFingerprint: request.MachineFingerprint,
		Doctor:             request.Doctor,
		AgentVersion:       version.Number,
		ProtocolVersion:    uint32(version.Protocol),
		Hostname:           request.Hostname,
		AdvertiseAddresses: request.AdvertiseAddresses,
	})
	if err != nil {
		return Credential{}, fmt.Errorf("enrol with %s: %w", endpoint, err)
	}

	if response.GetProtocolVersion() != uint32(version.Protocol) {
		return Credential{}, &ProtocolMismatch{
			Node:  uint32(version.Protocol),
			Panel: response.GetProtocolVersion(),
		}
	}
	if response.GetNodeId() == "" || response.GetCredential() == "" {
		return Credential{}, fmt.Errorf("enrol with %s: the panel returned an incomplete credential", endpoint)
	}

	pin, err := agreedPin(endpoint, observer, response.GetPanelCertificateSha256())
	if err != nil {
		return Credential{}, err
	}

	credential := Credential{
		NodeID:                 response.GetNodeId(),
		NodeName:               response.GetNodeName(),
		Token:                  response.GetCredential(),
		Panel:                  endpoint.String(),
		PanelCertificateSHA256: pin,
		EnrolledAt:             time.Now().UTC().Truncate(time.Second),
	}
	if err := credential.Validate(); err != nil {
		return Credential{}, fmt.Errorf("the panel returned a credential this node cannot use: %w", err)
	}
	return credential, nil
}

// agreedPin decides what this node will insist on from now on.
//
// The certificate observed during this very handshake is the one that gets stored -
// not one fetched by a second connection, which could have landed somewhere else. When
// the panel also states its fingerprint, the two have to agree: a difference means
// something other than the panel terminated the TLS, and the enrolment is abandoned
// rather than pinned to the impostor.
func agreedPin(endpoint Endpoint, observer *certificateObserver, stated string) (string, error) {
	declared, err := NormalisePin(stated)
	if err != nil {
		return "", fmt.Errorf("the panel stated an unreadable certificate fingerprint: %w", err)
	}

	if endpoint.Plaintext {
		if declared != "" {
			return "", fmt.Errorf("the panel stated a certificate fingerprint but %s is plaintext, "+
				"so there is no certificate to compare it with: dial https:// instead", endpoint)
		}
		return "", nil
	}

	observed := observer.seen()
	if observed == "" {
		return "", fmt.Errorf("enrolled over TLS but saw no certificate to pin")
	}
	if declared != "" && declared != observed {
		return "", &FingerprintMismatch{Expected: declared, Observed: observed}
	}
	return observed, nil
}

// enrolmentSignature proves the sender holds the private key for the public key it is
// presenting. Without it, whoever intercepts a bootstrap token can enrol with a key
// they invented (node.proto, EnrollRequest.signature).
func enrolmentSignature(key ed25519.PrivateKey, bootstrapToken, machineFingerprint string) []byte {
	message := enrolmentSignaturePrefix + bootstrapToken + "\n" + machineFingerprint
	return ed25519.Sign(key, []byte(message))
}

func (r EnrolmentRequest) validate() error {
	switch {
	case r.Panel == "":
		return fmt.Errorf("enrol: no panel endpoint")
	case r.BootstrapToken == "":
		return fmt.Errorf("enrol: no bootstrap token")
	case len(r.Key) != ed25519.PrivateKeySize:
		return fmt.Errorf("enrol: the node key is %d bytes, want %d", len(r.Key), ed25519.PrivateKeySize)
	case r.MachineFingerprint == "":
		return fmt.Errorf("enrol: no machine fingerprint, so the panel could not detect a cloned node")
	default:
		return nil
	}
}
