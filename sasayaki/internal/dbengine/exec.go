package dbengine

import (
	"context"
	"fmt"
	"strings"

	"github.com/furimeo/wisper/sasayaki/internal/runtime"
)

// Running one statement inside a server's container.
//
// The whole boundary between "what SQL to send" and "how it gets there" is this file, and it
// is deliberately small: the dialects build a command, this turns it into an exec and reads
// what came back. Nothing above it knows that the transport is `docker exec`, and nothing
// below it knows that the caller is a reconcile pass or a customer pressing a button.
//
// Two things happen here that would be easy to leave out and expensive to leave out:
//
//   - a non-zero exit is turned into an error carrying the server's own stderr, because "the
//     command failed" without the server's message is a support ticket, and
//   - every secret the command carried is removed from that message first. psql reports a
//     syntax error by quoting the line it failed on, and the line with the password on it is
//     the one most likely to be quoted.

// stderrLimit is how much of a failed command's error output is kept.
//
// A restore that fails can produce megabytes of complaints, and all of it ends up in a
// CommandResult the panel stores and shows. The first two kilobytes contain the first error,
// which is the one that caused the rest.
const stderrLimit = 2048

// runner is how this package executes SQL against one container.
func (e *Engines) runner(containerID string) run {
	return func(ctx context.Context, c command) (string, error) {
		result, err := e.commands.Run(ctx, containerID, runtime.RunOptions{
			Command: c.Argv,
			Env:     c.Env,
			Stdin:   []byte(c.SQL),
		})
		if err != nil {
			return "", fmt.Errorf("dbengine: %s: %s", c.Purpose, redact(err.Error(), c.Secrets))
		}
		if !result.Ok() {
			return "", fmt.Errorf("dbengine: %s: %s", c.Purpose,
				redact(describeFailure(result), c.Secrets))
		}
		return string(result.Stdout), nil
	}
}

// describeFailure is what to tell a person about a command that ran and did not work.
//
// The exit code alone is useless - psql says 3 for "a statement failed" whatever the statement
// was - so the server's own message is the substance and the code is context. A command killed
// by its deadline says so explicitly, because "exit 137" and "this took longer than it was
// given" are the same fact and only one of them is actionable.
func describeFailure(result runtime.RunResult) string {
	message := strings.TrimSpace(string(result.Stderr))
	if message == "" {
		message = strings.TrimSpace(string(result.Stdout))
	}
	if len(message) > stderrLimit {
		message = message[:stderrLimit] + " (truncated)"
	}

	if result.TimedOut {
		if message == "" {
			return "the server did not finish in the time it was given"
		}
		return "the server did not finish in the time it was given: " + message
	}
	if message == "" {
		return fmt.Sprintf("the client exited with status %d and said nothing", result.ExitCode)
	}
	return fmt.Sprintf("%s (exit status %d)", message, result.ExitCode)
}

// redact removes every secret a command carried from whatever it produced.
//
// Blank rather than shortened, so the length of what was removed is not a clue either. An
// empty secret is skipped: replacing "" would rewrite the whole string.
func redact(message string, secrets []string) string {
	for _, secret := range secrets {
		if secret == "" {
			continue
		}
		message = strings.ReplaceAll(message, secret, "[redacted]")
	}
	return message
}
