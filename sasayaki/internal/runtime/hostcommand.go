package runtime

import (
	"bytes"
	"context"
	"fmt"
	"os/exec"
	"strings"
)

// Running a tool on the host.
//
// Two things on a node cannot be done through the Docker API: the packet filter that
// blocks a container's egress to private addresses, and the XFS project quota that bounds
// a volume. Both are kernel state that Docker does not model, and both are reached with
// the vendor's own command-line tool.
//
// Never a shell string. exec.CommandContext with an argument vector, always, so a value
// that reached this daemon from a customer cannot become a second command however it is
// spelled (AGENTS.md section 5). There is no /bin/sh anywhere in this package.
//
// commandRunner is a field on Docker rather than a direct call so that the firewall and
// the quota can be tested for the arguments they *would* have used. The alternative is a
// pair of features that only exist on a Linux box with iptables and xfsprogs installed,
// which means in practice they are never tested at all.
type commandRunner func(ctx context.Context, name string, args ...string) ([]byte, error)

// execRunner is the real one: run the tool, and put its stderr in the error, because
// iptables and xfs_quota both say what is wrong there and nowhere else.
func execRunner(ctx context.Context, name string, args ...string) ([]byte, error) {
	command := exec.CommandContext(ctx, name, args...)
	var stdout, stderr bytes.Buffer
	command.Stdout = &stdout
	command.Stderr = &stderr

	if err := command.Run(); err != nil {
		detail := strings.TrimSpace(stderr.String())
		if detail == "" {
			detail = strings.TrimSpace(stdout.String())
		}
		if detail == "" {
			return stdout.Bytes(), fmt.Errorf("%s %s: %w", name, strings.Join(args, " "), err)
		}
		return stdout.Bytes(), fmt.Errorf("%s %s: %w: %s", name, strings.Join(args, " "), err, detail)
	}
	return stdout.Bytes(), nil
}
