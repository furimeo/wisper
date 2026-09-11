package stats

import (
	"path/filepath"
	"testing"
)

// Parsing the four files. Worth its own test because every one of these is a column index in
// a file whose format nobody controls, and being wrong in any of them is a chart that is
// plausible and false.

func TestReadingTheMachine(t *testing.T) {
	root := writeProcFixture(t, procFixture{
		// user 1000, nice 0, system 0, idle 100000, and the rest zero: 1000 ticks of work.
		cpuTicks: 1000, bootedAt: 1700000000, cores: 4,
		memTotalKiB: 8 << 20, memAvailableKiB: 6 << 20,
		rxBytes: 5_000_000, txBytes: 2_000_000, load: 1.25,
	})

	machine, err := procfs{root: root}.read()
	if err != nil {
		t.Fatalf("read the fixture: %v", err)
	}

	if machine.Cores != 4 {
		t.Fatalf("core count is %d, want the 4 per-core lines in /proc/stat", machine.Cores)
	}
	if machine.CPUNanos != 1000*10_000_000 {
		t.Fatalf("CPU nanoseconds is %d, want 1000 ticks at %d ns each; idle and iowait must not "+
			"be counted as work", machine.CPUNanos, 1_000_000_000/clockTicksPerSecond)
	}
	if machine.BootedAt != 1700000000 {
		t.Fatalf("boot time is %d, want the btime line: it is the epoch the CPU counter belongs to, "+
			"and a reboot is the one thing that resets it", machine.BootedAt)
	}
	if machine.MemoryTotalBytes != 8<<30 || machine.MemoryAvailableBytes != 6<<30 {
		t.Fatalf("memory is %d total and %d available, want 8 GiB and 6 GiB",
			machine.MemoryTotalBytes, machine.MemoryAvailableBytes)
	}
	if machine.MemoryUsedBytes() != 2<<30 {
		t.Fatalf("memory in use is %d, want total minus available: total minus free counts the page "+
			"cache and shows every healthy machine at 97%%", machine.MemoryUsedBytes())
	}
	if machine.Load1m != 1.25 {
		t.Fatalf("load average is %v, want 1.25", machine.Load1m)
	}
}

// Loopback, the bridge and the veths carry the same bytes the physical interface does.
// Totalling all of them reports a node at three times its real traffic.
func TestNetworkTotalsSkipTheInterfacesThatDoubleCount(t *testing.T) {
	root := writeProcFixture(t, procFixture{
		cpuTicks: 1, bootedAt: 1, cores: 1, memTotalKiB: 1024, memAvailableKiB: 512,
		rxBytes: 5_000_000, txBytes: 2_000_000,
	})

	machine, err := procfs{root: root}.read()
	if err != nil {
		t.Fatalf("read the fixture: %v", err)
	}
	if machine.NetworkRxBytes != 5_000_000 || machine.NetworkTxBytes != 2_000_000 {
		t.Fatalf("network totals are %d rx and %d tx, want only eth0's 5000000 and 2000000: lo and "+
			"docker0 carry the same bytes over again", machine.NetworkRxBytes, machine.NetworkTxBytes)
	}
}

func TestAMachineWithoutProcIsAnError(t *testing.T) {
	if _, err := (procfs{root: filepath.Join(t.TempDir(), "nothing-here")}).read(); err == nil {
		t.Fatal("reading a /proc that is not there succeeded")
	}
}

// A kernel too old to publish MemAvailable still has to produce a figure, and the figure has
// to be the pessimistic one: being wrong about headroom in the direction of caution costs a
// placement, and being wrong the other way costs a customer's container.
func TestMemFreeStandsInForAMissingMemAvailable(t *testing.T) {
	root := t.TempDir()
	rewriteProcFixture(t, root, procFixture{cpuTicks: 1, bootedAt: 1, cores: 1,
		memTotalKiB: 1024, memAvailableKiB: 512})
	write(t, filepath.Join(root, "meminfo"), "MemTotal:       1024 kB\nMemFree:         256 kB\n")

	machine, err := procfs{root: root}.read()
	if err != nil {
		t.Fatalf("read the fixture: %v", err)
	}
	if machine.MemoryAvailableBytes != 256*1024 {
		t.Fatalf("available memory is %d, want MemFree's 262144", machine.MemoryAvailableBytes)
	}
}

func TestVirtualInterfacesAreRecognised(t *testing.T) {
	for _, name := range []string{"lo", "docker0", "br-8f2c1a", "veth1a2b3c", "virbr0"} {
		if !isVirtualInterface(name) {
			t.Fatalf("%s is counted as a physical interface, so its traffic is added to the "+
				"interface it also travels over", name)
		}
	}
	for _, name := range []string{"eth0", "ens3", "enp5s0", "wlan0", "bond0"} {
		if isVirtualInterface(name) {
			t.Fatalf("%s is skipped, so a node whose only interface is called that reports no "+
				"traffic at all", name)
		}
	}
}
