package backup

import (
	"fmt"
	"sort"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Which generations to keep, as arithmetic and nothing else.
//
// Pure on purpose: a list of generations and a rule in, two lists out, no destination and no
// deletion anywhere near it. Retention is the one part of this package that destroys a
// customer's data on purpose, so it is the part that has to be provable by reading it, and
// testable without anything that could be mistaken for a real bucket.
//
// The grandfather-father-son shape is the standard one and it is worth spelling out, because
// the naive reading of "keep 7 daily, 4 weekly, 12 monthly" is that it means twenty-three
// backups. It does not. Walking newest to oldest, each generation is offered to each bucket
// rule in turn: the first backup of a day it sees satisfies that day, the first of a week
// satisfies that week, and one backup can satisfy all three at once. Sunday night's backup is
// usually the day, the week and, at the end of the month, the month.
//
// # The rule that outranks all of them
//
// A rule that asks for nothing deletes nothing. An empty RetentionRule arrives when the panel
// has not configured one, when a field was added and an older panel is sending the message
// without it, and when something upstream went wrong - and in every one of those cases
// "delete everything, no generation was requested" would be catastrophic and irreversible.
// Silence means keep.

// retentionPlan splits generations into what stays and what goes.
//
// protected is the key just written. It is kept whatever the rule says: a backup that deleted
// itself the moment it finished would be an interesting way to satisfy keep_last of zero.
func retentionPlan(generations []generation, rule *wisperpb.RetentionRule, protected string) (keep, remove []generation) {
	ordered := append([]generation(nil), generations...)
	sort.Slice(ordered, func(a, b int) bool {
		if ordered[a].Taken.Equal(ordered[b].Taken) {
			return ordered[a].Key > ordered[b].Key
		}
		return ordered[a].Taken.After(ordered[b].Taken)
	})

	if !asksForSomething(rule) {
		return ordered, nil
	}

	kept := make(map[string]bool, len(ordered))
	if protected != "" {
		kept[protected] = true
	}
	if len(ordered) > 0 {
		// The most recent generation is never removed by a count rule. A dormant project whose
		// only backup is eleven months old still has a backup.
		kept[ordered[0].Key] = true
	}

	for index, candidate := range ordered {
		if index < int(rule.GetKeepLast()) {
			kept[candidate.Key] = true
		}
	}
	fill(ordered, kept, int(rule.GetKeepDaily()), func(g generation) string {
		return g.Taken.Format("2006-01-02")
	})
	fill(ordered, kept, int(rule.GetKeepWeekly()), func(g generation) string {
		year, week := g.Taken.ISOWeek()
		return fmt.Sprintf("%04d-W%02d", year, week)
	})
	fill(ordered, kept, int(rule.GetKeepMonthly()), func(g generation) string {
		return g.Taken.Format("2006-01")
	})

	applyCeiling(ordered, kept, rule.GetMaxTotalBytes(), protected)

	for _, candidate := range ordered {
		if kept[candidate.Key] {
			keep = append(keep, candidate)
			continue
		}
		remove = append(remove, candidate)
	}
	return keep, remove
}

// asksForSomething reports whether the rule expresses any limit at all.
//
// A nil rule and an all-zero one are the same thing and both mean keep everything. This is the
// single most consequential branch in the package: getting it the other way round would empty
// a customer's bucket the first time a panel sent a message without the field set.
func asksForSomething(rule *wisperpb.RetentionRule) bool {
	return rule.GetKeepLast() > 0 ||
		rule.GetKeepDaily() > 0 ||
		rule.GetKeepWeekly() > 0 ||
		rule.GetKeepMonthly() > 0 ||
		rule.GetMaxTotalBytes() > 0
}

// fill keeps the newest generation of each of the most recent `limit` buckets.
func fill(ordered []generation, kept map[string]bool, limit int, bucketOf func(generation) string) {
	if limit <= 0 {
		return
	}
	seen := make(map[string]bool, limit)
	for _, candidate := range ordered {
		bucket := bucketOf(candidate)
		if seen[bucket] {
			continue
		}
		if len(seen) >= limit {
			return
		}
		seen[bucket] = true
		kept[candidate.Key] = true
	}
}

// applyCeiling drops the oldest kept generations until the total fits.
//
// The ceiling wins over the count rules, because it is the one that stops a customer's bucket
// from growing without limit - but it never takes the last one and never takes the one just
// written. A ceiling smaller than a single backup would otherwise delete the backup that was
// taken to satisfy it and report success.
func applyCeiling(ordered []generation, kept map[string]bool, ceiling int64, protected string) {
	if ceiling <= 0 {
		return
	}

	var total int64
	for _, candidate := range ordered {
		if kept[candidate.Key] {
			total += candidate.Size
		}
	}
	if total <= ceiling {
		return
	}

	// Oldest first, which is the reverse of the order everything else here walks in.
	for index := len(ordered) - 1; index >= 1 && total > ceiling; index-- {
		candidate := ordered[index]
		if !kept[candidate.Key] || candidate.Key == protected {
			continue
		}
		delete(kept, candidate.Key)
		total -= candidate.Size
	}
}
