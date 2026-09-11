# Protobuf code generation

Binding. See [README.md](README.md).

`proto/wisper/v1/*.proto` is the single contract between the panel and sasayaki. It is
generated into both languages from the same directory, so a field renamed on one side
fails to compile on the other instead of becoming a 500 at two in the morning.

## Layout

Split by domain, one file each. There is no `wisper.proto`.

```
proto/wisper/v1/
├── node.proto        NodeService, enrolment, the control stream, heartbeats
├── workload.proto    the NodeSpec: workloads, routes, databases, cron
├── terminal.proto    TerminalFrame and its oneof
├── files.proto       FileOp, chunked transfer
├── stats.proto       samples and rollups
└── backup.proto      snapshot and restore
```

## Options every file must set

```protobuf
syntax = "proto3";

package wisper.v1;

option java_package = "lhqm.furimeo.wisper.proto.v1";
option java_multiple_files = true;
option java_outer_classname = "<FileName>Proto";
option go_package = "github.com/furimeo/wisper/sasayaki/internal/wisperpb;wisperpb";
```

`java_multiple_files = true` because a single outer class holding forty message types is
the god file the structure law exists to prevent - and it is generated code, so nobody
would ever split it by hand.

`java_outer_classname` still has to be set and has to be unique per file, otherwise the
descriptor class collides with a message of the same name.

## Java side

Already wired in `panel/build.gradle`. The `com.google.protobuf` plugin reads
`../proto`, downloads `protoc` and `protoc-gen-grpc-java` from Maven Central at the
versions Spring Boot's BOM pins, and puts the output in
`panel/build/generated/source/proto/main/{java,grpc}` - which is on the main source set,
so `import lhqm.furimeo.wisper.proto.v1.NodeSpec;` just works.

Nothing to install. Nothing to commit. `./gradlew build` regenerates.

## Go side

Generated with `buf` into `sasayaki/internal/wisperpb`, package name `wisperpb`.

The generated `.pb.go` files **are committed**. `go build ./...` has to work on a
machine that has never heard of buf - that is the point of a single static binary - and
a generated file that only exists after a tool run is a build step waiting to be
forgotten in CI.

Bootstrap, once per machine:

```bash
go install github.com/bufbuild/buf/cmd/buf@latest
go install google.golang.org/protobuf/cmd/protoc-gen-go@latest
go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@latest
```

## When a message changes

Regenerate both sides in the same commit. Never one without the other: the whole reason
this contract exists is that the two are the same edit.

## Compatibility

`Connect()` negotiates a protocol version in its first frame. It is
`internal/version.Protocol` on the node side and must match the constant the panel's
`grpc` package holds; the two are bumped together, in the same commit, and only when a
change is one an older peer cannot interpret. A mismatch means the panel refuses the
stream and shows "node needs upgrading" - never two versions quietly misunderstanding
each other.

Within a protocol version the ordinary protobuf rules apply: add fields, never renumber
them, never reuse a tag, mark removed ones `reserved`.
