package bootstrap

import (
	"context"
	"fmt"
	"strconv"
	"strings"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// The engine floor.
//
// API 1.43 is Docker Engine 24.0. Below it the combination wisper depends on is not
// dependable: cgroup v2 resource enforcement, an OCI runtime registered by name so
// gVisor can be selected per container, and NanoCPUs meaning nano-CPUs. The client
// library refuses anything below API 1.40 outright, so this is a policy floor a little
// above a hard one.
const (
	minimumDockerAPI     = "1.43"
	minimumDockerRelease = "24.0"
)

// checkDocker proves the engine is there and callable, and that it is new enough.
//
// Two checks, because they fail for different reasons and want different remedies:
// docker.socket is "is anything answering", docker.version is "is what answered any use".
// A single check would report a permission problem on the socket as an old version.
func checkDocker(ctx context.Context, m *machine, facts *wisperpb.MachineFacts) []*wisperpb.DoctorCheck {
	report, err := m.dockerReading(ctx)

	if report.Version == "" {
		return []*wisperpb.DoctorCheck{
			socketUnreachable(report, err),
			check("docker.version", "Docker engine version", severityRequired, outcomeFail,
				"the engine did not answer, so its version is unknown",
				"Fix docker.socket above first."),
		}
	}

	facts.DockerVersion = report.Version
	facts.DockerApiVersion = report.APIVersion
	if report.CgroupVersion == "2" {
		facts.CgroupsV2 = true
	}

	socket := passed("docker.socket", "Docker socket", severityRequired,
		fmt.Sprintf("engine %s answering on %s", report.Version, report.Host))
	if err != nil {
		// /version worked and /info did not: the engine is there but it will not describe
		// itself, so runtime selection and the cgroup cross-check are blind.
		socket = check("docker.socket", "Docker socket", severityRequired, outcomeWarn,
			fmt.Sprintf("engine %s answered on %s but not every request succeeded: %v",
				report.Version, report.Host, err),
			"Check the daemon's logs. A socket that answers some calls and not others "+
				"usually means an overloaded engine or a restrictive socket policy.")
	}

	return []*wisperpb.DoctorCheck{socket, dockerVersionCheck(report)}
}

func socketUnreachable(report dockerReport, err error) *wisperpb.DoctorCheck {
	host := report.Host
	if host == "" {
		host = "the Docker socket"
	}
	return check("docker.socket", "Docker socket", severityRequired, outcomeFail,
		fmt.Sprintf("%s did not answer: %v", host, err),
		"Install Docker and start it (`systemctl enable --now docker`). sasayaki runs "+
			"every workload through the Engine API; without it the daemon has nothing to "+
			"drive.")
}

func dockerVersionCheck(report dockerReport) *wisperpb.DoctorCheck {
	if report.APIVersion == "" {
		return check("docker.version", "Docker engine version", severityRequired, outcomeWarn,
			fmt.Sprintf("engine %s did not state an API version, so it could not be compared "+
				"with the %s minimum", report.Version, minimumDockerAPI),
			"Check `docker version` reports an API version of "+minimumDockerAPI+" or newer.")
	}

	if compareDottedVersions(report.APIVersion, minimumDockerAPI) < 0 {
		return check("docker.version", "Docker engine version", severityRequired, outcomeFail,
			fmt.Sprintf("engine %s speaks API %s; wisper needs API %s, which is Docker "+
				"Engine %s", report.Version, report.APIVersion, minimumDockerAPI,
				minimumDockerRelease),
			"Upgrade Docker to "+minimumDockerRelease+" or newer.")
	}

	detail := fmt.Sprintf("API %s (minimum %s), storage driver %s",
		report.APIVersion, minimumDockerAPI, orUnknown(report.StorageDriver))
	if report.CgroupVersion != "" && report.CgroupVersion != "2" {
		return check("docker.version", "Docker engine version", severityRequired, outcomeFail,
			detail+fmt.Sprintf(", but the engine reports cgroup v%s", report.CgroupVersion),
			"The kernel may have cgroup v2 while the engine is still using v1. Set "+
				`"exec-opts": ["native.cgroupdriver=systemd"] in /etc/docker/daemon.json `+
				"and restart Docker.")
	}

	return passed("docker.version", "Docker engine version", severityRequired, detail)
}

// compareDottedVersions orders "1.47" against "1.43" numerically rather than
// lexically, which is the difference between 1.9 and 1.10 being in the right order and
// being in the wrong one. Missing components count as zero, so "24" and "24.0" are equal.
func compareDottedVersions(left, right string) int {
	leftParts := strings.Split(left, ".")
	rightParts := strings.Split(right, ".")
	for index := 0; index < len(leftParts) || index < len(rightParts); index++ {
		difference := versionComponent(leftParts, index) - versionComponent(rightParts, index)
		if difference != 0 {
			return difference
		}
	}
	return 0
}

func versionComponent(parts []string, index int) int {
	if index >= len(parts) {
		return 0
	}
	value, err := strconv.Atoi(strings.TrimSpace(parts[index]))
	if err != nil {
		return 0
	}
	return value
}

func orUnknown(value string) string {
	if value == "" {
		return "unknown"
	}
	return value
}
