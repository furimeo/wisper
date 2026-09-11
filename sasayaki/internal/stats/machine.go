package stats

import (
	"bufio"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

// What the machine as a whole is doing, read from the kernel's own accounting.
//
// Files rather than a library: /proc is the interface Linux offers, it is four small reads,
// and a dependency that wraps them would still be reading the same four files while adding
// a version to keep current. The root is a field so a test can point it at a directory of
// fixtures - which is the only way this parsing is testable at all on a developer's machine,
// where there is no /proc to read.
type procfs struct {
	// root is /proc on a node.
	root string
}

// clockTicksPerSecond is the USER_HZ the kernel reports /proc/stat in.
//
// One hundred on every Linux architecture wisper runs on, and not something a process can
// read without cgo (sysconf(_SC_CLK_TCK)) - which is the one thing this binary will not
// spend, because cgo costs the single static file that makes installing a node a copy.
// Being wrong here would scale every CPU figure by a constant, which is why the number is
// named rather than multiplied in inline.
const clockTicksPerSecond = 100

// machineReading is one look at the whole machine.
type machineReading struct {
	// BootedAt is /proc/stat's btime: the epoch the counters below are counting from. A
	// reboot changes it, and a reboot is the one thing that resets them.
	BootedAt int64
	Cores    int

	// CPUNanos is cumulative busy nanoseconds across every core - idle and iowait excluded,
	// because a core waiting on a disk is a core that is available to something else.
	CPUNanos int64

	MemoryTotalBytes     int64
	MemoryAvailableBytes int64

	NetworkRxBytes int64
	NetworkTxBytes int64

	Load1m float64
}

// MemoryUsedBytes is what is really spoken for.
//
// Total minus available, not total minus free. MemAvailable is the kernel's own estimate of
// what a new allocation could get, and it counts the page cache that would be reclaimed to
// satisfy it. Reporting total minus free would show every healthy Linux machine at 97% for
// the entirely normal reason that it has been reading files.
func (m machineReading) MemoryUsedBytes() int64 {
	used := m.MemoryTotalBytes - m.MemoryAvailableBytes
	if used < 0 {
		return 0
	}
	return used
}

// read takes one look at the machine.
func (p procfs) read() (machineReading, error) {
	var m machineReading
	if err := p.readStat(&m); err != nil {
		return machineReading{}, err
	}
	if err := p.readMemory(&m); err != nil {
		return machineReading{}, err
	}
	// Neither of the two below is worth failing a pass over. A machine with no network
	// interfaces the sampler recognises still has CPU and memory worth charting, and a load
	// average is a nicety.
	p.readNetwork(&m)
	p.readLoad(&m)
	return m, nil
}

// readStat parses /proc/stat: the aggregate CPU line, the per-core lines that give the core
// count, and the boot time that says which run of the machine the counters belong to.
func (p procfs) readStat(m *machineReading) error {
	file, err := os.Open(filepath.Join(p.root, "stat"))
	if err != nil {
		return fmt.Errorf("stats: read the machine's CPU accounting: %w", err)
	}
	defer file.Close()

	scanner := bufio.NewScanner(file)
	for scanner.Scan() {
		fields := strings.Fields(scanner.Text())
		if len(fields) < 2 {
			continue
		}
		switch {
		case fields[0] == "cpu":
			m.CPUNanos = busyNanos(fields[1:])
		case fields[0] == "btime":
			m.BootedAt = parseInt(fields[1])
		case strings.HasPrefix(fields[0], "cpu"):
			m.Cores++
		}
	}
	if err := scanner.Err(); err != nil {
		return fmt.Errorf("stats: read the machine's CPU accounting: %w", err)
	}
	if m.Cores == 0 {
		return fmt.Errorf("stats: %s has no per-core lines, so this is not a Linux /proc", p.root)
	}
	return nil
}

// busyNanos totals the columns of a /proc/stat cpu line that represent work.
//
//	user nice system idle iowait irq softirq steal guest guest_nice
//
// Idle and iowait are excluded because neither is a core doing anything. guest and
// guest_nice are excluded because the kernel already counts them inside user and nice, and
// adding them again would report a virtualisation host at twice its real load.
func busyNanos(fields []string) int64 {
	const perTick = int64(1_000_000_000) / clockTicksPerSecond
	busy := int64(0)
	for index, field := range fields {
		switch index {
		case 3, 4: // idle, iowait
			continue
		case 8, 9: // guest, guest_nice: already inside user and nice
			continue
		}
		busy += parseInt(field)
	}
	return busy * perTick
}

// readMemory parses /proc/meminfo, whose figures are in kibibytes.
func (p procfs) readMemory(m *machineReading) error {
	file, err := os.Open(filepath.Join(p.root, "meminfo"))
	if err != nil {
		return fmt.Errorf("stats: read the machine's memory accounting: %w", err)
	}
	defer file.Close()

	var free int64
	scanner := bufio.NewScanner(file)
	for scanner.Scan() {
		fields := strings.Fields(scanner.Text())
		if len(fields) < 2 {
			continue
		}
		switch fields[0] {
		case "MemTotal:":
			m.MemoryTotalBytes = parseInt(fields[1]) * 1024
		case "MemAvailable:":
			m.MemoryAvailableBytes = parseInt(fields[1]) * 1024
		case "MemFree:":
			free = parseInt(fields[1]) * 1024
		}
	}
	if err := scanner.Err(); err != nil {
		return fmt.Errorf("stats: read the machine's memory accounting: %w", err)
	}
	if m.MemoryTotalBytes == 0 {
		return fmt.Errorf("stats: %s reported no MemTotal", filepath.Join(p.root, "meminfo"))
	}
	if m.MemoryAvailableBytes == 0 {
		// Kernels before 3.14 do not publish MemAvailable. Free is a pessimistic stand-in -
		// it ignores the cache that would be reclaimed - and being pessimistic about
		// headroom is the safe direction to be wrong in.
		m.MemoryAvailableBytes = free
	}
	return nil
}

// readNetwork totals /proc/net/dev over the interfaces that carry real traffic.
//
// The virtual ones are skipped, and that is not tidiness. Every byte a container sends
// appears on its veth, on the bridge it is attached to and on the physical interface it
// leaves by; totalling all three reports three times the traffic. Loopback is skipped for
// the same reason a loopback byte is not traffic.
func (p procfs) readNetwork(m *machineReading) {
	file, err := os.Open(filepath.Join(p.root, "net", "dev"))
	if err != nil {
		return
	}
	defer file.Close()

	scanner := bufio.NewScanner(file)
	for scanner.Scan() {
		name, counters, found := strings.Cut(scanner.Text(), ":")
		if !found {
			// The two header lines.
			continue
		}
		name = strings.TrimSpace(name)
		if isVirtualInterface(name) {
			continue
		}
		fields := strings.Fields(counters)
		if len(fields) < 9 {
			continue
		}
		m.NetworkRxBytes += parseInt(fields[0])
		m.NetworkTxBytes += parseInt(fields[8])
	}
}

// isVirtualInterface reports whether traffic on this interface is already counted somewhere
// else, or is not traffic at all.
func isVirtualInterface(name string) bool {
	if name == "lo" {
		return true
	}
	for _, prefix := range []string{"veth", "docker", "br-", "virbr", "cni", "flannel", "tap", "dummy"} {
		if strings.HasPrefix(name, prefix) {
			return true
		}
	}
	return false
}

// readLoad takes the one-minute figure from /proc/loadavg. It is the only fractional
// quantity this package sends, and the only one that is never summed across samples.
func (p procfs) readLoad(m *machineReading) {
	contents, err := os.ReadFile(filepath.Join(p.root, "loadavg"))
	if err != nil {
		return
	}
	fields := strings.Fields(string(contents))
	if len(fields) == 0 {
		return
	}
	if load, err := strconv.ParseFloat(fields[0], 64); err == nil {
		m.Load1m = load
	}
}

// parseInt folds an unparseable field into a zero, which is what a missing one means here:
// every caller is totalling, and refusing to read a whole file because one column of it was
// something unexpected would cost the whole machine's statistics.
func parseInt(text string) int64 {
	value, err := strconv.ParseInt(text, 10, 64)
	if err != nil {
		return 0
	}
	return value
}
