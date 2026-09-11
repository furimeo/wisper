package build

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"strings"

	"github.com/moby/moby/api/types/build"
	"github.com/moby/moby/api/types/jsonstream"
	"github.com/moby/moby/client"

	"github.com/furimeo/wisper/sasayaki/internal/wisperpb"
)

// BUILD_PRESET_DOCKERFILE: the repository builds its own image.
//
// The output is an image in this node's local Docker daemon tagged with the release id,
// and nothing is pushed anywhere - v1 builds on the node that will run the result, so
// there is no registry to push to and nothing to pull back (design section 11.4).
//
// # The isolation this stage does not have
//
// Every other stage runs in a container this package configures: gVisor where the node has
// it, all capabilities dropped, no-new-privileges. A Dockerfile's RUN steps do not. They
// are executed by the engine's own builder, under whatever runtime it defaults to, and the
// Engine API has no field with which to ask for another. What can be capped is capped -
// memory, swap, CPU, pids, file descriptors, all from the same ResourceLimits every other
// stage uses - and the difference is written here rather than left for somebody to
// discover, because a platform that implies containment it does not provide is the failure
// mode gVisor was adopted to prevent.
//
// The classic builder is used rather than BuildKit. BuildKit over the HTTP API needs a
// session the daemon would have to serve, and its progress stream is a different format;
// the classic builder needs neither and produces the plain line-by-line output a customer
// watching a deployment expects to see.

// builtImage is what a Dockerfile build produced.
type builtImage struct {
	Ref    string
	Digest string
	Bytes  int64
}

// buildImage runs `docker build` over the checkout and tags the result.
func (b *Builder) buildImage(ctx context.Context, request *wisperpb.StartBuild, root string,
	log *buildLog) (builtImage, error) {

	plan := request.GetPlan()
	contextRoot, err := resolveInside(root, plan.GetBuildContext(), "build context")
	if err != nil {
		return builtImage{}, err
	}
	dockerfile, err := dockerfileName(contextRoot, plan.GetDockerfilePath())
	if err != nil {
		return builtImage{}, err
	}
	tag, err := releaseTag(request.GetWorkloadId(), request.GetBuildId())
	if err != nil {
		return builtImage{}, err
	}

	ignore, err := readIgnoreList(contextRoot)
	if err != nil {
		return builtImage{}, err
	}

	quota, period := cpuQuotaFor(request.GetLimits().GetNanoCpus())
	options := client.ImageBuildOptions{
		Tags:       []string{tag},
		Dockerfile: dockerfile,
		BuildArgs:  buildArguments(request.GetBuildEnv()),
		Labels:     buildLabels(request.GetBuildId()),
		// Intermediate containers go, whether the build worked or not: a failed
		// Dockerfile otherwise leaves one per step on the node forever.
		Remove:      true,
		ForceRemove: true,
		// "Clear cache and deploy" means the layer cache as well as the workspace, and it
		// is also the only way to pick up a moved base tag - so re-resolving FROM is tied
		// to the same switch rather than being a second one nobody knows about.
		NoCache:        !request.GetUseCache(),
		PullParent:     !request.GetUseCache(),
		Memory:         request.GetLimits().GetMemoryBytes(),
		MemorySwap:     swapCeiling(request.GetLimits()),
		CPUQuota:       quota,
		CPUPeriod:      period,
		Ulimits:        buildResources(request.GetLimits()).Ulimits,
		Version:        build.BuilderV1,
		NetworkMode:    "bridge",
		SuppressOutput: false,
	}

	log.say("building the image from %s", dockerfile)
	stream := tarDirectory(ctx, contextRoot, ignore)
	defer stream.Close()

	result, err := b.engine.ImageBuild(ctx, stream, options)
	if err != nil {
		return builtImage{}, fmt.Errorf("build: ask the engine to build the image: %w", err)
	}
	defer result.Body.Close()

	if err := followBuild(result.Body, log); err != nil {
		return builtImage{}, err
	}

	digest, size, err := b.imageFacts(ctx, tag)
	if err != nil {
		return builtImage{}, err
	}
	log.say("built %s (%s)", tag, digest)
	return builtImage{Ref: tag, Digest: digest, Bytes: size}, nil
}

// swapCeiling is the memory+swap total the build gets, kept at or above the memory limit
// because the engine rejects the pair otherwise. Equal to memory disables swap, which is
// what a build wants: one that swaps makes the whole machine grind.
func swapCeiling(limits *wisperpb.ResourceLimits) int64 {
	memory := limits.GetMemoryBytes()
	if memory <= 0 {
		return 0
	}
	swap := limits.GetMemorySwapBytes()
	if swap <= 0 || swap < memory {
		return memory
	}
	return swap
}

// followBuild turns the engine's progress stream into build log lines and decides whether
// it worked.
//
// The stream is newline-delimited JSON, and the failure of a RUN step arrives inside it as
// an errorDetail object with an HTTP 200 around the whole response. A reader that only
// checked the status code would report every broken Dockerfile as a successful build, so
// the error object is the authority here.
func followBuild(body io.Reader, log *buildLog) error {
	decoder := json.NewDecoder(body)
	for {
		var message jsonstream.Message
		err := decoder.Decode(&message)
		if err == io.EOF {
			return nil
		}
		if err != nil {
			return fmt.Errorf("build: read the engine's build output: %w", err)
		}

		if message.Error != nil {
			detail := strings.TrimSpace(message.Error.Message)
			if detail == "" {
				detail = "the engine did not say why"
			}
			return fmt.Errorf("build: the image build failed: %s", detail)
		}
		if text := message.Stream; text != "" {
			log.write(wisperpb.LogStreamKind_LOG_STREAM_KIND_STDOUT, []byte(text))
			continue
		}
		if status := strings.TrimSpace(message.Status); status != "" && message.Progress == nil {
			// Layer pulls of the base image. The percentage updates carry a Progress and
			// are dropped; the "Pulling from library/node" lines are worth showing.
			log.say("%s", status)
		}
	}
}
