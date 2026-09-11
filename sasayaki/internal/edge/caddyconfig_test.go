package edge

import (
	"encoding/json"
	"path/filepath"
	"strings"
	"testing"

	"github.com/caddyserver/caddy/v2"
	"github.com/caddyserver/caddy/v2/modules/caddyhttp"
)

// The configuration Caddy is handed.
//
// Asserted as data rather than by starting Caddy, because starting it means binding two
// privileged ports and reaching a certificate authority, neither of which belongs in a
// unit test. What can be checked here is everything that would be a silent misconfiguration
// on a real node: the wrong port, an admin socket left open, certificates written into a
// developer's home directory, or an on-demand policy with no permission module - which
// Caddy would refuse outright, but only on the node, at three in the morning.

func decodeConfig(t *testing.T, h *harness) map[string]any {
	t.Helper()
	config, err := h.edge.caddyConfig()
	if err != nil {
		t.Fatalf("building the configuration: %v", err)
	}
	encoded, err := json.Marshal(config)
	if err != nil {
		t.Fatalf("encoding the configuration: %v", err)
	}
	var decoded map[string]any
	if err := json.Unmarshal(encoded, &decoded); err != nil {
		t.Fatalf("decoding the configuration: %v", err)
	}
	return decoded
}

func TestConfigListensOnBothPorts(t *testing.T) {
	h := newHarness(t)
	config, err := h.edge.caddyConfig()
	if err != nil {
		t.Fatalf("building the configuration: %v", err)
	}

	var app caddyhttp.App
	if err := json.Unmarshal(config.AppsRaw["http"], &app); err != nil {
		t.Fatalf("decoding the web server app: %v", err)
	}

	secure, found := app.Servers["wisper_secure"]
	if !found {
		t.Fatal("there is no secure listener")
	}
	if len(secure.Listen) != 1 || secure.Listen[0] != ":18443" {
		t.Fatalf("the secure listener is on %v", secure.Listen)
	}
	if len(secure.TLSConnPolicies) == 0 {
		t.Fatal("the secure listener has no connection policy, so it would not terminate " +
			"TLS at all with automatic HTTPS switched off")
	}

	insecure, found := app.Servers["wisper_insecure"]
	if !found {
		t.Fatal("there is no plain listener, so the ACME HTTP-01 challenge could never be " +
			"answered and no certificate would ever be issued")
	}
	if len(insecure.Listen) != 1 || insecure.Listen[0] != ":18080" {
		t.Fatalf("the plain listener is on %v", insecure.Listen)
	}
}

func TestConfigDisablesTheAdminEndpoint(t *testing.T) {
	h := newHarness(t)
	config, err := h.edge.caddyConfig()
	if err != nil {
		t.Fatalf("building the configuration: %v", err)
	}
	if config.Admin == nil || !config.Admin.Disabled {
		t.Fatal("the admin endpoint is enabled: it is a socket that can replace this " +
			"daemon's entire configuration and nothing here uses it")
	}
}

func TestConfigKeepsCertificatesUnderTheStateDirectory(t *testing.T) {
	h := newHarness(t)
	decoded := decodeConfig(t, h)

	storage, ok := decoded["storage"].(map[string]any)
	if !ok {
		t.Fatal("no storage module is configured, so certmagic would write into a " +
			"per-user directory that changes with the daemon's environment")
	}
	wanted := filepath.Join(h.stateDir, certificateDirName)
	if storage["root"] != wanted {
		t.Fatalf("certificates go to %v, want %s", storage["root"], wanted)
	}
}

func TestConfigAsksThisProcessForOnDemandPermission(t *testing.T) {
	h := newHarness(t)
	decoded := decodeConfig(t, h)

	apps := decoded["apps"].(map[string]any)
	tls := apps["tls"].(map[string]any)
	automation := tls["automation"].(map[string]any)

	policies := automation["policies"].([]any)
	if len(policies) != 1 {
		t.Fatalf("there are %d automation policies, want one covering every name", len(policies))
	}
	if policy := policies[0].(map[string]any); policy["on_demand"] != true {
		t.Fatal("on-demand issuance is off, so a hostname the customer just added would " +
			"never get a certificate until the daemon was restarted")
	}

	onDemand := automation["on_demand"].(map[string]any)
	permission := onDemand["permission"].(map[string]any)
	if permission["module"] != "wisper_edge" {
		t.Fatalf("the permission module is %v: anything else asks something outside this "+
			"process, which is exactly what must keep working when the panel is down",
			permission["module"])
	}
	if permission["edge"] != h.edge.id {
		t.Fatalf("the permission module points at %v, not at this edge", permission["edge"])
	}
}

func TestConfigCompressesAndLogsEveryRequest(t *testing.T) {
	h := newHarness(t)
	decoded := decodeConfig(t, h)

	apps := decoded["apps"].(map[string]any)
	servers := apps["http"].(map[string]any)["servers"].(map[string]any)

	for name := range servers {
		server := servers[name].(map[string]any)
		routes := server["routes"].([]any)
		if len(routes) != 1 {
			t.Fatalf("%s has %d routes, want the one that matches everything", name, len(routes))
		}

		handlers := routes[0].(map[string]any)["handle"].([]any)
		if len(handlers) != 2 {
			t.Fatalf("%s has %d handlers, want compression and then the router",
				name, len(handlers))
		}
		if got := handlers[0].(map[string]any)["handler"]; got != "encode" {
			t.Errorf("%s compresses with %v", name, got)
		}
		if got := handlers[1].(map[string]any)["handler"]; got != "wisper_edge" {
			t.Errorf("%s routes with %v", name, got)
		}

		logs, found := server["logs"].(map[string]any)
		if !found || logs["default_logger_name"] != accessLoggerName {
			t.Errorf("%s does not send its access log anywhere the daemon can ship it", name)
		}
	}

	logging := decoded["logging"].(map[string]any)["logs"].(map[string]any)
	access, found := logging[accessLoggerName].(map[string]any)
	if !found {
		t.Fatal("there is no access log")
	}
	writer := access["writer"].(map[string]any)
	if writer["output"] != "file" {
		t.Fatalf("the access log is written by %v, want a file", writer["output"])
	}
	if got := writer["filename"].(string); !strings.HasSuffix(got, accessLogFileName) {
		t.Fatalf("the access log goes to %s", got)
	}
}

func TestConfigSetsTheTimeoutsThatHaveARightAnswer(t *testing.T) {
	h := newHarness(t)
	config, err := h.edge.caddyConfig()
	if err != nil {
		t.Fatalf("building the configuration: %v", err)
	}
	var app caddyhttp.App
	if err := json.Unmarshal(config.AppsRaw["http"], &app); err != nil {
		t.Fatalf("decoding the web server app: %v", err)
	}

	server := app.Servers["wisper_secure"]
	if server.ReadHeaderTimeout != caddy.Duration(readHeaderTimeout) {
		t.Errorf("the header timeout is %v", server.ReadHeaderTimeout)
	}
	if server.IdleTimeout != caddy.Duration(idleTimeout) {
		t.Errorf("the idle timeout is %v", server.IdleTimeout)
	}
	if server.MaxHeaderBytes != maxHeaderBytes {
		t.Errorf("the header size cap is %d", server.MaxHeaderBytes)
	}
	// Deliberately absent: a customer's upload over a phone's 4G and a customer's log
	// stream are both indistinguishable from a stall until they finish.
	if server.ReadTimeout != 0 || server.WriteTimeout != 0 {
		t.Errorf("a whole-request deadline is set (%v read, %v write), which cuts off "+
			"uploads and server-sent events", server.ReadTimeout, server.WriteTimeout)
	}
}

func TestNewRefusesAnEdgeThatCannotServe(t *testing.T) {
	for name, options := range map[string]Options{
		"no state directory":  {Backends: newFakeBackends(), Store: newFakeStore()},
		"no address resolver": {StateDir: t.TempDir(), Store: newFakeStore()},
		"no store":            {StateDir: t.TempDir(), Backends: newFakeBackends()},
		"a relative state directory": {
			StateDir: "var/lib/wisper", Backends: newFakeBackends(), Store: newFakeStore(),
		},
	} {
		if _, err := New(options); err == nil {
			t.Errorf("an edge with %s was built", name)
		}
	}
}
