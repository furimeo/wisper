package state

import (
	"reflect"
	"testing"
)

// No database in this file. Whether a set of chunks covers a file and where the first hole
// is are exact questions, and answering them wrongly is how a truncated upload is declared
// complete - so they are tested exhaustively and cheaply.

func TestByteRangeValidity(t *testing.T) {
	cases := []struct {
		name  string
		given ByteRange
		valid bool
		size  int64
	}{
		{"a normal run", ByteRange{0, 1024}, true, 1024},
		{"one byte", ByteRange{7, 8}, true, 1},
		{"empty", ByteRange{4, 4}, false, 0},
		{"inverted", ByteRange{8, 4}, false, 0},
		{"negative offset", ByteRange{-1, 4}, false, 0},
	}
	for _, testCase := range cases {
		t.Run(testCase.name, func(t *testing.T) {
			if got := testCase.given.Valid(); got != testCase.valid {
				t.Fatalf("Valid() = %v, want %v", got, testCase.valid)
			}
			if got := testCase.given.Len(); got != testCase.size {
				t.Fatalf("Len() = %d, want %d", got, testCase.size)
			}
		})
	}
}

func TestTouchesIsTheAdjacencyRuleTheSqlAlsoUses(t *testing.T) {
	// upload_chunk.go asks the same question of the table with
	// "end_exclusive >= ? AND start <= ?". If the two ever disagree, a chunk merges in memory
	// and not on disk, and the session accumulates a row per retry.
	cases := []struct {
		name        string
		left, right ByteRange
		want        bool
	}{
		{"end to end", ByteRange{0, 4}, ByteRange{4, 8}, true},
		{"end to end, reversed", ByteRange{4, 8}, ByteRange{0, 4}, true},
		{"overlapping", ByteRange{0, 6}, ByteRange{4, 10}, true},
		{"one inside the other", ByteRange{0, 100}, ByteRange{10, 20}, true},
		{"identical", ByteRange{0, 4}, ByteRange{0, 4}, true},
		{"one byte apart", ByteRange{0, 4}, ByteRange{5, 8}, false},
		{"far apart", ByteRange{0, 4}, ByteRange{1000, 2000}, false},
	}
	for _, testCase := range cases {
		t.Run(testCase.name, func(t *testing.T) {
			if got := testCase.left.touches(testCase.right); got != testCase.want {
				t.Fatalf("%v.touches(%v) = %v, want %v", testCase.left, testCase.right, got, testCase.want)
			}
		})
	}
}

func TestMergeRanges(t *testing.T) {
	cases := []struct {
		name  string
		given []ByteRange
		want  []ByteRange
	}{
		{"nothing", nil, nil},
		{"one", []ByteRange{{0, 4}}, []ByteRange{{0, 4}}},
		{
			// Chunks are uploaded in parallel, so out of order is the normal case.
			"out of order", []ByteRange{{8, 12}, {0, 4}, {4, 8}}, []ByteRange{{0, 12}},
		},
		{
			// Adjacent, not overlapping: [0,4) touches [4,8) because the ranges are half-open.
			"adjacent", []ByteRange{{0, 4}, {4, 8}}, []ByteRange{{0, 8}},
		},
		{"overlapping", []ByteRange{{0, 6}, {4, 10}}, []ByteRange{{0, 10}}},
		{"one inside another", []ByteRange{{0, 100}, {10, 20}}, []ByteRange{{0, 100}}},
		{"a retry of the same chunk", []ByteRange{{0, 4}, {0, 4}}, []ByteRange{{0, 4}}},
		{"a real hole", []ByteRange{{0, 4}, {8, 12}}, []ByteRange{{0, 4}, {8, 12}}},
		{"invalid runs are dropped", []ByteRange{{4, 4}, {0, 4}, {9, 2}}, []ByteRange{{0, 4}}},
		{"only invalid runs", []ByteRange{{4, 4}}, nil},
	}
	for _, testCase := range cases {
		t.Run(testCase.name, func(t *testing.T) {
			if got := MergeRanges(testCase.given); !reflect.DeepEqual(got, testCase.want) {
				t.Fatalf("MergeRanges(%v) = %v, want %v", testCase.given, got, testCase.want)
			}
		})
	}
}

func TestMergeRangesLeavesItsInputAlone(t *testing.T) {
	// The caller passes what it read from the table and then goes on using it. Sorting in
	// place would reorder rows underneath a loop that is still walking them.
	given := []ByteRange{{8, 12}, {0, 4}}
	MergeRanges(given)
	if given[0] != (ByteRange{8, 12}) || given[1] != (ByteRange{0, 4}) {
		t.Fatalf("MergeRanges reordered its input: %v", given)
	}
}

func TestReceivedBytesSumsCoalescedRuns(t *testing.T) {
	if got := ReceivedBytes(nil); got != 0 {
		t.Fatalf("ReceivedBytes(nil) = %d", got)
	}
	if got := ReceivedBytes([]ByteRange{{0, 4}, {8, 12}}); got != 8 {
		t.Fatalf("ReceivedBytes = %d, want 8", got)
	}
}

func TestFirstGapIsWhereTheClientCarriesOn(t *testing.T) {
	cases := []struct {
		name  string
		given []ByteRange
		total int64
		want  int64
	}{
		{"nothing received", nil, 1024, 0},
		{"a clean prefix", []ByteRange{{0, 400}}, 1024, 400},
		{"a hole after the prefix", []ByteRange{{0, 400}, {600, 1024}}, 1024, 400},
		{"a hole at the start", []ByteRange{{600, 1024}}, 1024, 0},
		{"complete", []ByteRange{{0, 1024}}, 1024, 1024},
		{"an empty file", nil, 0, 0},
	}
	for _, testCase := range cases {
		t.Run(testCase.name, func(t *testing.T) {
			if got := FirstGap(testCase.given, testCase.total); got != testCase.want {
				t.Fatalf("FirstGap(%v, %d) = %d, want %d", testCase.given, testCase.total, got, testCase.want)
			}
		})
	}
}

func TestCovers(t *testing.T) {
	cases := []struct {
		name  string
		given []ByteRange
		total int64
		want  bool
	}{
		{"complete", []ByteRange{{0, 1024}}, 1024, true},
		{"one byte short", []ByteRange{{0, 1023}}, 1024, false},
		{"a hole in the middle", []ByteRange{{0, 400}, {600, 1024}}, 1024, false},
		// Saving an empty file from the inline editor sends no chunks and must still complete.
		{"an empty file with no chunks", nil, 0, true},
		{"nothing received", nil, 1024, false},
	}
	for _, testCase := range cases {
		t.Run(testCase.name, func(t *testing.T) {
			if got := Covers(testCase.given, testCase.total); got != testCase.want {
				t.Fatalf("Covers(%v, %d) = %v, want %v", testCase.given, testCase.total, got, testCase.want)
			}
		})
	}
}
