# Contributing

Thanks for looking. This project has strong opinions about how it is built, and they are
written down rather than enforced by review comments after you have done the work - read
[AGENTS.md](AGENTS.md) before you write anything. It is short.

## The short version of the doctrine

- **One file, one feature. One folder, one feature cluster.** No god files, hard cap 500
  lines. No `DeploymentService` holding ten operations: one use-case per file, named with a
  verb.
- **Package by domain, never by layer.** There is no `controllers/` and there will not be.
- **No stubs.** If it is reachable, it works. This project is a rewrite of one that died of
  the opposite.
- **No dependency without a concrete reason**, and no abstraction with one implementation.
- **Comment why, not what.** The reason a line exists outlives the line.
- Code, comments, commit messages and docs are in English. The product itself ships in
  English and Vietnamese - see [docs/contracts/i18n.md](docs/contracts/i18n.md).

## The contracts are binding

`docs/contracts/` was written before the implementation and the implementation is held to
it: the seams between panel and node, how a controller's view name resolves to a React
page, the database invariants, the daemon's CLI. If your change needs one of them to be
different, change the document in the same pull request and say why.

## Getting it running

You need JDK 21, Node 24, Go 1.27, PostgreSQL 17 and Docker.

```bash
createdb -O wisper wisper
createdb -O wisper wisper_test
export WISPER_CRYPTO_KEY_1="$(openssl rand -base64 32)"

cd panel && ./gradlew bootRun --args='--spring.profiles.active=dev'
```

The dev profile carries a fixed encryption key so a laptop survives a restart without an
environment variable. It is in a committed file, which means it is public, which means it
protects nothing - that is the trade for a laptop and never the trade for a node.

For the daemon:

```bash
cd sasayaki
make build-linux
sasayaki doctor          # changes nothing; tells you what the machine is missing
sasayaki run --dev       # runc instead of runsc, because WSL2's kernel is not one gVisor supports
```

## Before you open a pull request

```bash
cd panel && ./gradlew build
cd panel/frontend && npx tsc --noEmit
cd sasayaki && gofmt -l . && go vet ./... && GOOS=linux go build ./... && go test ./...
```

CI runs all of it, plus a check that the committed protobuf stubs still match the `.proto`
files.

## Tests

Next to the code: `_test.go` in the same package, and the mirrored package under
`src/test/java`. The panel's integration tests talk to a real PostgreSQL rather than an
in-memory stand-in, because half of what they check is whether the migrations apply.

Some things cannot be tested on a developer's machine and are not faked into looking as
though they were - gVisor under a production kernel, XFS project quota, a real ACME
certificate. Those live in [docs/verify-on-linux.md](docs/verify-on-linux.md) and are
checked on a real node.

## Commits

Conventional commits, English, imperative mood, with a body explaining why when it is not
obvious. One coherent change per commit.

## Licence

Contributions are made under [Apache-2.0](LICENSE), the same licence as the project. It
carries an explicit patent grant, which is why it was chosen over MIT for something that
runs other people's workloads.
