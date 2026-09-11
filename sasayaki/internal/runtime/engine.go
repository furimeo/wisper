package runtime

import (
	"context"
	"errors"
	"iter"

	cerrdefs "github.com/containerd/errdefs"
	"github.com/moby/moby/api/types/jsonstream"
	"github.com/moby/moby/client"
)

// The surface of the Docker Engine API this daemon touches, and how to read its answers.
//
// One file, because the boundary is one thing: everything wisper can ask an engine to do
// is listed here, in twenty lines, where it can be read at a glance and where widening it
// is a visible edit rather than a new call somewhere in a hundred lines of container
// configuration.

// engine is the part of the Docker Engine API this package uses, declared here by the
// consumer so the tests can stand in for it. *client.Client satisfies it.
//
// Listing the methods rather than embedding client.APIClient is the point: it is a
// written statement that this daemon does not build images, does not touch swarm, does
// not manage plugins and never asks the engine to copy a file into a container. A method
// that is not here cannot be called by accident, and adding one is a visible edit.
type engine interface {
	ContainerCreate(ctx context.Context, options client.ContainerCreateOptions) (client.ContainerCreateResult, error)
	ContainerInspect(ctx context.Context, container string, options client.ContainerInspectOptions) (client.ContainerInspectResult, error)
	ContainerList(ctx context.Context, options client.ContainerListOptions) (client.ContainerListResult, error)
	ContainerStart(ctx context.Context, container string, options client.ContainerStartOptions) (client.ContainerStartResult, error)
	ContainerStop(ctx context.Context, container string, options client.ContainerStopOptions) (client.ContainerStopResult, error)
	ContainerRemove(ctx context.Context, container string, options client.ContainerRemoveOptions) (client.ContainerRemoveResult, error)
	ContainerLogs(ctx context.Context, container string, options client.ContainerLogsOptions) (client.ContainerLogsResult, error)
	ContainerStats(ctx context.Context, container string, options client.ContainerStatsOptions) (client.ContainerStatsResult, error)

	ExecCreate(ctx context.Context, container string, options client.ExecCreateOptions) (client.ExecCreateResult, error)
	ExecAttach(ctx context.Context, execID string, options client.ExecAttachOptions) (client.ExecAttachResult, error)
	ExecInspect(ctx context.Context, execID string, options client.ExecInspectOptions) (client.ExecInspectResult, error)
	ExecResize(ctx context.Context, execID string, options client.ExecResizeOptions) (client.ExecResizeResult, error)

	ImageInspect(ctx context.Context, image string, _ ...client.ImageInspectOption) (client.ImageInspectResult, error)
	ImagePull(ctx context.Context, ref string, options client.ImagePullOptions) (client.ImagePullResponse, error)

	NetworkCreate(ctx context.Context, name string, options client.NetworkCreateOptions) (client.NetworkCreateResult, error)
	NetworkInspect(ctx context.Context, network string, options client.NetworkInspectOptions) (client.NetworkInspectResult, error)

	Info(ctx context.Context, options client.InfoOptions) (client.SystemInfoResult, error)
	Ping(ctx context.Context, options client.PingOptions) (client.PingResult, error)
	Close() error
}

// notFound reports whether the engine said "no such thing".
//
// Worth its own function because the answer changes what a caller does more than any
// other error does: a container that is not there is a fact to act on, while every other
// failure means the engine could not be asked and nothing may be concluded from it.
func notFound(err error) bool {
	return errors.Is(err, cerrdefs.ErrNotFound)
}

// jsonMessages is the shape of a progress stream from the engine, named so the pull
// tracker can be tested without a live registry.
type jsonMessages interface {
	JSONMessages(ctx context.Context) iter.Seq2[jsonstream.Message, error]
	Close() error
}
