package bootstrap

import (
	"context"
	"fmt"
	"io"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// checkPorts proves the embedded edge can take :80 and :443.
//
// Both are required and neither has a substitute. Caddy runs inside sasayaki and listens
// on them directly on the node, because customers' traffic never goes through the panel
// (design section 5.4); and ACME HTTP-01 cannot be answered anywhere except :80, so a
// node without it cannot obtain a certificate however healthy everything else is.
//
// The test is to bind them. Reading /proc/net/tcp would miss a socket held in another
// network namespace and would say nothing about a port this process is not permitted to
// bind - and a port that cannot be bound is not free, whatever the reason.
//
// Both listeners are held at once and released together. Taking them one at a time can
// report :443 as free because the :80 listener from a moment ago is still in TIME_WAIT
// and something else grabbed it in between.
func checkPorts(_ context.Context, m *machine, facts *wisperpb.MachineFacts) []*wisperpb.DoctorCheck {
	held := make([]io.Closer, 0, 2)
	defer func() {
		for _, listener := range held {
			listener.Close()
		}
	}()

	checks := make([]*wisperpb.DoctorCheck, 0, 2)
	for _, port := range []int{80, 443} {
		listener, err := m.listen("tcp", fmt.Sprintf(":%d", port))
		if listener != nil {
			held = append(held, listener)
		}
		free := err == nil
		switch port {
		case 80:
			facts.Port_80Free = free
		case 443:
			facts.Port_443Free = free
		}
		checks = append(checks, portCheck(port, free, err))
	}
	return checks
}

func portCheck(port int, free bool, err error) *wisperpb.DoctorCheck {
	id := fmt.Sprintf("network.port%d", port)
	title := fmt.Sprintf("Port %d available", port)

	if free {
		return passed(id, title, severityRequired, fmt.Sprintf(":%d can be bound", port))
	}

	remedy := "Stop whatever is listening (`ss -lptn 'sport = :" + fmt.Sprint(port) + "'`) " +
		"and disable it. sasayaki embeds Caddy and serves customer traffic directly."
	if port == 80 {
		remedy += " Port 80 is not optional even for a node that only serves HTTPS: " +
			"ACME HTTP-01 is answered there."
	}

	return check(id, title, severityRequired, outcomeFail,
		fmt.Sprintf(":%d could not be bound: %v", port, err), remedy)
}
