package spec

import (
	"testing"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

func TestCronFromProto(t *testing.T) {
	entry := cronFromProto(&wisperpb.CronEntry{
		Id:             "cron-1",
		WorkloadId:     "1e9d",
		Schedule:       "0 3 * * *",
		Timezone:       "Asia/Ho_Chi_Minh",
		Command:        []string{"php", "artisan", "queue:prune"},
		TimeoutSeconds: 900,
		AllowOverlap:   false,
	})

	if entry.ID != "cron-1" || entry.WorkloadID != "1e9d" {
		t.Errorf("entry = %+v", entry)
	}
	if entry.Timeout != 15*time.Minute {
		t.Errorf("timeout = %s, want 15m", entry.Timeout)
	}
	if len(entry.Command) != 3 || entry.Command[0] != "php" {
		t.Errorf("command = %v, want argv", entry.Command)
	}
	if entry.AllowOverlap {
		t.Error("overlap is opt-in")
	}
}

// A customer who asked for 3am means their 3am, and a daily job that moves twice a year is
// a support ticket nobody enjoys.
func TestCronLocation(t *testing.T) {
	local, ok := CronEntry{Timezone: "Asia/Ho_Chi_Minh"}.Location()
	if !ok {
		t.Skip("no tzdata on this machine; the daemon links time/tzdata for exactly this")
	}
	if local.String() != "Asia/Ho_Chi_Minh" {
		t.Errorf("location = %s, want Asia/Ho_Chi_Minh", local)
	}
}

func TestCronLocationDefaultsToUTC(t *testing.T) {
	location, ok := CronEntry{}.Location()
	if !ok {
		t.Error("an empty timezone is UTC and is not a failure")
	}
	if location != time.UTC {
		t.Errorf("location = %s, want UTC", location)
	}
}

// A retired tzdata name must not stop the job running. The caller is told so it can say so
// in the status, and the run happens in UTC rather than not happening.
func TestUnknownTimezoneFallsBackAndSaysSo(t *testing.T) {
	location, ok := CronEntry{Timezone: "Mars/Olympus_Mons"}.Location()

	if ok {
		t.Error("an unknown zone should report that it was not resolved")
	}
	if location != time.UTC {
		t.Errorf("location = %s, want UTC as the fallback", location)
	}
}

func TestCronFromProtoAcceptsNil(t *testing.T) {
	if got := cronFromProto(nil); got.ID != "" || got.Timeout != 0 {
		t.Errorf("a nil entry gave %+v", got)
	}
}
