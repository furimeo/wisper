# wisper - engineering doctrine

> **NVNMC Hosting Environment & Operations:** See [`agent.md`](agent.md) for NVNMC Hosting workspace instructions, production VM `192.168.1.149` (`wisper.nvnmc.cloud`), safety risk gates, and deployment workflows.

Read this before you touch anything. It is short on purpose, and every rule in it
exists because its absence cost this project's predecessor a rewrite.

**wisper** is the panel: one Spring Boot jar that holds the desired state of the whole
platform. **sasayaki** is the node daemon: one static Go binary that makes reality match
that desired state. Customers' traffic never touches the panel.

The binding technical contracts live in [`docs/contracts/`](docs/contracts/README.md).
Where this file and a contract disagree, the contract wins.

---

## 1. Stack (locked - do not substitute)

| Area | Choice | Why it is not negotiable |
|---|---|---|
| Panel language | Java 21 LTS, virtual threads on | Records, pattern matching, sealed types, no framework needed to get there |
| Panel web | Spring Boot 4.1.x, **Spring MVC** | Blocking code on virtual threads reads like blocking code. **Never WebFlux.** |
| Panel data | **Spring Data JDBC**, PostgreSQL 17 | Explicit SQL. `SKIP LOCKED` and `LISTEN/NOTIFY` are load-bearing. **Never JPA/Hibernate.** |
| Job queue | db-scheduler, on the same Postgres | A job and the row it acts on commit in one transaction. No Redis. |
| Migrations | DIY runner over `resources/wisper/migrations/V{n}__name.sql` | No Flyway, no Liquibase |
| Panel UI | React 19 + TypeScript + Inertia + Vite, built into the jar | Controllers return a view name and a model; authorization stays in one place |
| UI shape | **Mobile-first** | Most customers arrive on a phone. Not a shrunken desktop. |
| Realtime | SSE | Proven; no second protocol to operate |
| Node daemon | Go 1.27, one static binary | Deploying a node is copying one file |
| Edge | Caddy embedded **as a library inside sasayaki** | On-demand TLS answered in-process, so it survives the panel being down |
| Isolation | Docker Engine API + gVisor `runsc` (per-service `runc` escape hatch) | Syscall filtering in userspace |
| Node state | Local SQLite | A restarted daemon reconverges instead of forgetting |
| Panel ↔ node | gRPC + protobuf, **the node always dials out** | The panel has no public IP. One contract, two languages. |
| Build | Gradle (Groovy DSL) for the panel, Makefile for sasayaki | |
| Java package root | `lhqm.furimeo.wisper` | |
| Go module | `github.com/furimeo/wisper/sasayaki` | |

Deliberately absent, and not to be reintroduced: Kubernetes, microservices, WebFlux,
JPA/Hibernate, Redis, Lombok, MapStruct, Guava, Apache Commons, a separate REST API for
the UI, SSH/SFTP/WebDAV, automatic DNS, billing, multi-region.

---

## 2. No stubs. Ever.

This project exists because the previous one shipped a menu full of screens that
rendered nothing. The rule that replaces that habit:

> **If it is reachable, it works.**

Concretely, none of the following may be merged:

- `TODO`, `FIXME`, `not implemented`, `UnsupportedOperationException`, `panic("todo")`;
- a method that returns `null`/`nil`/an empty list purely to satisfy a signature;
- a route in the navigation whose page renders an empty frame;
- an RPC declared in `proto/wisper/v1/` with an implementation on only one side;
- an interface with no implementation, or a factory over exactly one implementation.

If a feature is genuinely out of scope, it does not get a door. Do not build the door
and leave the room empty.

---

## 3. The file-structure law

**One file = one feature. One folder = one feature cluster.** This is checked, not
advised.

### 3.1 No service class holding ten operations

There is no `DeploymentService`. Each use-case is its own file, named with a **verb**:

```
panel/src/main/java/lhqm/furimeo/wisper/deploy/
├── Deployment.java             # record: one deployment's state
├── DeploymentStatus.java       # enum
├── DeploymentRepository.java   # Spring Data JDBC
├── DeploymentController.java   # HTTP -> Inertia view + model
├── GitWebhookController.java   # GitHub/GitLab webhook endpoint
├── StartDeployment.java        # use-case: accept the request, enqueue the job
├── BuildArtifact.java          # use-case: hand the build to a node
├── PublishRelease.java         # use-case: swap the symlink / start the container
├── RollbackRelease.java        # use-case: point back at the previous release
└── AppendDeploymentLog.java    # use-case: append one log line
```

The same shape on the Go side:

```
sasayaki/internal/reconcile/
├── loop.go       # the 15s loop, backoff, graceful shutdown
├── diff.go       # desired vs actual -> list of actions
├── apply.go      # execute one action
├── workload.go   # converge one workload
├── routes.go     # push the route table to the edge
├── report.go     # collect statuses for the panel
└── *_test.go     # tests next to the code
```

And on the frontend, grouped by feature rather than by file type:

```
panel/frontend/src/features/files/
├── FileManagerPage.tsx
├── FileList.tsx
├── FileRow.tsx
├── UploadDropzone.tsx
├── useChunkedUpload.ts
├── FileEditor.tsx
└── MobileKeyBar.tsx
```

### 3.2 Length

- **Over 300 lines**: split it, or write a comment at the top saying why you did not.
- **Over 500 lines**: rejected. Exempt: generated code (protobuf, Inertia bundles) and
  SQL migrations.

### 3.3 Package by domain, never by layer

Panel packages are `auth`, `org`, `project`, `service`, `deploy`, `domain`, `node`,
`placement`, `database`, `backup`, `files`, `stats`, `grpc`, `web`, `migration`.

There is no `controller/`, `service/`, `dto/`, `model/` or `config/` folder. A
controller lives beside the record and the repository it serves.

> `service/` in the panel is a **domain**: the deployable unit a customer owns
> (`kind ∈ {app, site}`). It is not the layer-cake meaning of the word.

### 3.4 Banned names

Folders - anywhere, in either language:

```
util/  utils/  common/  shared/  helpers/  misc/  core/  base/
```

Types: `*Manager`, `*Helper`, `*Util`, `Abstract*`, `Base*` when there is exactly one
implementation. If you cannot name it after what it does, you have not decided what it
does.

Frontend: `components/`, `hooks/`, `types/` at the root of `frontend/src/`. Those live
inside the feature folder that uses them.

### 3.5 Split at the real boundary

- `proto/wisper/v1/` splits by domain - `node.proto`, `workload.proto`,
  `terminal.proto`, `files.proto`, `stats.proto`, `backup.proto`. There is no single
  giant `wisper.proto`.
- Migrations: one file per change, `V{n}__<the specific change>.sql`. Do not pack
  several unrelated tables into one file.
- Tests sit next to the code: Go `_test.go` in the same package, Java mirroring the
  package under `src/test/java`.

---

## 4. Architectural rules that are easy to break by accident

1. **Desired state, not imperative RPC.** The panel publishes "node N must be running
   this workload set at generation 47". It never says "start container X". sasayaki
   diffs and converges, every 15 seconds, event or no event.
2. **The panel owns intent; the node owns fact.** Never write the same field from both
   directions. `desired_state` comes from the panel; `applied_generation`, health and
   statistics come from the node.
3. **The node dials out.** The panel has no route to a node. Every stream starts at the
   node. A dropped connection is normal, not an incident - reconnect with jittered
   backoff, forever, and resend the **full** spec on reconnect, never a delta.
4. **Crash-only on the node.** No cleanup on exit. The truth is on disk. Being killed is
   the same as shutting down.
5. **"Cannot see it" is not "does not exist".** If Docker is unreachable, report
   `degraded` and retry. Never delete a container because a query failed.
6. **One process for the panel.** Web, scheduler and workers in the same jar.

---

## 5. Security baseline (mandatory, not aspirational)

- Spring Security with sessions, form login, CSRF on every state change, BCrypt,
  TOTP 2FA, scoped API tokens.
- The file manager is the largest attack surface in v1: every path is resolved and
  checked to be inside the volume before any operation. Path traversal is a build
  failure, not a bug report.
- Never build a shell string. `ProcessBuilder` with an argument list on the panel,
  `exec.Command` with an argument slice on the node.
- Containers: gVisor `runsc` by default, user-namespace remap, all capabilities
  dropped, `no-new-privileges`, seccomp, read-only rootfs where the workload allows,
  pids and ulimit caps, a private network per tenant. **`docker.sock` is never exposed
  to a workload.**
- Egress to private ranges and cloud metadata endpoints is blocked by default.
- Node enrollment tokens are single-use with a 15-minute TTL, are accepted only via
  `--token-file` or stdin (never argv - argv is world-readable in `ps`), and the panel's
  certificate is pinned on first use.
- Every state-changing action writes an audit record with actor, IP and node.

---

## 6. Dependencies

A new dependency needs a concrete reason written next to it. "It is convenient" is not
one. Use the JDK and the Go standard library first - both are large, and both are
already deployed.

Do not downgrade a version to make an old tutorial compile. Read the current API.

---

## 7. Language and Localization

All code, comments, identifiers, commit messages and in-repo documentation are in
**English**. Commit messages follow Conventional Commits.

### 7.1 Full-site localization (English and Vietnamese)

The panel web interface must be **100% localized in both English (`en`) and Vietnamese (`vi`)**.
No partial translations, no hardcoded customer-facing text, and no raw keys shown to users.

- **Translation mechanism**: All UI text is retrieved via `t('feature.screen.item', params)`
  from `@/i18n`. Hardcoded user-visible text in components or views is forbidden.
- **Flat JSON catalogues**: Catalogues live in `panel/frontend/src/i18n/<locale>/<domain>.json`.
  Every catalogue MUST be a **flat key-value JSON** where keys are fully qualified dotted paths
  (e.g., `"service.list.col_service": "..."`). **Never use nested JSON objects** because the
  runtime lookup directly indexes `catalog[key]`.
- **100% key parity**: Every translation key in `en/<domain>.json` MUST have an exact matching
  entry in `vi/<domain>.json`, and vice versa.
- **Grammar & plurals**: English uses `{one: "...", other: "..."}` for plural boundaries with
  `{count}`. Vietnamese has no grammatical plural, so its catalogue entry is always a single string
  (e.g., `"{count} dịch vụ"`).
- **Technical terms stay standard**: Infrastructure & protocol terms remain English in both
  locales: `node`, `sasayaki`, `backup`, `runsc`, `gVisor`, `cgroups v2`, `Docker`, `API token`,
  `cron`, etc. Do not invent awkward translations for standard engineering terms.

---

## 8. Verifying your work

```bash
# panel
cd panel && ./gradlew build

# frontend
cd panel/frontend && npm ci && npm run build

# sasayaki
cd sasayaki && go build ./... && go vet ./... && go test ./...
```

Compiling is not evidence. A change to the node is verified by running it against a real
Docker daemon in WSL; a change to the panel is verified against the local PostgreSQL 17.
`docs/verify-on-linux.md` lists the handful of things (gVisor, XFS project quota, real
ACME certificates) that only a real Linux host can prove.
