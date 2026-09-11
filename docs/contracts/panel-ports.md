# Panel ports

Binding. See [README.md](README.md).

Fifteen Java packages are being written at the same time by people who will never read
each other's source. This file is every place one of them touches another: the exact
fully-qualified name, the exact signature, what it promises, and who owns each half.

If you need a seam that is not here, add it here first.

---

## 1. The two kinds of port, and why an interface at all

AGENTS.md §2 bans an interface with no implementation and a factory over exactly one
implementation. Every interface below has exactly one production implementation, named
in the table, with an owner. Nothing here is optional, pluggable or swappable, and
nothing here has a second implementation waiting in the wings.

They exist for one of two reasons, and the tables say which:

| Kind | Why it is an interface |
|---|---|
| **Inversion** | The implementation lives in a package that already depends on the one declaring it. Without the inversion the two cannot both compile. |
| **Coordination** | One provider, many consumers, all written in parallel. The interface is the signature the consumers compile against on day one, before the provider exists. |

A seam that is neither is not an interface. It is a plain class with a verb for a name,
and §4 lists those.

---

## 2. Ports

| # | Port | Declared in | Implemented by | Kind |
|---|---|---|---|---|
| 1 | `lhqm.furimeo.wisper.node.NodeConnections` | `node` | `lhqm.furimeo.wisper.grpc.ConnectedNodes` | Inversion |
| 2 | `lhqm.furimeo.wisper.node.NodeSpecSource` | `node` | `lhqm.furimeo.wisper.placement.BuildNodeSpec` | Inversion |
| 3 | `lhqm.furimeo.wisper.jobs.JobQueue` | `jobs` | `lhqm.furimeo.wisper.jobs.DbSchedulerJobQueue` | Coordination |
| 4 | `lhqm.furimeo.wisper.audit.AuditTrail` | `audit` | `lhqm.furimeo.wisper.audit.AuditLogRecorder` | Coordination |
| 5 | `lhqm.furimeo.wisper.org.QuotaGuard` | `org` | `lhqm.furimeo.wisper.org.EnforceQuota` | Coordination |
| 6 | `lhqm.furimeo.wisper.crypto.SecretCipher` | `crypto` | `lhqm.furimeo.wisper.crypto.AesGcmSecretCipher` | Coordination |
| 7 | `lhqm.furimeo.wisper.files.NodeFiles` | `files` | `lhqm.furimeo.wisper.grpc.NodeFileTransfers` | Inversion |
| 8 | `lhqm.furimeo.wisper.files.NodeTerminals` | `files` | `lhqm.furimeo.wisper.grpc.NodeTerminalSessions` | Inversion |
| 9 | `lhqm.furimeo.wisper.stats.NodeLogs` | `stats` | `lhqm.furimeo.wisper.grpc.NodeLogSubscriptions` | Inversion |

The interface files exist in the repository already, fully documented. **Do not change a
signature in one of them without changing this table in the same commit.**

### 2.1 `node.NodeConnections` — reaching a node

```java
public interface NodeConnections {
    boolean isConnected(UUID nodeId);
    List<UUID> connectedNodeIds();
    void send(UUID nodeId, PanelMessage.Builder message);
    CompletableFuture<CommandResult> call(UUID nodeId, PanelMessage.Builder command);
}
```

Also in `node`: `NodeOffline extends RuntimeException`, with `UUID nodeId()`.

Semantics:

- The caller supplies a `PanelMessage.Builder` with its `oneof payload` set and **no**
  `command_id`. The implementation stamps a fresh id, so correlation has one owner.
- `send` is for the messages with no reply: `ApplySpec`, `ReconcileNow`, `LogRequest`,
  `StopLogStream`. It throws `NodeOffline` when there is no stream. A caller pushing a
  spec catches it and does nothing — the reconnect path resends the whole spec, so an
  undeliverable spec is not a lost spec.
- `call` is for the commands that produce a `CommandResult`: `StartBuild`, `RunBackup`,
  `RestoreBackup`, `ProvisionDatabase`, `RotateDatabasePassword`, `DropDatabase`,
  `DrainNode`, `UpgradeNode`. It never applies a timeout — the sensible one differs by
  two orders of magnitude between rotating a password and restoring a database. Use
  `orTimeout`. The implementation drops its pending entry whenever the future completes,
  including by timeout.
- The future completes exceptionally with `NodeOffline` if the stream is absent or ends
  first. A `CommandResult` with `ok == false` is a normal completion: the node answered.
- Thread-safe. A `StreamObserver` is not safe for concurrent use, so the implementation
  serialises writes per node.

`StartTerminal` is a `PanelMessage` too, but it is not sent through this port — use
`NodeTerminals`, which also joins the stream the node dials back.

### 2.2 `node.NodeSpecSource` — building the desired state

```java
public interface NodeSpecSource {
    NodeSpec buildSpec(UUID nodeId, long generation, Instant issuedAt);
    List<UUID> nodesHosting(UUID serviceId);
}
```

`node` owns the counter and the delivery; `placement` owns the content, because it is the
only package that may read across `service`, `volume`, `domain`, `env_var`, `secret`,
`cron_task` and `managed_database` to assemble it. The row-by-row mapping is
[`node-spec.md`](node-spec.md).

- The generation is passed in, already bumped and already written to
  `node.desired_generation`. A spec is never built with a generation that was not
  recorded.
- `issuedAt` is passed in for the same reason: the value on the row and the value on the
  wire are one instant.
- An empty spec is legitimate and means "run nothing". Do not throw for a drained node.
- `nodesHosting` returns at most two in v1 — a `DRAINING` placement and its `ACTIVE`
  replacement can coexist, and both nodes need telling. Empty for a service that has
  never been placed, which is not an error.

### 2.3 `jobs.JobQueue` — background work

```java
public record JobKind<T>(String taskName, Class<T> payloadType) { String domain(); }

public interface JobQueue {
    <T> void enqueue(JobKind<T> kind, String instanceId, T payload);
    <T> void enqueueAt(JobKind<T> kind, String instanceId, T payload, Instant when);
    <T> boolean enqueueIfAbsent(JobKind<T> kind, String instanceId, T payload, Instant when);
    boolean cancel(JobKind<?> kind, String instanceId);
    default String uniqueInstanceId();
}
```

Also in `jobs`: `JobAlreadyQueued extends RuntimeException`.

- **Every method requires an active transaction and throws `IllegalStateException`
  without one.** A job and the row that justifies it commit together or not at all; that
  is the entire reason the queue is PostgreSQL. This is the behaviour the port exists to
  make checkable, and the second reason is containment: `com.github.kagkarlsson` is
  imported in `jobs` and in no other package.
- `instanceId` is the primary key half that makes an enqueue idempotent. Use the id of
  the row the job acts on. `uniqueInstanceId()` is only for work with no subject.
- `enqueue` throws `JobAlreadyQueued`; `enqueueIfAbsent` returns `false`. A customer
  pressing "deploy" twice wants the first; a poller finding the same backup due twice
  wants the second.
- `cancel` returns `false` for a job a worker already holds. A running job is stopped by
  cancelling its work, not by deleting its row.

`JobKind` constants live next to the `Task` bean that answers them, so the task name is
written exactly twice and both writings are in one file. Names are
`<domain>-<verb>`, validated by the record:

| Task name | Payload | Owner |
|---|---|---|
| `deploy-run` | `DeploymentJob(UUID deploymentId)` | `deploy` |
| `deploy-publish` | `PublishJob(UUID deploymentId)` | `deploy` |
| `domain-verify` | `DomainCheckJob(UUID domainId)` | `domain` |
| `node-sweep-heartbeats` | `Void` | `node` |
| `database-provision` | `ProvisionJob(UUID managedDatabaseId)` | `database` |
| `backup-run` | `BackupJob(UUID backupId)` | `backup` |
| `backup-schedule` | `Void` | `backup` |
| `backup-prune` | `Void` | `backup` |
| `restore-run` | `RestoreJob(UUID restoreRunId)` | `backup` |
| `stats-roll-up` | `Void` | `stats` |
| `stats-sweep-samples` | `Void` | `stats` |
| `files-sweep-uploads` | `Void` | `files` |

### 2.4 `audit.AuditTrail` — the record of what happened

```java
public interface AuditTrail { void record(AuditEntry entry); }

public record AuditEntry(AuditActor actor, String action, AuditTarget target,
                         AuditOutcome outcome, UUID organizationId, String detail) {
    static AuditEntry succeeded(...); static AuditEntry failed(...); static AuditEntry denied(...);
}

public record AuditActor(AuditActorKind kind, UUID accountId, UUID apiTokenId, UUID nodeId,
                         String label, String remoteAddress, String userAgent, String requestId) {
    static AuditActor account(UUID accountId, String email, HttpServletRequest request);
    static AuditActor apiToken(UUID accountId, UUID apiTokenId, String name, HttpServletRequest request);
    static AuditActor node(UUID nodeId, String nodeName, String remoteAddress);
    static AuditActor system(String label);
}

public record AuditTarget(String kind, UUID id, String label) {
    static AuditTarget of(String kind, UUID id, String label);
    static AuditTarget unidentified(String kind, String label);
}

public enum AuditActorKind { ACCOUNT, API_TOKEN, NODE, SYSTEM }
public enum AuditOutcome   { SUCCEEDED, FAILED, DENIED }
```

- Called by **every state-changing action in the panel** (AGENTS.md §5), including the
  ones that were refused. `DENIED` is the entry an incident is reconstructed from.
- The implementation writes in `REQUIRES_NEW` and never propagates a failure: an audit
  write that could not happen is logged at `ERROR` and the action continues. This is the
  only place in the codebase where a failed write is tolerated, and it is deliberate — a
  panel that refuses to stop a container because it could not append a log line has
  turned its audit trail into an outage.
- The records validate the `audit_log_actor_consistent` and `audit_log_action_shape`
  CHECKs in Java, so the failure names the mistake rather than arriving as a constraint
  violation.
- **`detail` never contains a secret value.** Name the field that changed; do not print
  what it changed to.

`action` vocabulary, dotted and stable, because it is filtered on:

```
account.sign_in          account.sign_out         account.password_change
account.two_factor_enable                         account.two_factor_disable
api_token.create         api_token.revoke
organization.suspend     organization.resume      member.invite   member.remove   member.role_change
plan.assign              quota_override.grant     quota_override.revoke
project.create           project.archive          project.delete
service.create           service.update           service.start   service.stop    service.delete
env_var.set              env_var.delete           secret.set      secret.delete
volume.create            volume.resize            volume.delete
cron_task.create         cron_task.update         cron_task.delete
deployment.start         deployment.cancel        deployment.rollback
domain.add               domain.verify            domain.remove
database.create          database.rotate_password database.delete
backup.create            backup.run               backup.delete   backup.restore
restore_point.delete
node.create              node.enrol               node.token_issue  node.token_revoke
node.drain               node.upgrade             node.suspend    node.delete
files.upload             files.delete             files.move      files.chmod
files.archive            files.extract            files.create_directory
files.path_escape
terminal.open            terminal.close
job.retry                job.discard
```

`audit.AuditAction.ALL` is this list in Java, in this order. It exists for the two jobs a
bare literal cannot do - filling the filter on `/admin/audit` with the actions that exist
rather than the ones that happen to be in the table today, and refusing a filter value
that is not one of them. The database only enforces the *shape*
(`audit_log_action_shape`), so adding an action here without adding it to that list, or
the other way round, is how the two drift apart.

### 2.5 `org.QuotaGuard` — limits

```java
public interface QuotaGuard {
    void require(UUID organizationId, QuotaResource resource, long amount);
    QuotaAllowance allowanceFor(UUID organizationId, QuotaResource resource);
    List<QuotaAllowance> allowances(UUID organizationId);
}

public enum QuotaResource { PROJECT, SERVICE, DOMAIN, MANAGED_DATABASE, CRON_TASK, MEMBER,
        API_TOKEN, VOLUME_BYTES, MEMORY_BYTES, CPU_MILLICORES, BACKUP_BYTES, RESTORE_POINT,
        DEPLOYMENTS_PER_DAY }

public record QuotaAllowance(QuotaResource resource, long limit, long used, QuotaSource source) {
    long remaining(); boolean permits(long amount);
    enum QuotaSource { ORGANIZATION_OVERRIDE, PLAN, UNSET }
}

public class QuotaExceeded extends RuntimeException {
    UUID organizationId(); QuotaResource resource(); long requested(); QuotaAllowance allowance();
    String message();
}
```

Resolution order: an unexpired `quota_override`, then the plan's `quota`, then **zero**.
Never unlimited. Reading a missing row as "no limit" turns forgetting to seed a plan into
an unmetered platform.

- `require` goes in the use-case, inside the write transaction, before the insert. Not in
  the controller — the form route and the API route would each need a copy.
- `amount` is `1` for a count and a byte figure for an amount. On a resize, pass the
  increase and nothing at all when it shrinks.
- A suspended organization is refused every resource. Suspension stops the panel writing
  anything new; it does not stop a customer's containers.
- Catch `QuotaExceeded` in the controller, write `InertiaFlash.failure(flash, e.message())`,
  redirect back, and record the attempt with `AuditOutcome.DENIED`.

> A note on the package: `lhqm.furimeo.wisper.org` shadows the top-level `org` package
> for *qualified names written inline*. `import org.springframework.…;` resolves
> normally; `org.springframework.util.StringUtils.hasText(x)` written out in a method
> body inside this package does not. Always import.

### 2.6 `crypto.SecretCipher` — encryption at rest

```java
public interface SecretCipher {
    String encrypt(String plaintext);
    String decrypt(String envelope);
    int currentKeyVersion();
}

public record SecretEnvelope(int keyVersion, byte[] nonce, byte[] ciphertext) {
    static final int NONCE_BYTES = 12;
    static boolean looksLikeEnvelope(String stored);
    static SecretEnvelope parse(String stored);
    String text();
}
```

One format, `v<keyVersion>.<base64url nonce>.<base64url ciphertext>`, matching the
`*_is_envelope` CHECK on every column that holds one. `SecretEnvelope` is complete and
tested; `AesGcmSecretCipher` is AES-256-GCM over it.

Columns: `account.totp_secret`, `service.webhook_secret`,
`service.repository_credential`, `secret.value`, `database_engine.admin_password`,
`managed_database.db_password`, `backup_destination.secret_access_key`,
`backup_destination.archive_passphrase`.

Never through this interface: anything the panel only compares — `account.password_hash`
(BCrypt), `account_recovery_code.code_hash`, `session.session_id_hash`,
`node.credential_hash`, `node_enrollment_token.token_hash`, `api_token.token_hash`. And
never a TLS private key: the node owns its certificates and the key never leaves it.

New configuration namespace, declared as a record in `crypto`:

```
wisper.crypto.current-key-version   int,  default 1
wisper.crypto.keys                  Map<Integer,String>, base64 32-byte keys, no default
```

No default for the keys. A cipher that invents one encrypts everything with a key that is
in the source tree.

### 2.7 `files.NodeFiles` — the file manager's back end

```java
public interface NodeFiles {
    FileEvent call(UUID nodeId, FileRequest request);
    void stream(UUID nodeId, FileRequest request, Consumer<FileEvent> events);
    void cancel(UUID nodeId, String requestId);
}
```

Also in `files`: `FileOperationFailed extends RuntimeException`, exposing
`FileError error()`, `FileErrorCode code()`, `String detail()`, `String path()` and
`boolean isTraversalAttempt()`.

- Two methods, not fourteen. The operations stay in the `oneof` in `files.proto`; what
  differs between them is only how many events answer, so that is the distinction.
- `call` for everything with one terminal answer. `stream` for `read`, which is many
  `FileChunk`s.
- The sink is called in order on the calling thread and must not block for long: one
  `FileOp` stream carries every operation for that node.
- **The panel never sends an absolute path.** Every request names a `root_id` from the
  node's current spec and a path relative to it.
- `FILE_ERROR_CODE_PATH_ESCAPES_ROOT` is recorded as `files.path_escape` with
  `AuditOutcome.DENIED` and answered flatly. It is never a customer's typo.

### 2.8 `files.NodeTerminals` — the web shell

```java
public interface NodeTerminals {
    TerminalSession open(UUID nodeId, StartTerminal request, Consumer<byte[]> fromNode,
                         Duration attachTimeout);
}

public interface TerminalSession extends AutoCloseable {
    String sessionId(); String containerId(); int columns(); int rows();
    void send(byte[] data);
    void resize(int columns, int rows);
    boolean isFinished(); int exitCode(); String exitReason();
    @Override void close();
}
```

Also in `files`: `TerminalUnavailable extends RuntimeException`.

- `open` hides the two-step handshake: the panel puts `StartTerminal` on the control
  stream, the node dials back a `Terminal` call whose first frame is `TerminalAttached`,
  and the implementation joins them by session id.
- The session id is minted by the caller before the browser connects, so the SSE
  connection, the gRPC stream and the audit entry share one id.
- `send`, `resize` and the exit accessors are the frame types in `terminal.proto` and
  there is no "write whatever" method. That absence is the fix for the predecessor's
  unframed copy.
- `idle_timeout_seconds` and `max_duration_seconds` on the request are not optional.
- `close()` throws nothing: it is called from a `finally` and from an SSE callback.

### 2.9 `stats.NodeLogs` — log feeds

```java
public interface NodeLogs {
    LogSubscription follow(UUID nodeId, LogRequest request, Consumer<LogChunk> chunks);
}

public interface LogSubscription extends AutoCloseable {
    String streamId(); boolean isFinished(); long droppedBytes();
    @Override void close();
}
```

- One path for all four `LogSource` values. `deploy` is the second consumer, for build
  output, and additionally persists each line into `deployment_log` keyed by
  `(deployment_id, sequence)` — that unique index is what makes a chunk redelivered after
  a reconnect a rejected duplicate rather than a doubled line.
- Chunks, not lines. A chunk boundary can fall inside a multi-byte character.
- Register `close()` on the `SseEmitter`'s completion, timeout **and** error callbacks.
  All three fire for a customer who closed a tab, and only one of them looks like
  success.
- `droppedBytes() > 0` is shown to the customer. A gap they are told about can be acted
  on; a silent one cannot.

---

## 3. What the `grpc` package calls inward

`grpc` is an adapter: it parses frames, authenticates the node, and hands the content to
the package that owns the table. It writes no domain table itself. Each class below is a
plain class with a verb for a name, owned by the package it lives in, and `grpc` will not
compile until it exists with this signature.

| Frame | Class | Signature |
|---|---|---|
| every RPC's metadata | `node.AuthenticateNode` | `AuthenticatedNode byCredential(String credential, String remoteAddress)` |
| `Enroll` | `node.EnrolNode` | `EnrollResponse enrol(EnrollRequest request, String remoteAddress, String panelCertificateSha256)` |
| `NodeHello` | `node.RecordHandshake` | `void accept(UUID nodeId, NodeHello hello, String remoteAddress)` |
| `Heartbeat` | `node.RecordHeartbeat` | `void accept(UUID nodeId, Heartbeat heartbeat, Instant receivedAt)` |
| stream end | `node.RecordDisconnect` | `void accept(UUID nodeId, String reason)` |
| `NodeEvent` | `node.RecordNodeEvent` | `void accept(UUID nodeId, NodeEvent event)` |
| `SpecApplied` | `node.PublishNodeSpec` | `void markApplied(UUID nodeId, SpecApplied applied)` |
| `StatusBatch.workloads` | `placement.RecordWorkloadStatus` | `void accept(UUID nodeId, List<WorkloadStatus> statuses, Instant observedAt, boolean partial)` |
| `StatusBatch.routes` | `domain.RecordRouteStatus` | `void accept(UUID nodeId, List<RouteStatus> statuses, Instant observedAt)` |
| `StatusBatch.databases` | `database.RecordDatabaseStatus` | `void accept(UUID nodeId, List<DatabaseStatus> statuses, Instant observedAt)` |
| `StatusBatch.cron` | `service.RecordCronStatus` | `void accept(UUID nodeId, List<CronStatus> statuses, Instant observedAt)` |
| `StatSample` | `stats.IngestStatSample` | `void accept(UUID nodeId, StatSample sample)` |
| `LogChunk` (`BUILD`) | `deploy.AppendDeploymentLog` | `void append(UUID deploymentId, LogChunk chunk)` |
| `BuildCompleted` | `deploy.CompleteDeployment` | `void accept(UUID deploymentId, BuildCompleted result)` |
| `BackupCompleted` | `backup.CompleteBackup` | `void accept(UUID backupId, BackupCompleted result)` |
| `RestoreCompleted` | `backup.CompleteRestore` | `void accept(UUID restoreRunId, RestoreCompleted result)` |
| `DatabaseProvisioned` | `database.CompleteProvision` | `void accept(UUID managedDatabaseId, DatabaseProvisioned result)` |
| `DrainReport` | `node.CompleteDrain` | `void accept(UUID nodeId, DrainReport report)` |
| `UpgradeResult` | `node.CompleteUpgrade` | `void accept(UUID nodeId, UpgradeResult result)` |

`node.AuthenticatedNode` is a record in `node`:

```java
public record AuthenticatedNode(UUID nodeId, String name, String lifecycle, long desiredGeneration) {}
```

Rules `grpc` must not break:

1. It writes only the node-owned columns listed in [`schema.md`](schema.md) §2, and it
   writes them through the classes above. No controller and no use-case may touch one.
2. `partial == true` on a `StatusBatch` means Docker did not answer. Nothing may be
   concluded to have gone away. "Cannot see it" is not "does not exist".
3. `applied_generation > node.desired_generation` is impossible and means two panels are
   driving one node. Suspend it and record `node.suspend`; do not publish to it again.
4. A protocol version it cannot speak ends the stream with `FAILED_PRECONDITION` before
   any frame is interpreted.

---

## 4. Published classes that cross a package boundary

These are ordinary classes, not ports, because nothing has to be inverted and nothing has
two implementations. They are here because their signature is a contract anyway.

| Class | Signature | Called by |
|---|---|---|
| `node.PublishNodeSpec` | `void toNode(UUID nodeId, String reason)` | `placement`, `node`, `database` |
| | `void forService(UUID serviceId, String reason)` | `service`, `deploy`, `domain`, `files`, `backup` |
| | `void markApplied(UUID nodeId, SpecApplied applied)` | `grpc` |
| `placement.ChooseNode` | `UUID forService(UUID serviceId)` | `deploy`, `service` |
| `placement.ReleasePlacement` | `void forService(UUID serviceId, String reason)` | `service` |
| `org.ResolveMembership` | `Membership forAccount(UUID accountId, UUID organizationId)` | `project`, `service`, `deploy`, `files`, `database`, `backup` |
| `org.ResolveMembership` | `Membership forService(UUID accountId, UUID serviceId)` | as above |
| `service.LocateService` | `ServiceLocation byId(UUID serviceId)` | `deploy`, `domain`, `files`, `stats`, `backup` |

`PublishNodeSpec.forService` is the single call every write that touches a service or one
of its children ends with. It resolves the affected nodes through
`NodeSpecSource.nodesHosting`, bumps `node.desired_generation` once per node, builds the
spec and sends `ApplySpec`. It swallows `NodeOffline`: a node that is away gets the whole
spec on reconnect.

`org.Membership` and `service.ServiceLocation` are records in their own packages:

```java
public record Membership(UUID organizationId, UUID accountId, MemberRole role) {
    boolean canWrite();   // OWNER, ADMIN, DEVELOPER
    boolean canAdminister();  // OWNER, ADMIN
}
public enum MemberRole { OWNER, ADMIN, DEVELOPER, VIEWER }

public record ServiceLocation(UUID serviceId, UUID projectId, UUID organizationId,
                              UUID nodeId, String slug, String name, ServiceKind kind) {}
```

`ResolveMembership` throws `NotFoundException` when the account is not a member, which is
how "not yours" and "no such thing" produce the same answer (see
[`panel-http.md`](panel-http.md)).

---

## 5. Shared props

Two `SharedPropsContributor` beans, and no more. Every other prop comes from the
controller's model.

| Bean | Package | Adds | Order |
|---|---|---|---|
| `auth.SignedInAccountProps` | `auth` | `account` | default |
| `org.CurrentOrganizationProps` | `org` | `organizations`, `organization` | 20 |

Both write the key even when the value is null or empty, so no page has to check for
`undefined`. Their exact prop shapes are in [`pages.md`](pages.md) §3, together with the
TypeScript declaration that has to match them.

---

## 6. Package map

```
web ────────────────────────────────── depended on by everything, depends on nothing
crypto  jobs  audit ────────────────── capability packages, depend only on web + JDBC
org ──── depends on: crypto, audit, jobs, web
auth ─── depends on: crypto, audit, org, web
project  service  deploy  domain  files  stats  database  backup
        depend on: node (ports), org, audit, jobs, crypto, web, and each other as §4 says
placement ── depends on: node, service, domain, database, files, org, audit
node ─────── depends on: crypto, audit, jobs, org, web. Declares NodeConnections and
             NodeSpecSource; depends on neither grpc nor placement.
grpc ─────── depends on everything. Nothing depends on grpc.
migration ── depends on nothing.
```

Two arrows are forbidden and everything above is arranged to avoid them:

- `node → grpc` — broken by `NodeConnections`.
- `node → placement` — broken by `NodeSpecSource`.

### Packages this file adds

AGENTS.md §3.3 lists the panel's packages as `auth, org, project, service, deploy,
domain, node, placement, database, backup, files, stats, grpc, web, migration`. Three
packages in the tables above are not on that list and have to be, because the schema
already has the tables and the columns that need them:

| Package | Exists for | Evidence it is required |
|---|---|---|
| `crypto` | the encryption envelope | eight `*_is_envelope` CHECK constraints across V21, V24, V25, V26 |
| `jobs` | the db-scheduler seam | `scheduled_tasks` (V1); design §13.3 groups "files+stats+jobs" |
| `audit` | the audit trail | `audit_log` (V33); AGENTS.md §5 requires an entry per state change |

Each is a feature cluster with one job, not a dumping ground, and none of them is a
banned name.
