package build

import (
	"bufio"
	"errors"
	"fmt"
	"os"
	"path"
	"path/filepath"
	"strings"
)

// .dockerignore, because a build context is uploaded before anything is built.
//
// Without it the whole checkout goes over the socket: the .git directory a shallow clone
// still leaves behind, the node_modules a cached workspace is keeping, the generator's
// incremental output. On a repository of any size that is the slowest part of the build,
// and it is spent sending the engine files the Dockerfile will not look at.
//
// The syntax is Docker's: one pattern per line, `#` starts a comment, a leading `!` makes
// the line an exception that puts a file back, and the last line that matches wins. `*`
// matches within one path segment and `**` matches across any number of them. Patterns are
// matched against the path relative to the context root, always with forward slashes.

// ignoreList is a parsed .dockerignore. The nil value excludes nothing, which is what a
// context with no .dockerignore means.
type ignoreList struct {
	rules []ignoreRule
}

type ignoreRule struct {
	// segments is the pattern split on "/", so each part can be matched against one path
	// segment and "**" can be handled between them.
	segments []string
	// exception is a line that began with "!": it un-excludes what an earlier line
	// excluded.
	exception bool
}

// readIgnoreList loads the .dockerignore at the root of a build context.
//
// A missing file is not an error and is the normal case. A file that cannot be read is:
// building anyway would send everything, and a customer who wrote a .dockerignore and got
// a four-minute upload has been ignored rather than told.
func readIgnoreList(contextRoot string) (*ignoreList, error) {
	file, err := os.Open(filepath.Join(contextRoot, ".dockerignore"))
	if errors.Is(err, os.ErrNotExist) {
		return &ignoreList{}, nil
	}
	if err != nil {
		return nil, fmt.Errorf("build: read the .dockerignore: %w", err)
	}
	defer file.Close()

	list := &ignoreList{}
	scanner := bufio.NewScanner(file)
	for scanner.Scan() {
		if rule, ok := parseIgnoreRule(scanner.Text()); ok {
			list.rules = append(list.rules, rule)
		}
	}
	if err := scanner.Err(); err != nil {
		return nil, fmt.Errorf("build: read the .dockerignore: %w", err)
	}
	return list, nil
}

func parseIgnoreRule(line string) (ignoreRule, bool) {
	trimmed := strings.TrimSpace(line)
	if trimmed == "" || strings.HasPrefix(trimmed, "#") {
		return ignoreRule{}, false
	}

	rule := ignoreRule{}
	if strings.HasPrefix(trimmed, "!") {
		rule.exception = true
		trimmed = strings.TrimSpace(trimmed[1:])
		if trimmed == "" {
			return ignoreRule{}, false
		}
	}

	// Leading and trailing separators are noise: "/build/" and "build" name the same thing
	// in a context, because every pattern is anchored at the root already.
	cleaned := path.Clean(strings.Trim(strings.ReplaceAll(trimmed, `\`, "/"), "/"))
	if cleaned == "." || cleaned == "" {
		return ignoreRule{}, false
	}
	rule.segments = strings.Split(cleaned, "/")
	return rule, true
}

// excludes reports whether a path relative to the context root should be left out.
//
// A pattern excludes what it names and everything under it, and the last rule that matches
// decides. The one exception to that is a directory holding an exception - `assets` with
// `!assets/logo.svg` after it - which is reported as wanted so the walk goes in and finds
// the file; the files beside the exception are still excluded on their own account.
func (l *ignoreList) excludes(name string, isDirectory bool) bool {
	if l == nil || len(l.rules) == 0 {
		return false
	}
	name = strings.Trim(filepath.ToSlash(name), "/")
	if name == "" || name == "." {
		return false
	}

	segments := strings.Split(name, "/")
	excluded := false
	for _, rule := range l.rules {
		if matchesAncestor(rule.segments, segments) {
			excluded = !rule.exception
		}
	}
	if excluded && isDirectory && l.hasExceptionUnder(segments) {
		// Something inside is wanted, so the walk has to go in. The directory's own entry
		// is cheap; it is the files under it that cost.
		return false
	}
	return excluded
}

// hasExceptionUnder reports whether any `!` rule could match something inside this
// directory. Conservative on purpose: descending into a directory nothing wants costs a
// walk, while skipping one that held an exception loses a file the customer asked for.
func (l *ignoreList) hasExceptionUnder(segments []string) bool {
	for _, rule := range l.rules {
		if !rule.exception {
			continue
		}
		if containsDoubleStar(rule.segments) {
			// It could match at any depth, and working out whether it really does is more
			// expensive than walking the directory.
			return true
		}
		if len(rule.segments) <= len(segments) {
			// It names this directory or something above it, so nothing deeper.
			continue
		}
		if matchSegments(rule.segments[:len(segments)], segments) {
			return true
		}
	}
	return false
}

func containsDoubleStar(segments []string) bool {
	for _, segment := range segments {
		if segment == "**" {
			return true
		}
	}
	return false
}

// matchesAncestor reports whether a pattern names a path or any directory above it.
//
// `node_modules` has to exclude `node_modules/left-pad/index.js` and not only the directory
// entry itself, because the walk descends into an excluded directory whenever an exception
// could live inside it - and everything under it must then still be left out. Docker reads
// its own patterns the same way.
func matchesAncestor(pattern, name []string) bool {
	for depth := 1; depth <= len(name); depth++ {
		if matchSegments(pattern, name[:depth]) {
			return true
		}
	}
	return false
}

// matchSegments matches a split pattern against a split path, with "**" spanning any
// number of segments.
//
// Written as a small recursion rather than turned into a regular expression: the pattern
// comes from a customer's file, and a translation step is one more place for a pattern to
// mean something other than what Docker would make of it.
func matchSegments(pattern, name []string) bool {
	if len(pattern) == 0 {
		return len(name) == 0
	}
	if pattern[0] == "**" {
		// Zero segments, or one and try again.
		for skip := 0; skip <= len(name); skip++ {
			if matchSegments(pattern[1:], name[skip:]) {
				return true
			}
		}
		return false
	}
	if len(name) == 0 {
		return false
	}
	matched, err := path.Match(pattern[0], name[0])
	if err != nil || !matched {
		return false
	}
	return matchSegments(pattern[1:], name[1:])
}
