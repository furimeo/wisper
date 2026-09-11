package build

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"time"

	"github.com/furimeo/wisper/sasayaki/internal/state"
	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// One build, from the command arriving to the answer going back.
//
// Four stages, in the order build.proto names them: fetch, install, build, publish. Each
// records itself in the node's SQLite before it starts, so a build killed by a machine
// going down leaves behind where it had got to rather than an unexplained gap, and each
// writes its output up the log stream the customer's browser is already watching.
//
// A build that fails is not an error. It comes back as a BuildCompleted with success false,
// the stage it stopped at and the exit code, because "your build script returned 1" is the
// customer's information and the RPC did exactly what it was asked. An error is returned
// only when the build could not be attempted at all, and rpc/dispatch.go turns that into a
// command that failed rather than a result.
//
// Past three hundred lines and deliberately not split further. What is left after the
// retention half moved to prune.go is one use case - Build - and the stage machine it walks
// through; the fetch, the plan, the publish and the recovery each already live in their own
// file, and splitting the sequence that calls them would leave nowhere to read the order of
// a deployment in one go.

// Build runs one build to completion.
func (b *Builder) Build(ctx context.Context, request *wisperpb.StartBuild) (*wisperpb.BuildCompleted, error) {
	if err := validate(request); err != nil {
		return nil, err
	}
	buildID := request.GetBuildId()
	workloadID := request.GetWorkloadId()

	unlock, err := b.lockWorkload(ctx, workloadID)
	if err != nil {
		return nil, err
	}
	defer unlock()

	startedAt := b.now()
	begun := state.BuildRun{
		BuildID:    buildID,
		WorkloadID: workloadID,
		ReleaseID:  buildID,
		Stage:      wisperpb.BuildStage_BUILD_STAGE_FETCH,
		StartedAt:  startedAt,
		Detail:     "starting",
	}
	if err := b.store.BeginBuild(ctx, begun); err != nil {
		return b.replay(ctx, buildID, err)
	}

	log := newBuildLog(b.sink, request.GetLogStreamId(), buildID, b.now)
	defer log.end()

	timeout := timeoutFor(request)
	deadline, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	log.say("build %s of workload %s starting", buildID, workloadID)
	completed := b.perform(deadline, request, log, startedAt, timeout)

	// Deliberately not the deadline context: the most important record to write is the one
	// for a build that ran out of time, and writing it through a cancelled context is how
	// a deployment gets stuck at "building" forever.
	if err := b.store.FinishBuild(context.WithoutCancel(ctx), buildID, completed, b.now()); err != nil {
		// The panel still gets its answer. Saying so at error level rather than failing the
		// command: the build really did happen, and refusing to report it because a row
		// would not write would lose the outcome as well as the record of it.
		b.log.Error("could not record the outcome of a build",
			slog.String("build", buildID), slog.String("error", err.Error()))
	}

	b.log.Info("build finished",
		slog.String("build", buildID),
		slog.String("workload", workloadID),
		slog.Bool("ok", completed.GetSuccess()),
		slog.String("detail", completed.GetDetail()))
	return completed, nil
}

// perform is the stage machine. It never returns an error: everything that can go wrong
// here is something the customer is entitled to see, so it comes back as a result.
func (b *Builder) perform(ctx context.Context, request *wisperpb.StartBuild, log *buildLog,
	startedAt time.Time, timeout time.Duration) *wisperpb.BuildCompleted {

	outcome := report{buildID: request.GetBuildId(), releaseID: request.GetBuildId(), startedAt: startedAt}
	fail := func(stage wisperpb.BuildStage, code int32, detail string) *wisperpb.BuildCompleted {
		log.flush()
		log.say("build failed during %s: %s", describeStage(stage), detail)
		b.cleanUpAfterFailure(ctx, request)
		return outcome.failed(b.now(), stage, code, detail)
	}

	// Containers a killed daemon left running. Done here rather than at startup so that a
	// daemon whose engine was unreachable when it came up still sweeps once the engine is
	// back, and so that this package needs no lifecycle hook of its own.
	b.sweepAbandoned(ctx, b.log)

	b.enterStage(ctx, request, wisperpb.BuildStage_BUILD_STAGE_FETCH, log)
	space, found, err := b.fetch(ctx, request, log)
	if err != nil {
		return fail(wisperpb.BuildStage_BUILD_STAGE_FETCH, 0, err.Error())
	}
	outcome.source = found

	root, err := resolveInside(space.Checkout, subdirectoryOf(request), "subdirectory")
	if err != nil {
		return fail(wisperpb.BuildStage_BUILD_STAGE_FETCH, 0, err.Error())
	}

	switch request.GetOutput() {
	case wisperpb.BuildOutputKind_BUILD_OUTPUT_KIND_CONTAINER_IMAGE:
		b.enterStage(ctx, request, wisperpb.BuildStage_BUILD_STAGE_BUILD, log)
		built, err := b.buildImage(ctx, request, root, log)
		if err != nil {
			if ctx.Err() != nil {
				return fail(wisperpb.BuildStage_BUILD_STAGE_BUILD, 137,
					timedOutDetail(wisperpb.BuildStage_BUILD_STAGE_BUILD, timeout))
			}
			return fail(wisperpb.BuildStage_BUILD_STAGE_BUILD, 1, err.Error())
		}
		outcome.image = built
		outcome.bytes = built.Bytes

	case wisperpb.BuildOutputKind_BUILD_OUTPUT_KIND_STATIC_RELEASE:
		if failure := b.runPlan(ctx, request, space, root, log, timeout, fail); failure != nil {
			return failure
		}

		b.enterStage(ctx, request, wisperpb.BuildStage_BUILD_STAGE_PUBLISH, log)
		output, err := resolveInside(root, request.GetPlan().GetOutputDirectory(), "output directory")
		if err != nil {
			return fail(wisperpb.BuildStage_BUILD_STAGE_PUBLISH, 0, err.Error())
		}
		size, err := b.collectRelease(ctx, request.GetWorkloadId(), outcome.releaseID, output)
		if err != nil {
			return fail(wisperpb.BuildStage_BUILD_STAGE_PUBLISH, 0, err.Error())
		}
		outcome.bytes = size
		outcome.pruned = b.prune(ctx, request, outcome.releaseID, log)

	default:
		return fail(wisperpb.BuildStage_BUILD_STAGE_UNSPECIFIED, 0,
			fmt.Sprintf("the panel asked for output kind %s, which this node does not produce",
				request.GetOutput()))
	}

	b.trimWorkspaces(ctx, request)
	log.flush()
	detail := b.describeSuccess(outcome)
	log.say("%s", detail)
	return outcome.succeeded(b.now(), detail)
}

// runPlan is the install and build commands, each in its own throwaway container.
//
// Two containers rather than one with two execs: an exec's exit code is not retrievable
// after a disconnect, and a container that spans both stages is one that survives the
// failure of the first. The workspace is a bind mount, so what the install left behind is
// exactly what the build finds.
func (b *Builder) runPlan(ctx context.Context, request *wisperpb.StartBuild, space workspace,
	root string, log *buildLog, timeout time.Duration,
	fail func(wisperpb.BuildStage, int32, string) *wisperpb.BuildCompleted) *wisperpb.BuildCompleted {

	plan := request.GetPlan()
	steps := []struct {
		stage   wisperpb.BuildStage
		command []string
	}{
		{wisperpb.BuildStage_BUILD_STAGE_INSTALL, plan.GetInstallCommand()},
		{wisperpb.BuildStage_BUILD_STAGE_BUILD, plan.GetBuildCommand()},
	}

	for _, step := range steps {
		if len(step.command) == 0 {
			// BUILD_PRESET_STATIC is exactly this: the repository already contains the
			// site, so there is nothing to install and nothing to compile.
			continue
		}
		b.enterStage(ctx, request, step.stage, log)
		if err := b.ensureImage(ctx, plan.GetBuilderImage(), log); err != nil {
			return fail(step.stage, 0, err.Error())
		}

		log.say("$ %s", quoteCommand(step.command))
		result, err := b.runContainer(ctx, containerRun{
			Name:       stageContainerName(request.GetBuildId(), describeStage(step.stage)),
			BuildID:    request.GetBuildId(),
			Image:      plan.GetBuilderImage(),
			Entrypoint: step.command[:1],
			Command:    step.command[1:],
			WorkingDir: containerPath(space.Root, root),
			Env:        buildEnvironment(request.GetBuildEnv()),
			HostPath:   space.Root,
			Limits:     request.GetLimits(),
			Stdout:     log.stdout(),
			Stderr:     log.stderr(),
		})
		if err != nil {
			return fail(step.stage, 0, err.Error())
		}
		if result.TimedOut {
			return fail(step.stage, result.ExitCode, timedOutDetail(step.stage, timeout))
		}
		if !result.Ok() {
			return fail(step.stage, result.ExitCode,
				fmt.Sprintf("%s exited with code %d", step.command[0], result.ExitCode))
		}
	}
	return nil
}

// fetch prepares the workspace and puts the source in it.
func (b *Builder) fetch(ctx context.Context, request *wisperpb.StartBuild, log *buildLog) (workspace, source, error) {
	git := request.GetSource().GetGit()
	archive := request.GetSource().GetArchive()

	// A cached workspace is only useful to a git source, which knows which of its files
	// are tracked. An archive is unpacked into an empty tree every time (fetcharchive.go).
	space, err := b.prepareWorkspace(request.GetWorkloadId(), request.GetBuildId(),
		request.GetUseCache() && git != nil)
	if err != nil {
		return workspace{}, source{}, err
	}

	switch {
	case git != nil:
		found, err := b.fetchGit(ctx, space, request, git, log)
		return space, found, err
	case archive != nil:
		found, err := b.fetchArchive(ctx, space, archive, log)
		return space, found, err
	default:
		return space, source{}, errors.New("build: the request carries neither a git source nor an archive")
	}
}

// cleanUpAfterFailure removes what this build had started to write, and nothing else.
//
// The live release is never touched: not the directory, not the symlink, not the workspace
// of the build that produced it. A failed deployment leaves the site serving exactly what
// it was serving a second earlier, which is the whole point of building into a new
// directory and publishing by symlink.
func (b *Builder) cleanUpAfterFailure(ctx context.Context, request *wisperpb.StartBuild) {
	if request.GetOutput() != wisperpb.BuildOutputKind_BUILD_OUTPUT_KIND_STATIC_RELEASE {
		return
	}
	workloadID := request.GetWorkloadId()
	releaseID := request.GetBuildId()

	published, err := b.releases.Published(context.WithoutCancel(ctx), workloadID)
	if err != nil {
		b.log.Warn("could not check which release is live, so nothing was cleaned up",
			slog.String("workload", workloadID), slog.String("error", err.Error()))
		return
	}
	if published == releaseID {
		// Only reachable when the build collided with a release that was already live,
		// which clearCollision refuses before anything is written. Guarded here as well,
		// because this is the one function in the package that deletes a release.
		return
	}
	if err := discardRelease(b.stateDir, workloadID, releaseID); err != nil {
		b.log.Warn("could not remove the half-written release of a failed build",
			slog.String("workload", workloadID),
			slog.String("release", releaseID),
			slog.String("error", err.Error()))
	}
}

// enterStage records where the build has got to, in the log the customer is watching and in
// the row that survives the daemon being killed.
func (b *Builder) enterStage(ctx context.Context, request *wisperpb.StartBuild,
	stage wisperpb.BuildStage, log *buildLog) {

	log.flush()
	log.say("--- %s", describeStage(stage))

	err := b.store.RecordBuildStage(context.WithoutCancel(ctx), request.GetBuildId(), stage,
		b.now(), describeStage(stage))
	if err != nil {
		b.log.Warn("could not record which stage a build reached",
			slog.String("build", request.GetBuildId()),
			slog.String("stage", describeStage(stage)),
			slog.String("error", err.Error()))
	}
}

// replay answers a command the panel sent twice.
//
// The control stream drops between the node finishing a build and the panel receiving the
// result, the panel resends, and the honest answer is the one already on disk: rebuilding
// would be minutes of a shared machine spent producing what is already there, and a second
// release directory for one deployment.
func (b *Builder) replay(ctx context.Context, buildID string, cause error) (*wisperpb.BuildCompleted, error) {
	if !errors.Is(cause, state.ErrAlreadyExists) {
		return nil, fmt.Errorf("build: record the start of build %s: %w", buildID, cause)
	}

	existing, err := b.store.Build(ctx, buildID)
	if err != nil {
		return nil, fmt.Errorf("build: build %s has been started before and cannot be read back: %w",
			buildID, err)
	}
	if existing.Finished && existing.Result != nil {
		b.log.Info("replaying the result of a build the panel asked for twice",
			slog.String("build", buildID), slog.Bool("ok", existing.Success))
		return existing.Result, nil
	}
	return nil, fmt.Errorf("build: build %s is already running on this node; it started at %s "+
		"and reached the %s stage", buildID, existing.StartedAt.Format(time.RFC3339),
		describeStage(existing.Stage))
}

// describeSuccess is the one line shown next to a finished deployment.
func (b *Builder) describeSuccess(outcome report) string {
	if outcome.image.Ref != "" {
		return fmt.Sprintf("built image %s (%d bytes)", outcome.image.Ref, outcome.bytes)
	}
	return fmt.Sprintf("release %s is ready (%d bytes)", outcome.releaseID, outcome.bytes)
}
