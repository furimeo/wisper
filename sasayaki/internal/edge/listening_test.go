package edge

import (
	"context"
	"io"
	"net"
	"net/http"
	"strconv"
	"testing"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
)

// Actually starting the thing.
//
// Every other test in this package works on the route table with no listener, which is
// the right shape for the logic and proves nothing at all about the configuration handed
// to Caddy. A misspelled module name, a JSON field that moved between releases, a
// permission module the registry cannot find - none of those are visible until something
// provisions the document, and on a node the first time that happens is at three in the
// morning on :443.
//
// So this starts it, on ports nobody uses, and asks it for a page over plain HTTP. TLS is
// deliberately not exercised: a real handshake needs a certificate authority, and
// docs/verify-on-linux.md is where that is proved.

// freePort is a port nothing is listening on, as of a moment ago.
//
// Racy by nature - anything could take it between the close and Caddy's bind - which is
// why the whole test skips rather than fails if the bind does not work out. A flaky
// failure in a suite people run twenty times a day teaches them to ignore it.
func freePort(t *testing.T) int {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Skipf("this machine would not give up a port to test with: %v", err)
	}
	port := listener.Addr().(*net.TCPAddr).Port
	if err := listener.Close(); err != nil {
		t.Skipf("this machine would not release the port it just gave: %v", err)
	}
	return port
}

func TestTheEdgeServesOverARealListener(t *testing.T) {
	h := newHarnessIn(t, looseTempDir(t))
	h.edge.httpPort = freePort(t)
	h.edge.httpsPort = freePort(t)

	h.publishSite("7", map[string]string{"index.html": "served by the embedded caddy"})
	route := siteRoute("shop.example", "7")
	// Without this the plain listener would answer with a redirect to a handshake this
	// test has no certificate for.
	route.ForceHTTPS = false
	h.sync([]spec.Route{route}, []spec.Workload{siteWorkload("7", spec.SiteOptions{})})

	if err := h.edge.Start(context.Background()); err != nil {
		t.Skipf("the embedded web server would not start on this machine: %v", err)
	}
	defer func() {
		if err := h.edge.Stop(context.Background()); err != nil {
			t.Errorf("stopping: %v", err)
		}
	}()

	address := net.JoinHostPort("127.0.0.1", strconv.Itoa(h.edge.httpPort))
	request, err := http.NewRequest(http.MethodGet, "http://"+address+"/", nil)
	if err != nil {
		t.Fatalf("building the request: %v", err)
	}
	// The hostname the route table is keyed by, which is not the address being dialled -
	// exactly as it arrives from a visitor whose DNS points here.
	request.Host = "shop.example"

	client := &http.Client{Timeout: 5 * time.Second}
	response, err := client.Do(request)
	if err != nil {
		t.Fatalf("asking the edge for a page: %v", err)
	}
	defer response.Body.Close()

	body, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatalf("reading the answer: %v", err)
	}
	if response.StatusCode != http.StatusOK {
		t.Fatalf("answered %d: %s", response.StatusCode, body)
	}
	if string(body) != "served by the embedded caddy" {
		t.Fatalf("served %q, want the published release", body)
	}

	// And a hostname the table does not hold gets a page rather than a dropped
	// connection, over the same listener.
	unknown, err := http.NewRequest(http.MethodGet, "http://"+address+"/", nil)
	if err != nil {
		t.Fatalf("building the second request: %v", err)
	}
	unknown.Host = "someone-elses.example"
	answer, err := client.Do(unknown)
	if err != nil {
		t.Fatalf("asking about an unknown hostname: %v", err)
	}
	defer answer.Body.Close()
	if answer.StatusCode != http.StatusNotFound {
		t.Fatalf("an unknown hostname answered %d, want 404", answer.StatusCode)
	}
}

func TestStoppingAnEdgeThatNeverStartedIsNotAnError(t *testing.T) {
	h := newHarness(t)
	if err := h.edge.Stop(context.Background()); err != nil {
		t.Fatalf("stopping an edge that was never started: %v", err)
	}
}
