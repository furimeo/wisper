package stats

import "testing"

// The thresholds, and the hysteresis around them. A node that gets these wrong either fills
// its disk or refuses to deploy anything, and both of those are the whole platform down for
// everybody on the machine.

func TestDiskPressureThresholds(t *testing.T) {
	const total = int64(1000)

	for _, testCase := range []struct {
		name string
		used int64
		want Pressure
	}{
		{name: "an empty disk", used: 0, want: PressureNone},
		{name: "half full", used: 500, want: PressureNone},
		{name: "just below the warning mark", used: 819, want: PressureNone},
		{name: "on the warning mark", used: 850, want: PressureWarning},
		{name: "between the marks", used: 900, want: PressureWarning},
		{name: "just below the critical mark", used: 919, want: PressureWarning},
		{name: "on the critical mark", used: 920, want: PressureCritical},
		{name: "completely full", used: 1000, want: PressureCritical},
	} {
		t.Run(testCase.name, func(t *testing.T) {
			gauge := newGauge(defaultDiskWarning, defaultDiskCritical)
			got, _ := gauge.observe(testCase.used, total)
			if got != testCase.want {
				t.Fatalf("%d of %d bytes used reads as %s, want %s", testCase.used, total, got, testCase.want)
			}
		})
	}
}

// Critical does not clear at the critical mark. It clears at the warning mark, so a sweep
// that frees a hundred megabytes on a node sitting at 92% does not put it straight back into
// the state it just left.
func TestCriticalClearsAtTheWarningMarkAndNotBefore(t *testing.T) {
	gauge := newGauge(defaultDiskWarning, defaultDiskCritical)

	if state, _ := gauge.observe(930, 1000); state != PressureCritical {
		t.Fatalf("93%% full reads as %s, want CRITICAL", state)
	}
	if state, moved := gauge.observe(910, 1000); state != PressureCritical || moved {
		t.Fatalf("falling back to 91%% gave %s (moved=%t), want a node still CRITICAL: clearing at "+
			"the mark it just crossed is how an alert fires every twelve seconds", state, moved)
	}
	if state, moved := gauge.observe(860, 1000); state != PressureCritical || moved {
		t.Fatalf("falling to 86%% gave %s (moved=%t), want CRITICAL until it is below the warning mark",
			state, moved)
	}
	if state, moved := gauge.observe(840, 1000); state != PressureWarning || !moved {
		t.Fatalf("falling to 84%% gave %s (moved=%t), want a move to WARNING", state, moved)
	}
	if state, moved := gauge.observe(810, 1000); state != PressureNone || !moved {
		t.Fatalf("falling to 81%% gave %s (moved=%t), want a move to NONE", state, moved)
	}
}

// The margin below the warning mark. A filesystem oscillating by a megabyte around 85% must
// not produce a transition on every pass.
func TestWarningIsStickyInsideTheMargin(t *testing.T) {
	gauge := newGauge(defaultDiskWarning, defaultDiskCritical)

	if state, _ := gauge.observe(855, 1000); state != PressureWarning {
		t.Fatalf("85.5%% full reads as %s, want WARNING", state)
	}
	for _, used := range []int64{845, 855, 830, 850, 825} {
		state, moved := gauge.observe(used, 1000)
		if state != PressureWarning || moved {
			t.Fatalf("%d of 1000 bytes used gave %s (moved=%t) inside the margin, want a state that "+
				"has not moved", used, state, moved)
		}
	}
	if state, moved := gauge.observe(819, 1000); state != PressureNone || !moved {
		t.Fatalf("falling clear of the margin gave %s (moved=%t), want a move to NONE", state, moved)
	}
}

// A disk nobody could measure is not a full disk. This is what a developer's machine reports,
// and a sampler that read it as pressure would refuse every deployment there.
func TestAnUnmeasuredDiskIsNotPressure(t *testing.T) {
	gauge := newGauge(defaultDiskWarning, defaultDiskCritical)
	if state, moved := gauge.observe(0, 0); state != PressureNone || moved {
		t.Fatalf("an unmeasured filesystem reads as %s (moved=%t), want NONE", state, moved)
	}
}

func TestThresholdsDefaultFieldByField(t *testing.T) {
	filled := Thresholds{DiskWarning: 0.70}.withDefaults()

	if filled.DiskWarning != 0.70 {
		t.Fatalf("DiskWarning is %v, want the value that was set, 0.70", filled.DiskWarning)
	}
	if filled.CPUAllocation != defaultCPUAllocation || filled.MemoryUsed != defaultMemoryUsed {
		t.Fatalf("moving one mark cleared the others: %+v", filled)
	}
	if filled.DiskCritical <= filled.DiskWarning {
		t.Fatalf("DiskCritical is %v and DiskWarning is %v: a critical mark at or below the warning "+
			"mark means the warning state can never be reached", filled.DiskCritical, filled.DiskWarning)
	}
}

// A caller that raises the warning mark above the default critical one must not end up with
// the two crossed over.
func TestACriticalMarkIsAlwaysAboveTheWarningMark(t *testing.T) {
	filled := Thresholds{DiskWarning: 0.95}.withDefaults()
	if filled.DiskCritical <= filled.DiskWarning {
		t.Fatalf("DiskCritical %v is not above DiskWarning %v", filled.DiskCritical, filled.DiskWarning)
	}
	if filled.DiskCritical > 1 {
		t.Fatalf("DiskCritical is %v, which no filesystem can reach", filled.DiskCritical)
	}
}
