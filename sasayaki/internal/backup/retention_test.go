package backup

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"slices"
	"testing"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Retention is the only part of this package that destroys a customer's data on purpose, so
// it is tested as arithmetic - a list in, two lists out - rather than through anything that
// could be mistaken for a real bucket.

// Midday, so that an offset of a few hours stays inside the same day and the daily test is
// about the bucket rule rather than about where midnight happens to fall.
var epoch = time.Date(2026, 9, 11, 12, 0, 0, 0, time.UTC)

// generationsAt builds one archive per offset, newest first in the offsets given.
func generationsAt(size int64, offsets ...time.Duration) []generation {
	out := make([]generation, 0, len(offsets))
	for _, offset := range offsets {
		taken := epoch.Add(offset)
		out = append(out, generation{
			Key:   fmt.Sprintf("nightly/vol1/%s-b%d.tar.gz", taken.Format(stampLayout), len(out)),
			Taken: taken,
			Size:  size,
		})
	}
	return out
}

func keys(generations []generation) []string {
	out := make([]string, 0, len(generations))
	for _, candidate := range generations {
		out = append(out, candidate.Key)
	}
	slices.Sort(out)
	return out
}

const day = 24 * time.Hour

// The single most consequential branch in the package. A rule that asks for nothing is a rule
// that arrives when the panel has not configured one, or when an older panel is sending a
// message without a field that was added later, and answering it with "delete everything" is
// not recoverable.
func TestARetentionRuleThatAsksForNothingDeletesNothing(t *testing.T) {
	generations := generationsAt(100, 0, -day, -2*day, -30*day)

	for name, rule := range map[string]*wisperpb.RetentionRule{
		"nil":        nil,
		"all zeroes": {},
	} {
		t.Run(name, func(t *testing.T) {
			keep, remove := retentionPlan(generations, rule, "")
			if len(remove) != 0 {
				t.Errorf("removed %v", keys(remove))
			}
			if len(keep) != len(generations) {
				t.Errorf("kept %d of %d", len(keep), len(generations))
			}
		})
	}
}

func TestKeepLastKeepsTheMostRecent(t *testing.T) {
	generations := generationsAt(100, 0, -day, -2*day, -3*day, -4*day, -5*day)

	keep, remove := retentionPlan(generations, &wisperpb.RetentionRule{KeepLast: 3}, "")
	if len(keep) != 3 {
		t.Fatalf("kept %v, want the three most recent", keys(keep))
	}
	want := keys(generations[:3])
	if got := keys(keep); !slices.Equal(got, want) {
		t.Errorf("kept %v, want %v", got, want)
	}
	if len(remove) != 3 {
		t.Errorf("removed %v, want the three oldest", keys(remove))
	}
}

// The naive reading of "keep 2 daily" is two backups. It is two days: the newest backup of
// each of the two most recent days, whatever else was taken on them.
func TestKeepDailyKeepsOnePerDayNotOnePerBackup(t *testing.T) {
	generations := generationsAt(100,
		0, -6*time.Hour, // today, twice
		-day, -day-6*time.Hour, // yesterday, twice
		-2*day, -2*day-6*time.Hour, // the day before, twice
	)

	keep, remove := retentionPlan(generations, &wisperpb.RetentionRule{KeepDaily: 2}, "")
	want := keys([]generation{generations[0], generations[2]})
	if got := keys(keep); !slices.Equal(got, want) {
		t.Errorf("kept %v, want %v", got, want)
	}
	if len(remove) != 4 {
		t.Errorf("removed %v, want four", keys(remove))
	}
}

func TestKeepMonthlyKeepsOnePerMonth(t *testing.T) {
	generations := generationsAt(100, 0, -31*day, -62*day, -93*day, -124*day)

	keep, _ := retentionPlan(generations, &wisperpb.RetentionRule{KeepMonthly: 2}, "")
	want := keys([]generation{generations[0], generations[1]})
	if got := keys(keep); !slices.Equal(got, want) {
		t.Errorf("kept %v, want %v", got, want)
	}
}

// A dormant project's only backup is eleven months old and is still its only backup.
func TestTheMostRecentGenerationIsNeverRemoved(t *testing.T) {
	generations := generationsAt(100, -300*day, -400*day)

	keep, remove := retentionPlan(generations, &wisperpb.RetentionRule{KeepDaily: 1}, "")
	if len(keep) == 0 {
		t.Fatal("a rule for recent backups deleted every backup there was")
	}
	if keep[0].Key != generations[0].Key {
		t.Errorf("kept %v, and the newest is %s", keys(keep), generations[0].Key)
	}
	if len(remove) != 1 {
		t.Errorf("removed %v, want the older one only", keys(remove))
	}
}

// The archive that was just written is spared even when it is not the newest by the clock -
// a resumed run uses the time it originally started at, so this is reachable.
func TestTheArchiveJustWrittenIsNeverRemoved(t *testing.T) {
	generations := generationsAt(100, 0, -day, -2*day)
	protected := generations[2].Key

	keep, remove := retentionPlan(generations, &wisperpb.RetentionRule{KeepLast: 1}, protected)
	if got := keys(keep); !slices.Equal(got, keys([]generation{generations[0], generations[2]})) {
		t.Errorf("kept %v, want the newest and the protected one", got)
	}
	for _, candidate := range remove {
		if candidate.Key == protected {
			t.Fatalf("the backup that was just taken was removed by its own retention rule")
		}
	}
}

func TestTheByteCeilingDropsTheOldestOfWhatWouldHaveBeenKept(t *testing.T) {
	generations := generationsAt(100, 0, -day, -2*day, -3*day)

	keep, remove := retentionPlan(generations, &wisperpb.RetentionRule{
		KeepLast:      4,
		MaxTotalBytes: 250,
	}, "")

	want := keys([]generation{generations[0], generations[1]})
	if got := keys(keep); !slices.Equal(got, want) {
		t.Errorf("kept %v, want %v - 250 bytes holds two of four hundred-byte archives", got, want)
	}
	if len(remove) != 2 {
		t.Errorf("removed %v, want two", keys(remove))
	}
}

// A ceiling smaller than one archive must not delete the archive that was taken to satisfy it.
func TestTheByteCeilingNeverTakesTheLastOne(t *testing.T) {
	generations := generationsAt(1000, 0, -day)

	keep, _ := retentionPlan(generations, &wisperpb.RetentionRule{KeepLast: 2, MaxTotalBytes: 10}, "")
	if len(keep) != 1 || keep[0].Key != generations[0].Key {
		t.Errorf("kept %v, want only the newest", keys(keep))
	}
}

// End to end through the local destination: the sidecar goes with the archive, and an object
// this node did not write is left alone.
func TestPruneRemovesAgedOutArchivesWithTheirChecksums(t *testing.T) {
	h := newHarness(t)
	h.Workloads.running["w1"] = true
	h.seedVolume(t, "w1", "vol1", map[string]string{"app.db": "data"})

	stranger := filepath.Join(localRoot(h.StateDir), "nightly", "vol1", "somebody-elses-file.txt")
	if err := os.MkdirAll(filepath.Dir(stranger), 0o755); err != nil {
		t.Fatalf("create the destination: %v", err)
	}
	if err := os.WriteFile(stranger, []byte("not ours"), 0o600); err != nil {
		t.Fatalf("write the stranger: %v", err)
	}

	for _, id := range []string{"b1", "b2", "b3", "b4"} {
		request := volumeBackup(localTarget("nightly"))
		request.BackupId = id
		request.Retention = &wisperpb.RetentionRule{KeepLast: 2}

		completed, err := h.Runner.RunBackup(context.Background(), request)
		if err != nil {
			t.Fatalf("run backup %s: %v", id, err)
		}
		if !completed.GetSuccess() {
			t.Fatalf("backup %s failed: %s", id, completed.GetDetail())
		}
	}

	objects := h.objectsAt(t)
	archives := archiveKeys(objects)
	if len(archives) != 2 {
		t.Fatalf("%d archives survived a keep_last of 2: %v", len(archives), archives)
	}
	for _, key := range archives {
		if _, found := objects[key+digestSuffix]; !found {
			t.Errorf("%s survived without its checksum", key)
		}
	}
	// Two archives, two sidecars, and the file this node did not write.
	if len(objects) != 5 {
		t.Errorf("the destination holds %v", objects)
	}
	if _, found := objects["nightly/vol1/somebody-elses-file.txt"]; !found {
		t.Error("retention deleted an object this node did not write")
	}
}
