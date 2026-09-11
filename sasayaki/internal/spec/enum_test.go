package spec

import (
	"testing"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Every enum value the wire can carry, mapped exhaustively.
//
// One table per enum rather than a case here and there, because the failure this guards
// against is silent: a value added to the proto and not added to a switch falls into the
// default branch and becomes "unknown", and a workload the panel asked to be stopped is
// then simply never acted on. Listing the whole vocabulary makes the omission a red test
// instead of a support ticket.

func TestWorkloadKindMapping(t *testing.T) {
	cases := map[wisperpb.WorkloadKind]Kind{
		wisperpb.WorkloadKind_WORKLOAD_KIND_APP:         KindApp,
		wisperpb.WorkloadKind_WORKLOAD_KIND_SITE:        KindSite,
		wisperpb.WorkloadKind_WORKLOAD_KIND_UNSPECIFIED: KindUnknown,
		wisperpb.WorkloadKind(64):                       KindUnknown,
	}
	for wire, want := range cases {
		if got := kindFromProto(wire); got != want {
			t.Errorf("%v became %q, want %q", wire, got, want)
		}
	}
}

func TestDesiredStateMapping(t *testing.T) {
	cases := map[wisperpb.DesiredState]Desired{
		wisperpb.DesiredState_DESIRED_STATE_RUNNING:     DesiredRunning,
		wisperpb.DesiredState_DESIRED_STATE_STOPPED:     DesiredStopped,
		wisperpb.DesiredState_DESIRED_STATE_UNSPECIFIED: DesiredUnspecified,
		wisperpb.DesiredState(64):                       DesiredUnspecified,
	}
	for wire, want := range cases {
		if got := desiredFromProto(wire); got != want {
			t.Errorf("%v became %q, want %q", wire, got, want)
		}
	}
}

func TestMountKindMapping(t *testing.T) {
	cases := map[wisperpb.MountKind]MountKind{
		wisperpb.MountKind_MOUNT_KIND_VOLUME:       MountKindVolume,
		wisperpb.MountKind_MOUNT_KIND_SITE_RELEASE: MountKindSiteRelease,
		wisperpb.MountKind_MOUNT_KIND_TMPFS:        MountKindTmpfs,
		wisperpb.MountKind_MOUNT_KIND_UNSPECIFIED:  MountKindUnknown,
		wisperpb.MountKind(64):                     MountKindUnknown,
	}
	for wire, want := range cases {
		if got := mountKindFromProto(wire); got != want {
			t.Errorf("%v became %q, want %q", wire, got, want)
		}
	}
}

func TestRestartModeMapping(t *testing.T) {
	cases := map[wisperpb.RestartPolicyMode]RestartMode{
		wisperpb.RestartPolicyMode_RESTART_POLICY_MODE_NEVER:          RestartNever,
		wisperpb.RestartPolicyMode_RESTART_POLICY_MODE_ON_FAILURE:     RestartOnFailure,
		wisperpb.RestartPolicyMode_RESTART_POLICY_MODE_ALWAYS:         RestartAlways,
		wisperpb.RestartPolicyMode_RESTART_POLICY_MODE_UNLESS_STOPPED: RestartUnlessStopped,
		wisperpb.RestartPolicyMode_RESTART_POLICY_MODE_UNSPECIFIED:    RestartNever,
		wisperpb.RestartPolicyMode(64):                                RestartNever,
	}
	for wire, want := range cases {
		got := restartFromProto(&wisperpb.RestartPolicy{Mode: wire}).Mode
		if got != want {
			t.Errorf("%v became %q, want %q", wire, got, want)
		}
	}
}

func TestPortProtocolMapping(t *testing.T) {
	cases := map[wisperpb.PortProtocol]Protocol{
		wisperpb.PortProtocol_PORT_PROTOCOL_TCP:         ProtocolTCP,
		wisperpb.PortProtocol_PORT_PROTOCOL_UDP:         ProtocolUDP,
		wisperpb.PortProtocol_PORT_PROTOCOL_UNSPECIFIED: ProtocolTCP,
		wisperpb.PortProtocol(64):                       ProtocolTCP,
	}
	for wire, want := range cases {
		got := portFromProto(&wisperpb.PortBinding{Protocol: wire}).Protocol
		if got != want {
			t.Errorf("%v became %q, want %q", wire, got, want)
		}
	}
}

// A port published on the host is the exception, and the flag that says so has to survive:
// HTTP arrives through the embedded Caddy over the tenant network and needs no host port.
func TestPublishedPortIsRecognised(t *testing.T) {
	published := portFromProto(&wisperpb.PortBinding{
		ContainerPort: 25565,
		HostPort:      25565,
		HostIp:        "127.0.0.1",
	})
	if !published.IsPublished() || published.HostIP != "127.0.0.1" {
		t.Errorf("port = %+v, want a published binding on the loopback", published)
	}

	internal := portFromProto(&wisperpb.PortBinding{ContainerPort: 8080})
	if internal.IsPublished() {
		t.Error("host port 0 means do not publish")
	}
}
