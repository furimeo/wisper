package bootstrap

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"net"
	"net/http"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/rpc"
)

// How long one probe may take. Short, because doctor is run interactively and a panel
// behind a dead tunnel would otherwise hold the terminal for a TCP timeout.
const panelProbeTimeout = 8 * time.Second

// reachPanel finds out whether the panel is there, and what time it thinks it is.
//
// Three separate failures, reported separately, because they need three different
// remedies and an installer that collapses them into "could not connect" sends an
// administrator to look at the wrong thing (design section 7.2):
//
//   - resolve: the name is wrong, or this machine has no working DNS;
//   - connect: the name resolves but nothing is listening - a tunnel that is down, or
//     the wrong port;
//   - tls: something answered but the handshake failed.
//
// A fourth failure - the panel is fine and refuses the token - is deliberately not
// visible here. Doctor holds no token. Enrolment reports it, and keeps it distinct from
// all of the above.
//
// The certificate is not validated. This is a reachability probe, not a trust decision:
// the trust decision is the pin taken during enrolment (rpc/pin.go), and refusing to
// probe a panel with a private certificate would make doctor useless on exactly the
// deployments wisper is built for.
func reachPanel(ctx context.Context, raw string) panelProbe {
	endpoint, err := rpc.ParseEndpoint(raw)
	if err != nil {
		return panelProbe{Endpoint: raw, Stage: "parse", Err: err}
	}

	probe := panelProbe{Endpoint: endpoint.Target}
	ctx, cancel := context.WithTimeout(ctx, panelProbeTimeout)
	defer cancel()

	host, _, splitErr := net.SplitHostPort(endpoint.Target)
	if splitErr == nil && net.ParseIP(host) == nil {
		if _, err := net.DefaultResolver.LookupHost(ctx, host); err != nil {
			probe.Stage = "resolve"
			probe.Err = err
			return probe
		}
	}

	started := time.Now()
	dialer := &net.Dialer{}
	connection, err := dialer.DialContext(ctx, "tcp", endpoint.Target)
	if err != nil {
		probe.Stage = "connect"
		probe.Err = err
		return probe
	}

	if endpoint.Plaintext {
		connection.Close()
		probe.Reachable = true
		probe.Stage = "connect"
		probe.RoundTrip = time.Since(started)
		probe.ServerTime = panelClock(ctx, endpoint.Target, false)
		probe.LocalTime = time.Now()
		return probe
	}

	handshake := tls.Client(connection, &tls.Config{
		ServerName: endpoint.ServerName,
		MinVersion: tls.VersionTLS12,
		// See the commentary above: reachability, not trust.
		InsecureSkipVerify: true,
	})
	err = handshake.HandshakeContext(ctx)
	handshake.Close()
	if err != nil {
		probe.Stage = "tls"
		probe.Err = err
		return probe
	}

	probe.Reachable = true
	probe.Stage = "tls"
	probe.RoundTrip = time.Since(started)
	probe.ServerTime = panelClock(ctx, endpoint.Target, true)
	probe.LocalTime = time.Now()
	return probe
}

// panelClock reads the Date header off one HTTP response.
//
// Every HTTP server sends one, including a gRPC endpoint answering a request it does not
// recognise, so the status code is ignored on purpose - a 404 with a Date header answers
// the question this is asking. It is the panel's clock the node has to agree with:
// nothing else in the system cares whether the node agrees with an NTP pool, and a node
// whose time matches the panel's will negotiate TLS with it (design section 7.2).
//
// A zero time means the panel is reachable but said nothing about the time, which the
// clock check reports as "could not measure" rather than as skew.
func panelClock(ctx context.Context, target string, useTLS bool) time.Time {
	scheme := "http"
	if useTLS {
		scheme = "https"
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, scheme+"://"+target+"/health", nil)
	if err != nil {
		return time.Time{}
	}

	client := &http.Client{
		Timeout: panelProbeTimeout,
		Transport: &http.Transport{
			TLSClientConfig:   &tls.Config{InsecureSkipVerify: true, MinVersion: tls.VersionTLS12},
			DisableKeepAlives: true,
		},
		// A redirect to another host would be answering with somebody else's clock.
		CheckRedirect: func(*http.Request, []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}
	response, err := client.Do(request)
	if err != nil {
		return time.Time{}
	}
	defer response.Body.Close()

	stated := response.Header.Get("Date")
	if stated == "" {
		return time.Time{}
	}
	parsed, err := http.ParseTime(stated)
	if err != nil {
		return time.Time{}
	}
	return parsed
}

// describe turns a failed probe into the sentence a check prints.
func (p panelProbe) describe() string {
	if p.Reachable {
		return fmt.Sprintf("%s answered in %s", p.Endpoint, p.RoundTrip.Round(time.Millisecond))
	}
	switch p.Stage {
	case "parse":
		return fmt.Sprintf("%s is not an endpoint this node can dial: %v", p.Endpoint, p.Err)
	case "resolve":
		return fmt.Sprintf("the name in %s does not resolve: %v", p.Endpoint, p.Err)
	case "connect":
		return fmt.Sprintf("nothing is listening on %s: %v", p.Endpoint, p.Err)
	case "tls":
		return fmt.Sprintf("%s answered but the TLS handshake failed: %v", p.Endpoint, p.Err)
	default:
		return fmt.Sprintf("%s could not be reached: %v", p.Endpoint, p.Err)
	}
}

// remedy is what to do about a probe that failed.
func (p panelProbe) remedy() string {
	switch p.Stage {
	case "parse":
		return "Pass --panel as https://panel.example or https://panel.example:9443."
	case "resolve":
		return "Check /etc/resolv.conf and that the panel's name is published where this " +
			"machine can see it."
	case "connect":
		return "Check the tunnel in front of the panel is up and that the port is the one " +
			"the panel's gRPC listener is published on."
	case "tls":
		return "The endpoint is not speaking TLS. Use http:// if the panel's gRPC port is " +
			"reached over something already private, such as Tailscale."
	default:
		return "Check this machine has a route to the panel."
	}
}

// timedOut distinguishes a probe cut short by the caller's context from one that failed
// on its own. Used by the reachability check, which should not blame the network for an
// operator pressing Ctrl-C.
func (p panelProbe) timedOut() bool {
	return errors.Is(p.Err, context.Canceled) || errors.Is(p.Err, context.DeadlineExceeded)
}
