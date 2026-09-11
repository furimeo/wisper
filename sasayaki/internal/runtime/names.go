package runtime

import (
	"fmt"
	"strings"
)

// Naming, and the check that keeps an identifier from becoming a path.
//
// Every directory this package touches is built by joining an id from the spec onto the
// node's state root. That is the one place where something the panel sent reaches a
// filesystem call, so it is the one place where "../../etc" has to be impossible rather
// than unlikely. The rule is deliberately narrow: an identifier is letters, digits,
// dashes, underscores and dots, it is not empty, it does not start with a dot, and it is
// at most 64 characters. Everything the panel actually sends - a bigint id, a slug - fits
// inside that, so a value that does not fit is a bug or an attack and either way the
// answer is to refuse rather than to sanitise. Sanitising maps two different ids onto one
// directory, which is worse than a rejection nobody can act on.

const (
	// namePrefix marks a container as this platform's in `docker ps`. An operator
	// looking at a node should be able to tell wisper's containers from whatever else
	// the machine runs without consulting labels.
	namePrefix = "wisper"

	// maxIdentifier is the longest id or name accepted. Docker itself allows far more;
	// the limit is here so a container name stays readable and a directory name stays
	// inside every filesystem's own limit.
	maxIdentifier = 64
)

// checkIdentifier refuses anything that could escape a directory or confuse the engine.
func checkIdentifier(kind, value string) error {
	if value == "" {
		return fmt.Errorf("runtime: the %s is empty", kind)
	}
	if len(value) > maxIdentifier {
		return fmt.Errorf("runtime: the %s %q is longer than %d characters", kind, value, maxIdentifier)
	}
	if value == "." || value == ".." || strings.HasPrefix(value, ".") {
		return fmt.Errorf("runtime: the %s %q starts with a dot, which would name a "+
			"directory relative to something other than itself", kind, value)
	}
	for _, character := range value {
		switch {
		case character >= 'a' && character <= 'z',
			character >= 'A' && character <= 'Z',
			character >= '0' && character <= '9',
			character == '-', character == '_', character == '.':
		default:
			return fmt.Errorf("runtime: the %s %q contains %q, and only letters, digits, "+
				"'-', '_' and '.' may reach a filesystem path or a container name",
				kind, value, string(character))
		}
	}
	return nil
}

// containerName is what a workload's container is called.
//
// Both the customer's name and the workload id are in it. The name alone would collide
// the moment two projects both call something "api"; the id alone would make `docker ps`
// on a busy node a list of numbers. Docker requires the first character to be
// alphanumeric, which the constant prefix guarantees.
func containerName(workloadID, name string) (string, error) {
	if err := checkIdentifier("workload id", workloadID); err != nil {
		return "", err
	}
	readable := slug(name)
	if readable == "" {
		return namePrefix + "-" + workloadID, nil
	}
	return namePrefix + "-" + readable + "-" + workloadID, nil
}

// slug reduces a customer's service name to the characters a container name accepts.
//
// Unlike checkIdentifier this does rewrite rather than refuse, and that is safe for
// exactly one reason: the result is decoration. Uniqueness comes from the workload id
// appended after it, so two names that slug to the same thing still produce two
// different container names, and nothing is ever resolved by parsing this back out.
func slug(name string) string {
	var out strings.Builder
	previousDash := false
	for _, character := range strings.ToLower(name) {
		switch {
		case character >= 'a' && character <= 'z', character >= '0' && character <= '9':
			out.WriteRune(character)
			previousDash = false
		case out.Len() > 0 && !previousDash:
			out.WriteByte('-')
			previousDash = true
		}
		if out.Len() >= 32 {
			break
		}
	}
	return strings.Trim(out.String(), "-")
}
