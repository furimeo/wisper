package runtime

import (
	"context"
	"fmt"
	"strings"
	"time"
)

// Talking to iptables.
//
// The mechanics of installing a rule set, kept away from egress.go so that what is
// blocked and how it is blocked can be read separately. There is no netlink library here:
// the `iptables` binary is on every host that runs Docker - the engine shells out to it
// too - it speaks to whichever backend the distribution chose, legacy or nft, and a
// library binding would be a second opinion about a table the engine is also writing.
const (
	firewallTool = "iptables"

	// One invocation's budget. iptables takes the xtables lock, and a daemon that is
	// mid-reload can hold it; -w makes iptables wait rather than fail, and this bounds
	// how long it waits for.
	firewallTimeout = 15 * time.Second

	// Seconds passed to iptables' own -w, kept below firewallTimeout so the tool gives
	// up and says so rather than being killed with no explanation.
	firewallLockWait = "10"
)

// syncChain makes a chain contain exactly these rules, in this order.
//
// Read first, write only on a difference. The read is one invocation and the steady state
// is every reconcile pass finding nothing to do, which matters because the alternative -
// flush and rebuild every time - leaves a window on every pass where a container's egress
// is unfiltered.
func (d *Docker) syncChain(ctx context.Context, chain string, rules [][]string) error {
	ctx, cancel := context.WithTimeout(ctx, firewallTimeout)
	defer cancel()

	current, err := d.iptables(ctx, "-S", chain)
	if err == nil && sameRules(chain, current, rules) {
		return nil
	}
	if err != nil {
		// The chain is not there, which is the normal first-run answer. Creating it is
		// the same work as fixing it, so there is no branch here beyond this one.
		if _, createErr := d.iptables(ctx, "-N", chain); createErr != nil {
			return fmt.Errorf("create the chain %s: %w", chain, createErr)
		}
	} else if _, flushErr := d.iptables(ctx, "-F", chain); flushErr != nil {
		return fmt.Errorf("empty the chain %s before rewriting it: %w", chain, flushErr)
	}

	for _, rule := range rules {
		arguments := append([]string{"-A", chain}, rule...)
		if _, err := d.iptables(ctx, arguments...); err != nil {
			return fmt.Errorf("add %s to %s: %w", strings.Join(rule, " "), chain, err)
		}
	}
	return nil
}

// jumpTo sends everything arriving on a bridge through a chain, once.
//
// Inserted at the top rather than appended: DOCKER-USER is where the engine expects
// operator rules and it is empty apart from a RETURN, but INPUT on a real host has a
// policy and a firewall of somebody else's underneath it, and a rule appended after an
// ACCEPT would never be reached.
func (d *Docker) jumpTo(ctx context.Context, from, bridge, chain string) error {
	ctx, cancel := context.WithTimeout(ctx, firewallTimeout)
	defer cancel()

	rule := []string{"-i", bridge, "-j", chain}
	if _, err := d.iptables(ctx, append([]string{"-C", from}, rule...)...); err == nil {
		return nil
	}
	if _, err := d.iptables(ctx, append([]string{"-I", from, "1"}, rule...)...); err != nil {
		return fmt.Errorf("send traffic from %s through %s at the top of %s: %w",
			bridge, chain, from, err)
	}
	return nil
}

// iptables runs one invocation against the filter table.
//
// -w is not optional. The engine takes the same lock every time it starts a container,
// and without it an invocation that lands at the wrong moment fails with "another app is
// currently holding the xtables lock" - which would turn a busy node into one where
// container creation intermittently reports a firewall it could not install.
func (d *Docker) iptables(ctx context.Context, arguments ...string) (string, error) {
	full := append([]string{"-w", firewallLockWait, "-t", "filter"}, arguments...)
	output, err := d.run(ctx, firewallTool, full...)
	return string(output), err
}

// sameRules compares what iptables reports with what was asked for.
//
// `iptables -S <chain>` prints the chain's own declaration followed by one -A line per
// rule, in order, in the same spelling the rule was given in. A difference of any kind -
// an extra rule somebody added by hand, a missing one, the wrong order - is answered by
// rebuilding the whole chain, so this only has to be right about "identical", never about
// which part differs.
func sameRules(chain, listing string, rules [][]string) bool {
	var found []string
	for _, line := range strings.Split(listing, "\n") {
		line = strings.TrimSpace(line)
		if strings.HasPrefix(line, "-A "+chain+" ") {
			found = append(found, strings.TrimPrefix(line, "-A "+chain+" "))
		}
	}
	if len(found) != len(rules) {
		return false
	}
	for index, rule := range rules {
		if found[index] != strings.Join(rule, " ") {
			return false
		}
	}
	return true
}
