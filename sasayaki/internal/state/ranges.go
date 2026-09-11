package state

import "sort"

// Byte ranges, the arithmetic a resumable upload is made of.
//
// No SQL in this file. Whether a set of chunks covers a file, where the first hole is and
// how two adjacent runs become one are questions with exact answers that have nothing to
// do with storage, and keeping them here means they can be tested exhaustively without a
// database and reused by the files package when it answers a ResumeUpload.
//
// Every range is half-open - Start included, EndExclusive not - which is how files.proto
// defines ByteRange and the only convention under which adjacency is expressible without
// an off-by-one: [0,4) and [4,8) touch, [0,4) and [5,8) do not.

// ByteRange is a run of bytes that is on disk.
type ByteRange struct {
	Start        int64
	EndExclusive int64
}

// Len is how many bytes the range covers.
func (r ByteRange) Len() int64 {
	if !r.Valid() {
		return 0
	}
	return r.EndExclusive - r.Start
}

// Valid rejects the ranges that cannot mean anything: a negative offset, and an empty or
// inverted run. An empty range is invalid rather than harmless because it would come from
// a chunk that carried no bytes, and recording it as received would let an upload complete
// with a hole in it.
func (r ByteRange) Valid() bool {
	return r.Start >= 0 && r.EndExclusive > r.Start
}

// touches reports whether the two runs overlap or sit end to end, and can therefore be
// replaced by one run spanning both.
//
// This is the definition of adjacency for the whole package - MergeRanges below folds runs
// by it, and the WHERE clause in upload_chunk.go's touchingRanges is the same comparison
// expressed in SQL. Written once here so the two cannot drift apart into a coalescer and a
// query that disagree about whether [0,4) and [4,8) are one range.
func (r ByteRange) touches(other ByteRange) bool {
	return r.Start <= other.EndExclusive && other.Start <= r.EndExclusive
}

// MergeRanges sorts and coalesces, so a set of chunks that arrived out of order and
// overlapping becomes the shortest description of the same bytes.
//
// Chunks are uploaded in parallel from a browser and retried after a dropped connection,
// so duplicates and overlaps are the normal case, not an error. The input is not modified.
func MergeRanges(ranges []ByteRange) []ByteRange {
	valid := make([]ByteRange, 0, len(ranges))
	for _, candidate := range ranges {
		if candidate.Valid() {
			valid = append(valid, candidate)
		}
	}
	if len(valid) == 0 {
		return nil
	}

	sort.Slice(valid, func(i, j int) bool {
		if valid[i].Start != valid[j].Start {
			return valid[i].Start < valid[j].Start
		}
		return valid[i].EndExclusive < valid[j].EndExclusive
	})

	merged := []ByteRange{valid[0]}
	for _, candidate := range valid[1:] {
		last := &merged[len(merged)-1]
		if !last.touches(candidate) {
			merged = append(merged, candidate)
			continue
		}
		if candidate.EndExclusive > last.EndExclusive {
			last.EndExclusive = candidate.EndExclusive
		}
	}
	return merged
}

// ReceivedBytes is how much of the file is on disk. It assumes coalesced input, which is
// what the store always holds and what MergeRanges produces; overlapping input would be
// counted twice, and that is the bug MergeRanges exists to prevent.
func ReceivedBytes(ranges []ByteRange) int64 {
	var total int64
	for _, r := range ranges {
		total += r.Len()
	}
	return total
}

// FirstGap is the offset a client should send next: the start of the first hole, or the
// total when there are none.
//
// A phone that lost signal in a lift comes back and asks what the node already has; the
// answer it can act on is one number, not a list it has to walk itself. Ranges are assumed
// sorted and coalesced.
func FirstGap(ranges []ByteRange, total int64) int64 {
	if total <= 0 {
		return 0
	}
	var cursor int64
	for _, r := range ranges {
		if r.Start > cursor {
			return cursor
		}
		if r.EndExclusive > cursor {
			cursor = r.EndExclusive
		}
	}
	if cursor > total {
		return total
	}
	return cursor
}

// Covers reports whether every byte of a file of this size is on disk.
//
// A zero-byte file is covered by no ranges at all, which is right: an empty upload has no
// chunks and still has to be completable, or saving an empty file from the inline editor
// would hang forever.
func Covers(ranges []ByteRange, total int64) bool {
	return FirstGap(ranges, total) >= total
}
