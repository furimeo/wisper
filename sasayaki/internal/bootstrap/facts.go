package bootstrap

import (
	"bufio"
	"context"
	"net"
	"os"
	"runtime"
	"strings"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// baseFacts is what the machine says about itself before any check has run.
//
// Everything here is descriptive - a name, a version string, an address - and nothing in
// it can fail an installation. The facts that carry a judgement (is the filesystem one
// with project quotas, is runsc there, is :80 free) are filled in by the check that made
// the judgement, so the report and the facts cannot disagree about what was found.
func baseFacts(ctx context.Context, m *machine) *wisperpb.MachineFacts {
	facts := &wisperpb.MachineFacts{
		Architecture:       runtime.GOARCH,
		CpuCores:           int32(runtime.NumCPU()),
		AdvertiseAddresses: advertiseAddresses(),
	}

	if hostname, err := os.Hostname(); err == nil {
		facts.Hostname = hostname
	}
	facts.OperatingSystem = operatingSystem(m)
	facts.KernelVersion = kernelRelease(ctx, m)
	facts.MemoryBytes = totalMemoryBytes(m)

	return facts
}

// operatingSystem is "ubuntu 24.04", from /etc/os-release.
//
// The file is a shell fragment by specification, but only the two fields wanted here are
// read and both are plain tokens, so it is parsed as key=value with the quotes stripped
// rather than by shelling out - which would mean running a file from /etc as root to find
// out what distribution this is.
func operatingSystem(m *machine) string {
	contents, err := m.readEtc("os-release")
	if err != nil {
		return runtime.GOOS
	}

	var id, versionID, prettyName string
	scanner := bufio.NewScanner(strings.NewReader(contents))
	for scanner.Scan() {
		key, value, found := strings.Cut(strings.TrimSpace(scanner.Text()), "=")
		if !found {
			continue
		}
		value = strings.Trim(value, `"'`)
		switch key {
		case "ID":
			id = value
		case "VERSION_ID":
			versionID = value
		case "PRETTY_NAME":
			prettyName = value
		}
	}

	switch {
	case id != "" && versionID != "":
		return id + " " + versionID
	case prettyName != "":
		return prettyName
	case id != "":
		return id
	default:
		return runtime.GOOS
	}
}

// kernelRelease is what `uname -r` prints, read from /proc rather than by running uname:
// one file read instead of a fork, and it works in the container the tests build.
func kernelRelease(ctx context.Context, m *machine) string {
	if release, err := m.readProc("sys", "kernel", "osrelease"); err == nil && release != "" {
		return release
	}
	// Not Linux, or a /proc that is not mounted. uname is the only remaining answer, and
	// its absence is itself reported by the kernel check.
	output, err := m.runCommand(ctx, "uname", "-r")
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(output))
}

// totalMemoryBytes reads MemTotal out of /proc/meminfo, in kibibytes as the kernel writes
// it.
func totalMemoryBytes(m *machine) int64 {
	contents, err := m.readProc("meminfo")
	if err != nil {
		return 0
	}
	scanner := bufio.NewScanner(strings.NewReader(contents))
	for scanner.Scan() {
		fields := strings.Fields(scanner.Text())
		if len(fields) >= 2 && fields[0] == "MemTotal:" {
			return parseInt64(fields[1]) * 1024
		}
	}
	return 0
}

// advertiseAddresses is where customers' DNS will point.
//
// Public addresses when the machine has any, and private ones only when it has none: a
// node reached over Tailscale or on a private network behind a load balancer is a
// legitimate deployment, but publishing 10.0.0.4 alongside a real address would have the
// panel show an address no customer can use. Loopback and link-local never appear.
func advertiseAddresses() []string {
	interfaces, err := net.Interfaces()
	if err != nil {
		return nil
	}

	var public, private []string
	for _, iface := range interfaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		addresses, err := iface.Addrs()
		if err != nil {
			continue
		}
		for _, address := range addresses {
			ip, _, err := net.ParseCIDR(address.String())
			if err != nil || ip == nil {
				continue
			}
			if !ip.IsGlobalUnicast() || ip.IsLinkLocalUnicast() {
				continue
			}
			if ip.IsPrivate() {
				private = append(private, ip.String())
				continue
			}
			public = append(public, ip.String())
		}
	}

	if len(public) > 0 {
		return public
	}
	return private
}

// parseInt64 is strconv.ParseInt with the error folded into a zero, for the /proc fields
// where a value that will not parse means the same as a field that was not there.
func parseInt64(text string) int64 {
	var value int64
	for _, digit := range text {
		if digit < '0' || digit > '9' {
			return 0
		}
		value = value*10 + int64(digit-'0')
	}
	return value
}
