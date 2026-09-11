package runtime

import (
	"context"
	"errors"
	"log/slog"
	"slices"
	"strings"
	"testing"
)

const testBridge = "wsp000000000"

// listingFor renders what `iptables -S <chain>` prints for a chain that is already
// correct, so a test can hand it back and prove nothing gets rewritten.
func listingFor(bridge string) string {
	chain := egressChain(bridge)
	lines := []string{"-N " + chain}
	for _, rule := range egressRules(bridge) {
		lines = append(lines, "-A "+chain+" "+strings.Join(rule, " "))
	}
	return strings.Join(lines, "\n") + "\n"
}

func TestEgressBlocksTheMetadataEndpointAndEveryPrivateRange(t *testing.T) {
	docker := newDocker(t, readyEngine(), newHost())
	rules := egressRules(testBridge)

	// The two that keep the platform working have to be first, in this order: without
	// the conntrack return the reply from a container to the embedded Caddy - which
	// lives at the bridge's own gateway, inside 172.16/12 - is dropped and every site on
	// the node stops answering.
	if got := strings.Join(rules[0], " "); got != "-m conntrack --ctstate RELATED,ESTABLISHED -j RETURN" {
		t.Fatalf("first rule = %q, want the established-connection return", got)
	}
	if got := strings.Join(rules[1], " "); got != "-o "+testBridge+" -j RETURN" {
		t.Fatalf("second rule = %q, want the intra-tenant return", got)
	}

	dropped := map[string]bool{}
	for _, rule := range rules[2:] {
		if len(rule) != 4 || rule[0] != "-d" || rule[3] != "DROP" {
			t.Fatalf("rule %v is not a destination drop", rule)
		}
		dropped[rule[1]] = true
	}
	for _, required := range []string{
		"169.254.0.0/16", // every cloud's metadata endpoint lives in here
		"10.0.0.0/8",
		"172.16.0.0/12",
		"192.168.0.0/16",
		"100.64.0.0/10", // Alibaba metadata, Tailscale
		"127.0.0.0/8",
	} {
		if !dropped[required] {
			t.Errorf("%s is reachable from a customer container", required)
		}
	}
	_ = docker
}

func TestEgressInstallsTheChainAndJumpsIntoItFromBothDirections(t *testing.T) {
	host := newHost()
	docker := newDocker(t, readyEngine(), host)

	if err := docker.filterEgress(context.Background(), testBridge); err != nil {
		t.Fatalf("filterEgress: %v", err)
	}
	calls := host.invocations()
	chain := egressChain(testBridge)

	want := []string{
		"iptables -w 10 -t filter -N " + chain,
		"iptables -w 10 -t filter -I DOCKER-USER 1 -i " + testBridge + " -j " + chain,
		"iptables -w 10 -t filter -I INPUT 1 -i " + testBridge + " -j " + chain,
	}
	for _, expected := range want {
		if !slices.Contains(calls, expected) {
			t.Errorf("missing invocation %q\ngot:\n%s", expected, strings.Join(calls, "\n"))
		}
	}
	// INPUT as well as DOCKER-USER, because DOCKER-USER only sees forwarded traffic and
	// the host itself is the most interesting private address a container can reach.
	if !slices.Contains(calls, want[2]) {
		t.Error("nothing stops a container talking to the host it is running on")
	}
}

func TestEgressLeavesACorrectChainAlone(t *testing.T) {
	host := newHost()
	chain := egressChain(testBridge)
	host.answers["iptables -w 10 -t filter -S "+chain] = listingFor(testBridge)
	// Both jumps already present.
	host.answers["iptables -w 10 -t filter -C DOCKER-USER -i "+testBridge+" -j "+chain] = ""
	host.answers["iptables -w 10 -t filter -C INPUT -i "+testBridge+" -j "+chain] = ""

	docker := newDocker(t, readyEngine(), host)
	if err := docker.filterEgress(context.Background(), testBridge); err != nil {
		t.Fatalf("filterEgress: %v", err)
	}

	for _, call := range host.invocations() {
		if strings.Contains(call, " -F ") || strings.Contains(call, " -A ") {
			t.Errorf("rewrote a chain that was already right (%q), which leaves a window on "+
				"every pass where egress is unfiltered", call)
		}
	}
}

func TestEgressRebuildsAChainSomebodyChanged(t *testing.T) {
	host := newHost()
	chain := egressChain(testBridge)
	host.answers["iptables -w 10 -t filter -S "+chain] =
		"-N " + chain + "\n-A " + chain + " -d 10.0.0.0/8 -j ACCEPT\n"

	docker := newDocker(t, readyEngine(), host)
	if err := docker.filterEgress(context.Background(), testBridge); err != nil {
		t.Fatalf("filterEgress: %v", err)
	}

	calls := host.invocations()
	if !slices.Contains(calls, "iptables -w 10 -t filter -F "+chain) {
		t.Fatalf("a tampered chain was not emptied first:\n%s", strings.Join(calls, "\n"))
	}
	appended := 0
	for _, call := range calls {
		if strings.Contains(call, " -A "+chain+" ") {
			appended++
		}
	}
	if appended != len(egressRules(testBridge)) {
		t.Errorf("appended %d rules, want %d", appended, len(egressRules(testBridge)))
	}
}

// A node that cannot filter egress cannot safely run a customer's container, so it does
// not run one. The alternative - starting it anyway and logging - is a container with
// unauthenticated access to the host's cloud credentials.
func TestCreateRefusesWhenTheFilterWillNotInstall(t *testing.T) {
	host := newHost()
	host.fail = errors.New("iptables: command not found")
	docker := newDocker(t, readyEngine(), host)

	_, err := docker.Create(context.Background(), appWorkload(), "wf1:abc")
	if err == nil {
		t.Fatal("started a container whose egress could not be filtered")
	}
	if !strings.Contains(err.Error(), "metadata") {
		t.Errorf("error = %v, want it to say what is now reachable", err)
	}
	if isolation, err := docker.Isolation(context.Background()); err != nil || isolation.EgressFiltered {
		t.Error("Isolation still claims egress is filtered")
	}
}

func TestDevModeWarnsInsteadOfRefusingAndSaysSoOnTheNode(t *testing.T) {
	host := newHost()
	host.fail = errors.New("iptables: no chain/target/match by that name")

	docker, err := New(context.Background(), t.TempDir(), withEngine(readyEngine()),
		WithCommandRunner(host.run), WithLogger(slog.New(slog.DiscardHandler)), WithDevMode(true))
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	defer docker.Close()

	if _, err := docker.Create(context.Background(), appWorkload(), "wf1:abc"); err != nil {
		t.Fatalf("Create under --dev: %v", err)
	}
	isolation, err := docker.Isolation(context.Background())
	if err != nil {
		t.Fatalf("Isolation: %v", err)
	}
	if isolation.EgressFiltered {
		t.Error("a developer node with no working iptables reports its egress as filtered, " +
			"which is exactly the silent downgrade this platform exists to avoid")
	}
}

func TestABridgeNameIsStableAndFitsInTheKernelsLimit(t *testing.T) {
	first := bridgeName("wisper-tenant-1042")
	if first != bridgeName("wisper-tenant-1042") {
		t.Error("the same tenant produced two different bridge names")
	}
	if first == bridgeName("wisper-tenant-1043") {
		t.Error("two tenants share a bridge name")
	}
	if len(first) > 15 {
		t.Errorf("bridge name %q is %d characters, and the kernel allows 15", first, len(first))
	}
	if len(egressChain(first)) > 28 {
		t.Errorf("chain name %q is longer than iptables allows", egressChain(first))
	}
}

func TestTheTenantNetworkHasNoIPv6ToLeaveBy(t *testing.T) {
	api := readyEngine()
	api.onNetInspect = nil // nothing there yet, so it gets created
	docker := newDocker(t, api, newHost())

	if err := docker.ensureNetwork(context.Background(), "wisper-tenant-7"); err != nil {
		t.Fatalf("ensureNetwork: %v", err)
	}
	if len(api.networks) != 1 {
		t.Fatalf("created %d networks, want 1", len(api.networks))
	}
	created := api.networks[0].options
	if created.EnableIPv6 == nil || *created.EnableIPv6 {
		t.Error("IPv6 is enabled on a tenant network, and every rule in egress.go is an " +
			"iptables rule: the filter would be half installed")
	}
	if created.Internal {
		t.Error("the network is internal, so a customer cannot install a package")
	}
	if created.Options[bridgeNameOption] != bridgeName("wisper-tenant-7") {
		t.Errorf("bridge = %q, want the deterministic name the firewall rules are written "+
			"against", created.Options[bridgeNameOption])
	}
}
