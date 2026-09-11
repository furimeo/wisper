package bootstrap

import (
	"context"
	"fmt"
	"strings"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// checkIsolation looks for gVisor.
//
// Advisory, and that is the whole point of it. A node without runsc still works: every
// workload runs under runc, and the panel marks the node as less isolated so an operator
// placing a customer on it knows what they are placing them on. What must never happen is
// the silent version - a node quietly downgrading its own isolation and nothing anywhere
// saying so (design section 7.2).
//
// Two conditions, and only the second one is enough. The binary being on $PATH means
// gVisor is installed; the engine having a runtime called "runsc" registered means it can
// actually be selected for a container. An installation that did the first and forgot the
// second is common - it is one edit of /etc/docker/daemon.json - and it looks like
// success from every angle except the one that matters.
func checkIsolation(ctx context.Context, m *machine, facts *wisperpb.MachineFacts) []*wisperpb.DoctorCheck {
	binary, lookErr := m.lookPath("runsc")
	report, _ := m.dockerReading(ctx)
	registered := report.hasRuntime("runsc")

	if binary != "" {
		facts.RunscVersion = runscVersion(ctx, m, binary)
	}
	facts.RunscAvailable = registered

	switch {
	case registered && binary != "":
		return []*wisperpb.DoctorCheck{passed("runtime.runsc", "gVisor (runsc)", severityAdvisory,
			fmt.Sprintf("%s registered with the engine%s", binary, versionSuffix(facts.GetRunscVersion())))}

	case registered:
		// Registered but not on this process's $PATH. The engine is the one that has to
		// find it, so this is fine, and saying so beats a warning nobody can act on.
		return []*wisperpb.DoctorCheck{passed("runtime.runsc", "gVisor (runsc)", severityAdvisory,
			"registered with the engine as a runtime, though not on this shell's PATH")}

	case binary != "":
		return []*wisperpb.DoctorCheck{check("runtime.runsc", "gVisor (runsc)", severityAdvisory,
			outcomeWarn,
			fmt.Sprintf("%s is installed%s but the engine has no runtime called runsc "+
				"(it offers %s), so every workload here will run under runc",
				binary, versionSuffix(facts.GetRunscVersion()), runtimeList(report)),
			`Add {"runtimes":{"runsc":{"path":"`+binary+`"}}} to /etc/docker/daemon.json and `+
				"restart Docker. Until then the panel will show this node as less isolated.")}

	default:
		detail := "runsc is not installed, so every workload here will run under runc " +
			"with kernel-level isolation only"
		if lookErr != nil {
			detail = fmt.Sprintf("%s (%v)", detail, lookErr)
		}
		return []*wisperpb.DoctorCheck{check("runtime.runsc", "gVisor (runsc)", severityAdvisory,
			outcomeWarn, detail,
			"Install gVisor from https://gvisor.dev/docs/user_guide/install/ and register "+
				"it with Docker. This node is usable without it; the panel will show it as "+
				"less isolated, which is the point of not failing here.")}
	}
}

// runscVersion runs `runsc --version` and keeps the first line, which is
// "runsc version release-20240401.0".
func runscVersion(ctx context.Context, m *machine, binary string) string {
	output, err := m.runCommand(ctx, binary, "--version")
	if err != nil {
		return ""
	}
	first, _, _ := strings.Cut(strings.TrimSpace(string(output)), "\n")
	return strings.TrimSpace(strings.TrimPrefix(first, "runsc version "))
}

func versionSuffix(version string) string {
	if version == "" {
		return ""
	}
	return " (" + version + ")"
}

func runtimeList(report dockerReport) string {
	if len(report.Runtimes) == 0 {
		return "none it would name"
	}
	return strings.Join(report.Runtimes, ", ")
}
