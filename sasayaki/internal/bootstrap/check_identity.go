package bootstrap

import (
	"context"
	"fmt"
	"strings"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// checkIdentity proves this machine can be told apart from a copy of itself.
//
// Required, because enrolment cannot proceed without a fingerprint: the panel uses it to
// notice that one credential has arrived from two addresses, and a node that cannot
// produce one is a node whose clone would be invisible. Cloning a running node is not a
// hypothetical - it is what happens when somebody snapshots a virtual machine to make a
// second one - and the failure mode without this check is two machines quietly dividing
// one customer's workload between them (design section 7.3).
func checkIdentity(_ context.Context, m *machine, _ *wisperpb.MachineFacts) []*wisperpb.DoctorCheck {
	identity := readMachineIdentity(m)

	if identity.Fingerprint == "" {
		return []*wisperpb.DoctorCheck{check("machine.identity", "Machine identity",
			severityRequired, outcomeFail,
			"nothing identifying could be read: "+describeMissing(identity),
			"Create one with `systemd-machine-id-setup`, or run this as root so the "+
				"hardware serials under /sys/class/dmi/id become readable. Enrolment "+
				"needs a fingerprint; without one the panel cannot detect a cloned node.")}
	}

	sources := strings.Join(identity.readable(), ", ")
	if !identity.hasHardwareSerial() {
		return []*wisperpb.DoctorCheck{check("machine.identity", "Machine identity",
			severityRequired, outcomeWarn,
			fmt.Sprintf("derived from %s only. No hardware serial was readable, so a clone "+
				"of this machine whose machine-id was regenerated would look like a "+
				"different node", sources),
			"Run this as root, or check /sys/class/dmi/id exists. Detection still works "+
				"for the usual case, where a clone carries the machine-id with it.")}
	}

	return []*wisperpb.DoctorCheck{passed("machine.identity", "Machine identity",
		severityRequired, "derived from "+sources)}
}

// describeMissing says why each source produced nothing, because "no identity" with no
// reasons attached is the least actionable sentence a check can print.
func describeMissing(identity machineIdentity) string {
	reasons := make([]string, 0, len(identity.Sources))
	for _, source := range identity.Sources {
		if source.err != nil {
			reasons = append(reasons, fmt.Sprintf("%s (%v)", source.name, source.err))
			continue
		}
		reasons = append(reasons, source.name+" (empty)")
	}
	return strings.Join(reasons, "; ")
}
