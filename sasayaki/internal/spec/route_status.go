package spec

import (
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// CertificateState is how far the edge has got with one hostname's certificate.
type CertificateState string

const (
	// CertificateUnspecified is the zero value: nothing has looked.
	CertificateUnspecified CertificateState = "UNSPECIFIED"
	// CertificateNone is a route that does not want one - TLS is off for it.
	CertificateNone    CertificateState = "NONE"
	CertificateIssuing CertificateState = "ISSUING"
	CertificateValid   CertificateState = "VALID"
	CertificateFailed  CertificateState = "FAILED"
)

// RouteStatus is whether a route is actually serving.
//
// The customer pointed DNS at this node and wants to know why nothing happens; this is the
// answer, and it is the reason the edge reports rather than merely logging.
type RouteStatus struct {
	Domain string
	// The edge has the route loaded and a backend to send it to.
	Serving     bool
	Certificate CertificateState
	// So the panel can warn before a renewal that has been failing quietly runs out.
	NotAfter time.Time
	// The ACME failure, verbatim. "DNS does not point here yet" and "rate limited" need
	// completely different actions from the customer, and summarising them into one word
	// loses exactly the part that tells them apart.
	LastError string
}

// ToProto renders the status for the wire.
func (r RouteStatus) ToProto() *wisperpb.RouteStatus {
	return &wisperpb.RouteStatus{
		Domain:              r.Domain,
		Serving:             r.Serving,
		Certificate:         certificateStateToProto(r.Certificate),
		CertificateNotAfter: wireInstant(r.NotAfter),
		LastError:           r.LastError,
	}
}

// RouteStatusFromProto reads one back, for the statuses internal/state keeps across a
// restart.
func RouteStatusFromProto(message *wisperpb.RouteStatus) RouteStatus {
	return RouteStatus{
		Domain:      message.GetDomain(),
		Serving:     message.GetServing(),
		Certificate: certificateStateFromProto(message.GetCertificate()),
		NotAfter:    instant(message.GetCertificateNotAfter()),
		LastError:   message.GetLastError(),
	}
}

func certificateStateToProto(value CertificateState) wisperpb.CertificateState {
	switch value {
	case CertificateNone:
		return wisperpb.CertificateState_CERTIFICATE_STATE_NONE
	case CertificateIssuing:
		return wisperpb.CertificateState_CERTIFICATE_STATE_ISSUING
	case CertificateValid:
		return wisperpb.CertificateState_CERTIFICATE_STATE_VALID
	case CertificateFailed:
		return wisperpb.CertificateState_CERTIFICATE_STATE_FAILED
	default:
		return wisperpb.CertificateState_CERTIFICATE_STATE_UNSPECIFIED
	}
}

func certificateStateFromProto(value wisperpb.CertificateState) CertificateState {
	switch value {
	case wisperpb.CertificateState_CERTIFICATE_STATE_NONE:
		return CertificateNone
	case wisperpb.CertificateState_CERTIFICATE_STATE_ISSUING:
		return CertificateIssuing
	case wisperpb.CertificateState_CERTIFICATE_STATE_VALID:
		return CertificateValid
	case wisperpb.CertificateState_CERTIFICATE_STATE_FAILED:
		return CertificateFailed
	default:
		return CertificateUnspecified
	}
}
