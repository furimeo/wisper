package build

import (
	"context"
	"errors"
	"fmt"
	"strings"

	cerrdefs "github.com/containerd/errdefs"
	"github.com/distribution/reference"
	"github.com/moby/moby/client"
)

// Getting the image a stage runs in, and naming the image a build produces.
//
// A build blocks on its pull, unlike a workload's. The reconcile loop cannot: it has a
// watchdog window measured in seconds and a cold pull takes minutes, so it starts one and
// answers "not yet" (runtime/image.go). A build has nowhere to come back to - it is one
// long operation with its own timeout - so waiting here is both correct and simpler, and
// the timeout the panel sent is what bounds it.

// ensureImage makes sure an image is on this node, pulling it if it is not.
//
// Idempotent and quiet on the common path: a node that has built anything before already
// has the git image, and the second build of the day says nothing about it.
func (b *Builder) ensureImage(ctx context.Context, ref string, log *buildLog) error {
	normalised, err := normaliseRef(ref)
	if err != nil {
		return err
	}

	if _, err := b.engine.ImageInspect(ctx, normalised); err == nil {
		return nil
	} else if !errors.Is(err, cerrdefs.ErrNotFound) {
		// The engine could not be asked. Not the same as "the image is missing", and
		// pulling on this path would hammer a registry over a socket hiccup.
		return fmt.Errorf("build: ask the engine about the image %s: %w", normalised, err)
	}

	log.say("pulling %s", normalised)
	response, err := b.engine.ImagePull(ctx, normalised, client.ImagePullOptions{})
	if err != nil {
		return fmt.Errorf("build: pull %s: %w", normalised, err)
	}
	defer response.Close()

	// Wait consumes the stream and reports whatever the registry said. The progress
	// messages are deliberately not forwarded to the customer's build log: a pull emits
	// hundreds of percentage updates per layer, and the customer wants the compiler's
	// output, not the node's housekeeping.
	if err := response.Wait(ctx); err != nil {
		return fmt.Errorf("build: pull %s: %w", normalised, err)
	}
	return nil
}

// normaliseRef turns what the panel sent into what the engine takes, and refuses what it
// would misread.
//
// An image reference with no tag means :latest to every client, and making that explicit
// means the build log says which one was used rather than leaving a reader to know the
// convention.
func normaliseRef(ref string) (string, error) {
	trimmed := strings.TrimSpace(ref)
	if trimmed == "" {
		return "", fmt.Errorf("build: the plan names no image to build in")
	}
	named, err := reference.ParseNormalizedNamed(trimmed)
	if err != nil {
		return "", fmt.Errorf("build: %q is not a valid image reference: %w", trimmed, err)
	}
	return reference.FamiliarString(reference.TagNameOnly(named)), nil
}

// releaseTag is what a built image is called on this node.
//
// Repository from the workload, tag from the release, so `docker images` on a node reads
// as a deployment history and two releases of one service never overwrite each other. The
// panel puts this string in the workload's `image` field and the running container is then
// provably the built one.
func releaseTag(workloadID, releaseID string) (string, error) {
	if err := checkIdentifier("workload id", workloadID); err != nil {
		return "", err
	}
	if err := checkIdentifier("release id", releaseID); err != nil {
		return "", err
	}
	// Lowercased because a repository name may not contain capitals, and an id or a
	// release number never does - so this only ever normalises, never collides.
	return "wisper/" + strings.ToLower(workloadID) + ":" + strings.ToLower(releaseID), nil
}

// imageFacts is what the panel is told about an image that was just built.
func (b *Builder) imageFacts(ctx context.Context, ref string) (digest string, size int64, err error) {
	inspected, err := b.engine.ImageInspect(ctx, ref)
	if err != nil {
		return "", 0, fmt.Errorf("build: ask the engine about the image %s it just built: %w", ref, err)
	}
	// The image id, not a repository digest: nothing has been pushed anywhere, so no
	// registry has minted a manifest digest for it. The id is content-addressable over the
	// configuration and the layers, which is exactly the property the panel needs - it
	// changes when the bytes change.
	return inspected.ID, inspected.Size, nil
}
