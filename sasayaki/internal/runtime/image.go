package runtime

import (
	"context"
	"fmt"
	"strings"

	"github.com/distribution/reference"

	"github.com/furimeo/wisper/sasayaki/internal/reconcile"
	"github.com/furimeo/wisper/sasayaki/internal/spec"
)

// EnsureImage makes a workload's image available on this node, without waiting for it.
//
// The whole shape of this function comes from one constraint: a reconcile pass has to
// finish inside the systemd watchdog's window, and a cold pull of a two-gigabyte image
// does not. So the pull runs on its own goroutine with its own lifetime, this returns
// straight away with "not yet, here is how far it has got", the workload is reported as
// PULLING - which is exactly the phase the customer is waiting on - and the pass fifteen
// seconds later asks again.
//
// The three answers, as reconcile reads them:
//
//	Ready, no error       the image is here; create the container from it
//	not Ready, no error   still pulling; report PULLING with Progress and come back
//	an error              the pull failed; report FAILED with the message
func (d *Docker) EnsureImage(ctx context.Context, workload spec.Workload) (reconcile.ImageState, error) {
	if !workload.IsApp() {
		return reconcile.ImageState{}, fmt.Errorf("runtime: workload %s is a %s and has no image",
			workload.ID, workload.Kind)
	}
	ref, err := imageRef(workload)
	if err != nil {
		return reconcile.ImageState{}, err
	}

	// A pull already running, or one that failed recently enough that hammering the
	// registry again would only get this node rate-limited.
	if state, decided, err := d.pullState(ref); decided {
		return state, err
	}

	digest, present, err := d.localDigest(ctx, ref)
	if err != nil {
		return reconcile.ImageState{}, err
	}
	if present {
		return reconcile.ImageState{Ready: true, Digest: digest}, nil
	}

	return d.startPull(ref), nil
}

// imageRef is what to ask the registry for.
//
// When the panel resolved a digest, the digest is what gets pulled - not the tag with the
// digest recorded next to it. Two nodes told to run the same tag then run the same bytes,
// and a tag that moves under a running service does not silently change what a restart
// brings up. That pinning is the panel's decision; a spec with no digest means "resolve
// the tag and tell me what you got".
func imageRef(workload spec.Workload) (string, error) {
	image := strings.TrimSpace(workload.Image)
	if image == "" {
		return "", fmt.Errorf("runtime: workload %s is an app with no image", workload.ID)
	}

	named, err := reference.ParseNormalizedNamed(image)
	if err != nil {
		return "", fmt.Errorf("runtime: workload %s has the image reference %q, which is not a "+
			"valid one: %w", workload.ID, image, err)
	}

	digest := strings.TrimSpace(workload.ImageDigest)
	if digest == "" {
		// Without a tag the registry is asked for :latest, which is what every client
		// does; making it explicit means the reference recorded on the container says
		// what was actually requested.
		return reference.FamiliarString(reference.TagNameOnly(named)), nil
	}

	pinned, err := reference.ParseNormalizedNamed(reference.TrimNamed(named).Name() + "@" + digest)
	if err != nil {
		return "", fmt.Errorf("runtime: workload %s pins image %s to digest %q, which cannot be "+
			"combined into a reference: %w", workload.ID, image, digest, err)
	}
	return reference.FamiliarString(pinned), nil
}

// localDigest asks the engine whether an image is already here, and what it is.
//
// "Not here" is a fact and returns present=false. Anything else is the engine failing to
// answer, which is returned as an error rather than being read as absence - a node that
// concluded its images had disappeared because the socket hiccuped would pull the whole
// fleet's images again.
func (d *Docker) localDigest(ctx context.Context, ref string) (string, bool, error) {
	found, err := d.api.ImageInspect(ctx, ref)
	if notFound(err) {
		return "", false, nil
	}
	if err != nil {
		return "", false, fmt.Errorf("runtime: ask the engine about the image %s: %w", ref, err)
	}
	return digestOf(ref, found.RepoDigests, found.ID), true, nil
}

// digestOf is what is really running, in the form the panel stores.
//
// A repository digest is preferred: it is what the panel put in the spec, what a registry
// would hand back, and what makes "the tag moved" visible as a difference between two
// strings. It is matched against this reference's own repository, because an image can
// carry digests from several - a mirror, a private copy - and reporting one of those
// would compare unequal against the spec forever.
//
// The image id is the fallback, for an image that was never pulled from a registry: a
// build's output, a `docker load`. It is still content-addressable and still changes when
// the bytes change, which is all the panel needs it for.
func digestOf(ref string, repoDigests []string, imageID string) string {
	if repository := repositoryOf(ref); repository != "" {
		for _, candidate := range repoDigests {
			name, digest, found := strings.Cut(candidate, "@")
			if found && name == repository {
				return digest
			}
		}
	}
	return imageID
}

// repositoryOf is the fully qualified name of a reference, without its tag or digest.
func repositoryOf(ref string) string {
	named, err := reference.ParseNormalizedNamed(ref)
	if err != nil {
		return ""
	}
	return reference.TrimNamed(named).Name()
}
