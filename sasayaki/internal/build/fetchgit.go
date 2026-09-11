package build

import (
	"bytes"
	"context"
	"fmt"
	"net/url"
	"strconv"
	"strings"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// Getting a repository onto the node, at exactly the commit that triggered the build.
//
// # Why this runs in a container
//
// The alternative is `exec.Command("git", ...)` on the host, which is what most platforms
// do and what this one deliberately does not. sasayaki is one static binary whose host
// dependencies are a kernel and a Docker socket; requiring git as a third would make a
// node that passes `doctor` still unable to deploy. And a clone runs a stranger's data
// through a parser, resolves submodule URLs the repository chose, and can be pointed at a
// path on the host - as root, if it ran on the host. In a container it is confined by the
// same runtime, capability set and ceilings as every other build stage.
//
// # Why the token is in the environment
//
// It may not be on argv: `ps` is readable by every user on the machine, and a URL with a
// token in it is also what ends up in a crash report. It may not be on disk: the panel
// holds it encrypted and sends it for one build, and a `.git-credentials` file outlives
// the build that needed it. So it is an environment variable read by a credential helper
// whose text is a constant, and the container is removed when the stage ends.

const (
	// gitTokenVariable and gitUserVariable are read by the credential helper below.
	gitTokenVariable = "WISPER_GIT_TOKEN"
	gitUserVariable  = "WISPER_GIT_USER"

	// gitUser is the username half of HTTP basic auth. Every forge that issues deploy
	// tokens ignores it and reads the password, but one has to be sent.
	gitUser = "x-access-token"

	// credentialHelper answers git's request for a password from the environment.
	//
	// A fixed string. Nothing the panel sends is interpolated into it, so there is no way
	// for a repository URL or a branch name to become part of a command - the token
	// arrives separately, in the environment, and this snippet only reads it.
	credentialHelper = `!f() { test "$1" = get && ` +
		`printf 'username=%s\npassword=%s\n' "$` + gitUserVariable + `" "$` + gitTokenVariable + `"; }; f`

	// defaultDepth is the shallow clone every build gets unless the panel asks for more.
	// A build does not need history, and a depth of one turns a large repository's clone
	// from minutes into seconds.
	defaultDepth = 1
)

// source is what the fetch stage found out, which the panel shows next to the deployment.
type source struct {
	// Commit is what was actually built, which for a branch build is not knowable in
	// advance. The panel uses it to skip a rebuild of the same commit.
	Commit string
	// Message is the commit's subject line, so a deployment list reads like a history
	// instead of a column of hashes.
	Message string
}

// fetchGit puts the repository in the workspace's checkout and reports what it got.
func (b *Builder) fetchGit(ctx context.Context, space workspace, request *wisperpb.StartBuild,
	git *wisperpb.GitSource, log *buildLog) (source, error) {

	repository, err := checkRepositoryURL(git.GetRepositoryUrl())
	if err != nil {
		return source{}, err
	}
	if err := b.ensureImage(ctx, gitImage, log); err != nil {
		return source{}, err
	}

	depth := int(git.GetDepth())
	if depth <= 0 {
		depth = defaultDepth
	}
	environment := []string{
		// Without it git waits on a terminal that is not there when a private repository
		// asks for credentials, and a build that should have failed in a second hangs
		// until its timeout instead.
		"GIT_TERMINAL_PROMPT=0",
		// The container has no system git configuration worth reading and no home
		// directory to speak of; saying so keeps the behaviour identical whatever base
		// image the pinned tag resolves to.
		"GIT_CONFIG_NOSYSTEM=1",
		"HOME=" + workMount,
	}
	if token := git.GetAccessToken(); token != "" {
		environment = append(environment,
			gitUserVariable+"="+gitUser,
			gitTokenVariable+"="+token)
	}

	incremental := space.Reused != "" && hasRepository(space.Checkout)
	if incremental {
		log.say("reusing the workspace from build %s", space.Reused)
	} else if err := emptyCheckout(space.Checkout); err != nil {
		return source{}, err
	}

	if err := b.cloneOrFetch(ctx, space, request, git, repository, depth, environment, incremental, log); err != nil {
		return source{}, err
	}
	if git.GetSubmodules() {
		log.say("updating submodules")
		if err := b.git(ctx, space, request, environment, log,
			"submodule", "update", "--init", "--recursive", "--depth", strconv.Itoa(depth)); err != nil {
			return source{}, err
		}
	}
	return b.describeHead(ctx, space, request, environment, log)
}

// cloneOrFetch brings the checkout to the requested commit, either by cloning it fresh or
// by advancing the repository a previous build left behind.
func (b *Builder) cloneOrFetch(ctx context.Context, space workspace, request *wisperpb.StartBuild,
	git *wisperpb.GitSource, repository string, depth int, environment []string,
	incremental bool, log *buildLog) error {

	commit := strings.TrimSpace(git.GetCommit())
	ref := strings.TrimSpace(git.GetRef())

	if incremental {
		// The URL is a positional argument rather than a configured remote, so a
		// repository that was moved or re-permissioned between two builds needs no
		// reconciliation step. What is fetched is the commit when the panel pinned one and
		// the branch otherwise; either way FETCH_HEAD is what gets checked out.
		wanted := commit
		if wanted == "" {
			wanted = ref
		}
		if wanted == "" {
			wanted = "HEAD"
		}
		log.say("fetching %s from %s", wanted, repository)
		if err := b.git(ctx, space, request, environment, log,
			"fetch", "--depth", strconv.Itoa(depth), "--no-tags", "--force", repository, wanted); err != nil {
			return err
		}
		// --force discards whatever the previous build left modified in tracked files.
		// Untracked ones - node_modules, the caches this reuse exists for - are left
		// exactly where they are, which is the whole point.
		return b.git(ctx, space, request, environment, log,
			"checkout", "--detach", "--force", "FETCH_HEAD")
	}

	log.say("cloning %s", repository)
	arguments := []string{"clone", "--depth", strconv.Itoa(depth), "--no-tags", "--single-branch"}
	if ref != "" {
		arguments = append(arguments, "--branch", ref)
	}
	arguments = append(arguments, repository, ".")
	if err := b.git(ctx, space, request, environment, log, arguments...); err != nil {
		return err
	}

	if commit == "" {
		return nil
	}
	// A shallow clone of a branch holds its tip and nothing else, so a commit the panel
	// pinned from a webhook that has since been overtaken is not there yet. Fetching it by
	// name first is what makes "build the commit that triggered this" true rather than
	// "build whatever the branch moved to while the job sat in the queue".
	log.say("checking out %s", commit)
	if err := b.git(ctx, space, request, environment, log,
		"fetch", "--depth", strconv.Itoa(depth), "--no-tags", "origin", commit); err != nil {
		// Forges differ on whether a commit may be fetched by hash. When one refuses, the
		// checkout below still succeeds for the common case where the pinned commit is the
		// branch tip the clone already has - so this is a note, not a failure.
		log.say("the remote would not serve %s directly; using what the clone brought", commit)
	}
	return b.git(ctx, space, request, environment, log, "checkout", "--detach", "--force", commit)
}

// describeHead reads back what was actually checked out.
func (b *Builder) describeHead(ctx context.Context, space workspace, request *wisperpb.StartBuild,
	environment []string, log *buildLog) (source, error) {

	var captured bytes.Buffer
	outcome, err := b.runContainer(ctx, containerRun{
		Name:       stageContainerName(request.GetBuildId(), "describe"),
		BuildID:    request.GetBuildId(),
		Image:      gitImage,
		Entrypoint: []string{"git"},
		Command:    append(gitPrefix(space), "log", "-1", "--format=%H%n%s"),
		WorkingDir: containerPath(space.Root, space.Checkout),
		Env:        environment,
		HostPath:   space.Root,
		Limits:     request.GetLimits(),
		Stdout:     &captured,
		Stderr:     log.stderr(),
	})
	if err != nil {
		return source{}, err
	}
	if !outcome.Ok() {
		return source{}, fmt.Errorf("build: git could not describe the checkout (exit %d)", outcome.ExitCode)
	}

	commit, message, _ := strings.Cut(strings.TrimRight(captured.String(), "\n"), "\n")
	found := source{Commit: strings.TrimSpace(commit), Message: strings.TrimSpace(message)}
	if found.Commit == "" {
		return source{}, fmt.Errorf("build: git described the checkout as having no commit")
	}
	log.say("building %s - %s", found.Commit, found.Message)
	return found, nil
}

// git runs one git command in the checkout and fails the stage when it does.
func (b *Builder) git(ctx context.Context, space workspace, request *wisperpb.StartBuild,
	environment []string, log *buildLog, arguments ...string) error {

	outcome, err := b.runContainer(ctx, containerRun{
		Name:       stageContainerName(request.GetBuildId(), "git-"+arguments[0]),
		BuildID:    request.GetBuildId(),
		Image:      gitImage,
		Entrypoint: []string{"git"},
		Command:    append(gitPrefix(space), arguments...),
		WorkingDir: containerPath(space.Root, space.Checkout),
		Env:        environment,
		HostPath:   space.Root,
		Limits:     request.GetLimits(),
		Stdout:     log.stdout(),
		Stderr:     log.stderr(),
	})
	if err != nil {
		return err
	}
	if outcome.TimedOut {
		return fmt.Errorf("build: git %s ran past the build's deadline", arguments[0])
	}
	if !outcome.Ok() {
		return fmt.Errorf("build: git %s failed with exit code %d", arguments[0], outcome.ExitCode)
	}
	return nil
}

// gitPrefix is the configuration every invocation carries.
//
// -C rather than relying on the working directory, because `clone` runs before the
// checkout exists as a repository. safe.directory because git refuses to operate on a tree
// whose owner differs from the process's, which is what a daemon running with
// user-namespace remap produces. And the credential helper, whose text is a constant.
func gitPrefix(space workspace) []string {
	return []string{
		"-C", containerPath(space.Root, space.Checkout),
		"-c", "safe.directory=*",
		"-c", "credential.helper=",
		"-c", "credential.helper=" + credentialHelper,
		"-c", "advice.detachedHead=false",
	}
}

// checkRepositoryURL refuses anything that is not an HTTPS remote.
//
// Not a style preference. An SSH remote needs a private key on the node, and this node
// deliberately holds no credential it was not handed for one job (build.proto). A file://
// or a bare path would clone from the host's own filesystem through the bind mount, and a
// scheme nobody checked is how a build reads /etc.
func checkRepositoryURL(raw string) (string, error) {
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" {
		return "", fmt.Errorf("build: the git source names no repository")
	}
	parsed, err := url.Parse(trimmed)
	if err != nil {
		return "", fmt.Errorf("build: %q is not a usable repository URL: %w", trimmed, err)
	}
	if parsed.Scheme != "https" {
		return "", fmt.Errorf("build: the repository %q uses %q, and only https is accepted: an "+
			"ssh remote would need a key on the node, and any other scheme would read from the "+
			"node itself", trimmed, parsed.Scheme)
	}
	if parsed.Host == "" {
		return "", fmt.Errorf("build: the repository %q names no host", trimmed)
	}
	if parsed.User != nil {
		// Credentials belong in the environment, not in a URL that ends up in a log line,
		// a build record and every error message about this repository.
		return "", fmt.Errorf("build: the repository URL carries credentials; send them as the " +
			"source's access token instead, where they stay out of logs")
	}
	return parsed.String(), nil
}

// stageContainerName is what one stage's container is called while it exists.
func stageContainerName(buildID, stage string) string {
	return "wisper-build-" + buildID + "-" + stage
}
