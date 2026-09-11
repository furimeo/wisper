package rpc

import (
	"context"
	"strconv"

	"github.com/furimeo/wisper/sasayaki/internal/version"
)

// The three headers every authenticated call carries. They are written down in
// node.proto and nowhere else, and these constants are that file's other half.
//
//	wisper-node-id      the node id from EnrollResponse
//	wisper-node-token   the long-lived credential from EnrollResponse
//	wisper-protocol     the protocol version, in decimal, so the panel can refuse an
//	                    incompatible node before it has parsed a frame
const (
	MetadataNodeID    = "wisper-node-id"
	MetadataNodeToken = "wisper-node-token"
	MetadataProtocol  = "wisper-protocol"
)

// nodeCredentials attaches those headers to every RPC on the connection.
//
// gRPC's own PerRPCCredentials rather than an interceptor, because an interceptor has
// to be written twice - once for unary calls and once for streams - and forgetting the
// second one produces a control stream that authenticates and file operations that do
// not.
type nodeCredentials struct {
	nodeID string
	token  string
}

func (n nodeCredentials) GetRequestMetadata(context.Context, ...string) (map[string]string, error) {
	return map[string]string{
		MetadataNodeID:    n.nodeID,
		MetadataNodeToken: n.token,
		MetadataProtocol:  strconv.FormatUint(uint64(version.Protocol), 10),
	}, nil
}

// RequireTransportSecurity is false, and that is not a relaxation.
//
// gRPC's idea of transport security is a verified certificate chain, which this client
// deliberately does not have: it pins one certificate instead (see pin.go). Returning
// true here would refuse every pinned connection and every plaintext development
// endpoint, so the check has to be answered by the code that actually knows what the
// transport is - dial.go, which refuses to build a TLS connection with no pin at all.
func (n nodeCredentials) RequireTransportSecurity() bool { return false }
