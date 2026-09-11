// Package build turns a customer's source into something this node can serve.
//
// Two shapes come out of it and only two, because BuildOutputKind has only two values:
//
//   - a static release, which is a directory under sites/<workload>/releases/<release>/;
//   - a container image in the local Docker daemon, tagged with the release id.
//
// Both start the same way: fetch the source (a git clone at a pinned commit, or a zip the
// customer uploaded through the panel), then run the plan the panel resolved - an install
// command and a build command, or the repository's own Dockerfile - inside an ephemeral,
// resource-capped container that is removed whether it succeeded or not.
//
// # Publishing is not here
//
// A finished build leaves a release directory behind and stops. It does not move the
// `current` symlink, and it never touches what is being served. A release goes live when
// the next NodeSpec names it in the workload and the reconcile loop calls Releases.Publish;
// a rollback is the same move with an older id (build.proto, and design section 5.5). One
// mechanism, so "what is live" has exactly one answer and a build that fails halfway
// cannot become that answer by accident.
//
// That is why a failure in this package is quiet from the outside: the workspace is left
// for the retention policy to sweep, the half-written release directory is removed, and
// the site carries on serving whatever it was serving a second earlier.
//
// # Everything runs in a container
//
// Including git. sasayaki is one static binary whose only host dependencies are a Linux
// kernel and a Docker socket, so shelling out to a `git` that may not be installed - and
// running a stranger's repository's hooks and submodule URLs as root on the host - is not
// an option. The clone happens in a pinned image, the token reaches it through the
// environment and a credential helper rather than through argv or a file, and the
// container is gone before the next stage starts.
//
// The one thing that cannot be contained this way is `docker build`: the classic builder
// runs RUN steps under the engine's own runtime, not under gVisor, and there is no field
// in the Engine API to change that. It is capped for memory, CPU and pids like everything
// else here, and buildimage.go says so out loud rather than implying containment that is
// not there.
//
// # The file layout
//
//	<state>/builds/<workload>/<build>/source/            the checkout, and the caches
//	<state>/sites/<workload>/releases/<release>/         one finished release
//	<state>/sites/<workload>/current -> releases/<id>    the symlink the edge reads
//
// Ids, never customer names: a rename must not move a directory, and an id that reached a
// path without being checked is the bug class this platform is arranged to avoid
// (layout.go).
package build
