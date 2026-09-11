# NodeSpec

Binding. See [README.md](README.md).

How the panel turns database rows into a `NodeSpec`, what a generation means, and what
the daemon is allowed to say back. Two components in two languages read this file: the
panel's `placement` package builds the document, and every package in sasayaki consumes
it.

The message definitions are in [`workload.proto`](../../proto/wisper/v1/workload.proto)
and [`database.proto`](../../proto/wisper/v1/database.proto). This file is everything a
compiler cannot check: which row produces which field, in what order, and what a number
means.

---

## 1. The one rule

> **A spec is the whole desired state of one node, always. There is no delta anywhere in
> this contract.**

Everything below follows from that. A node that has been unreachable for a week is caught
up by one document. A node that reconnects three times in a second is handed the same
document three times and converges to the same place. Nothing has to be replayed in
order, nothing has to be acknowledged before the next thing is sent, and there is no
queue to drain.

The corollary is the part that is easy to get wrong: **omission is deletion**. A workload
that is not in the spec is removed from the node, together with its container and its
logs. A route that is not in the spec is withdrawn from the edge. A grant that is not in
the spec has its role dropped. Anything the panel wants kept but not running is sent with
`desired_state = DESIRED_STATE_STOPPED`, never left out.

The second corollary: **the spec must be a pure function of the rows**. Same rows, same
bytes. `BuildNodeSpec` introduces no clock reading, no random value and no map iteration
order, because the node hashes the encoded spec to decide whether anything changed, and a
document that differs run to run would make every pass look like drift. §4 lists the
orderings that make this true; breaking one of them is a real bug even though nothing
fails.

---

## 2. Generation

### What it is

`node.desired_generation` is a `bigint` on the node's row, owned by the panel, strictly
increasing, never reused and never reset. It is the only ordering the two sides share.

`node_status.applied_generation` is what the node says it has converged to. It starts at
`-1`, the constant `NodeStatus.NEVER_REPORTED`, which is not a generation any spec can
carry and therefore cannot be confused with one.

### Who bumps it, and when

`node.PublishNodeSpec` and nothing else.

```java
Instant at = Instant.now();
Long generation = nodes.bumpGeneration(nodeId, at);   // UPDATE ... RETURNING, one statement
NodeSpec spec = specSource.buildSpec(nodeId, generation, at);
```

Three properties of that sequence are load-bearing:

1. **The counter is taken in one statement.** A read-then-write increment lets two
   publishers landing together read the same value and send two different documents under
   one generation. The node would converge on whichever arrived second while reporting the
   number both claimed, and the panel would believe it was converged.
2. **`generation` and `issuedAt` are passed in, not discovered.** The value written to
   `node.desired_generation` and the value on the wire are the same value, and
   `NodeSpec.issued_at` equals `node.spec_updated_at` exactly. The node compares
   `issued_at` with its own clock to detect skew; a timestamp taken a moment later inside
   the builder would make that comparison measure the panel's latency instead.
3. **The frame goes out after the transaction commits.** `PublishNodeSpec` registers an
   `afterCommit` synchronisation. Sending inside the transaction means a rollback leaves
   the node holding a generation the panel no longer believes it published - and the next
   status report then looks like two panels driving one machine, which suspends a
   perfectly healthy node.

### What the node does with it

| Offered generation | Node's answer |
|---|---|
| Greater than stored | Accept, store, reconcile. |
| Equal to stored | **Accept, store, reconcile again.** The panel resends the whole spec on every reconnect and the bytes may differ even when the number does not; and the disk may have drifted since the last pass. |
| Less than stored | Refuse. `SpecApplied{accepted: false, rejected_reason: "..."}`. A retried frame arriving late must not roll a machine backwards. |

`state.Store.SaveSpec` implements exactly this and returns `ErrSupersededGeneration` for
the third row.

### What the panel does with what comes back

`applied_generation` arrives in three places - `NodeHello`, every `Heartbeat` and every
`StatusBatch` - and is written by `PublishNodeSpec.markApplied` and `RecordHeartbeat`
using `GREATEST(existing, incoming)`. Two acknowledgements can cross on a reconnect, and
applying the older one would make the node look like it had gone backwards, which the
next drift check would answer by republishing, forever.

| Comparison | Meaning | Panel's action |
|---|---|---|
| `applied == desired` | Converged. | Nothing. |
| `applied < desired` | In flight, or the node is behind. | Publish on the next handshake; the reconcile loop will get there anyway. |
| `applied > desired` | **Impossible.** Two panels are driving one node, or a database was restored from a backup older than the node's memory. | Suspend the node with `NodeSuspensionReason.DUPLICATE_FINGERPRINT`, record `node.suspend`, and publish nothing further to it. Its containers keep running. |

A refused spec does not move the column. `markApplied` passes `NEVER_REPORTED` for a
rejection, which loses to whatever is already stored under `GREATEST`, and writes
`node_status.reconcile_error` with the reason.

### Reconnect

The node dials, sends `NodeHello` with its `applied_generation`, the panel answers
`PanelHello` with `current_generation`, and then sends **`ApplySpec` with the full spec**
whatever those two numbers are. There is no "you are already up to date, here is nothing"
path, because the cost of one redundant document is a few kilobytes and the cost of
getting the comparison wrong is a node that never converges.

`PanelHello.current_generation` exists so a node on a flapping tunnel can skip a redundant
*apply* - it may compare, store and answer `SpecApplied` without re-running the reconcile
pass. It may never use it to skip *receiving*.

### Publishing is never a failure

`PublishNodeSpec` swallows `NodeOffline`. A node that is away is not a lost spec: the
whole document goes out the moment it reconnects. Nothing is queued, and no caller has to
handle "the node was not there".

Nor is it published to a node that is suspended or retired - `NodeLifecycle.acceptsControl()`
is false for those. Suspension means the panel has stopped being an author of that
machine's state, and quietly continuing to publish would make it one again.

---

## 3. Rows to fields

`placement.BuildNodeSpec` composes; each piece below is loaded or built by one class next
to it. Rows are read in **three statements plus one per part**, never one per service: a
generation is cut whenever anything changes anywhere on a node, which on a busy fleet is
constantly.

### 3.1 The envelope

| Field | Source |
|---|---|
| `generation` | The argument. Already written to `node.desired_generation`. |
| `issued_at` | The argument. Equals `node.spec_updated_at`. |
| `workloads` | `placement` join `service`, §3.2. |
| `routes` | `domain`, §3.6. |
| `engines` | `database_engine`, §3.7. |
| `databases` | `managed_database` join `database_engine`, §3.8. |
| `cron` | `cron_task`, §3.9. |
| `file_roots` | `volume` plus one synthetic staging root, §3.10. |
| `retention` | `wisper.placement.*` and `service.keep_releases`, §3.11. |
| `reconcile_interval_seconds` | `wisper.node.reconcile-interval`, default 15s. Sent with the spec rather than compiled into the daemon so the interval can be tuned without an upgrade. |

An empty spec is legitimate and means "run nothing". It is exactly what a drained node
should be told, so a node holding nothing produces empty lists and not an exception.

### 3.2 `Workload`

One per **live** placement (`PLANNED`, `ACTIVE` or `DRAINING`) whose service is not
archived. An archived service is one the customer has put away; leaving it in a spec means
it keeps running and keeps costing them.

| Field | Source | Notes |
|---|---|---|
| `id` | `service.id` as a string | The workload id **is** the service id. Container names, volume directories and log streams derive from it, so it never changes. |
| `kind` | `service.kind` | `APP` -> `WORKLOAD_KIND_APP`, `SITE` -> `WORKLOAD_KIND_SITE`. |
| `name` | `service.slug` | Used for the container name, so `docker ps` is readable without resolving ids. |
| `image` | `service.image` | APP only; empty for a site. |
| `image_digest` | the current deployment's `image_digest`, else `service.image_digest`, else empty | The deployment's wins: it is what was actually built. Empty means the node resolves the tag and reports what it got. |
| `entrypoint`, `command` | `service.entrypoint`, `service.command` (`text[]`) | argv, never a shell string. |
| `working_dir` | `service.working_dir` | |
| `env` | `env_var` + `secret`, §3.3 | |
| `limits` | §3.4 | |
| `mounts` | `volume`, §3.5 | APP only. |
| `ports` | `service.container_port` | One `PortBinding` with `host_port = 0` when the column is set, none when it is null. |
| `restart` | `service.restart_policy` | `ON_FAILURE` also sets `max_retries = 5`. |
| `runtime` | `service.runtime_isolation` | `RUNSC` -> `CONTAINER_RUNTIME_RUNSC`, `RUNC` -> `CONTAINER_RUNTIME_RUNC`. |
| `desired_state` | §below | |
| `health_check` | `service.health_check_path`, §3.4 | |
| `tenant_network` | `wisper.placement.tenant-network-prefix` + `project.organization_id` | Default prefix `wisper-tenant-`. One Docker network per tenant; two customers on one node must not reach each other by address. |
| `read_only_rootfs` | not set in v1 | No column exists. Left false rather than guessed: a workload that cannot write where it expects to looks like a broken image. |
| `user` | not set in v1 | User-namespace remap on the daemon covers the threat this field addresses. |
| `stop_grace_seconds` | `wisper.placement.stop-grace`, default 30s | |
| `release_id` | the current deployment's id as a string, else empty | SITE: which directory under `releases/` the `current` symlink must point at. |
| `site` | `SiteOptions`, §3.12 | SITE only. |

**`desired_state`** is not simply `service.desired_state`:

```
placement.state == DRAINING            -> DESIRED_STATE_STOPPED
service.desired_state == RUNNING       -> DESIRED_STATE_RUNNING
otherwise                              -> DESIRED_STATE_STOPPED
```

A draining binding is published as *stopped*, not omitted. Omission means "remove this",
which would take the container and its logs with it while the replacement is still coming
up. The row is released - and the container removed - only once the move has finished.

### 3.3 `EnvVar`

`env_var` and `secret` are two tables and one list. A process does not have two
environments.

| Field | Source |
|---|---|
| `name` | `env_var.name` / `secret.name` |
| `value` | `env_var.value` verbatim; `secret.value` decrypted through `crypto.SecretCipher` |
| `secret` | false for `env_var`, true for `secret` |

`placement.LoadServiceEnvironment` is **the only place in the panel that decrypts a
secret**. The value is read, unsealed and put straight on the wire to the node that needs
it; nothing logs it, returns it to a browser or keeps it.

The `secret` flag travels with the value so the node knows not to put it in a log line, an
error message or any status. Guessing from the name is what a platform does when it wants
`DATABASE_PASSWORD` redacted and `DB_PW` not.

Sorted by name, and the sort is part of the contract - see §4.

### 3.4 `ResourceLimits` and `HealthCheck`

| Field | Source | Notes |
|---|---|---|
| `nano_cpus` | `service.cpu_millicores * 1_000_000` | **Docker's NanoCPUs.** One full core is `1000000000`. It is a hard ceiling and **not** `CPUShares * 1000`, which is a relative scheduling weight. The predecessor conflated the two and a single busy workload could take a whole machine while the panel showed it politely limited. |
| `memory_bytes` | `service.memory_bytes` | |
| `memory_swap_bytes` | equal to `memory_bytes` | Equal disables swap. A container that swaps makes the whole machine grind; one that is killed fails a single workload. |
| `pids_limit` | `service.pids_limit` | A fork bomb inside gVisor is still a fork bomb for the host process table. |
| `disk_bytes` | `service.disk_bytes` | XFS project quota. Advisory where `node_status.quota_enforceable` is false. |
| `nofile_limit` | 65536 | Node and Go servers open a lot of sockets; the distribution default is low enough that running out looks like a networking bug. |

A **site** gets `disk_bytes` and nothing else. There is no process to limit, and sending a
memory ceiling would suggest the node should enforce something about a directory of files.

`HealthCheck` is produced only when the customer typed a path **and** the service has a
container port:

```
test:                  ["wget", "--quiet", "--tries=1", "--spider",
                        "http://127.0.0.1:<container_port><path>"]
interval_seconds:      service.health_check_interval_seconds
timeout_seconds:       wisper.placement.health-check-timeout        (10s)
retries:               wisper.placement.health-check-retries        (3)
start_period_seconds:  wisper.placement.health-check-start-period   (20s)
```

`test` is argv run inside the container; the node adds Docker's own `CMD` marker. A check
nobody asked for that fails because the image has no `wget` would mark a healthy service
unhealthy, which is worse than no check - hence both conditions.

### 3.5 `Mount`

One per `volume` row, APP only.

| Field | Source |
|---|---|
| `volume_id` | `volume.id` as a string |
| `kind` | always `MOUNT_KIND_VOLUME` in v1 |
| `target` | `volume.mount_path` |
| `read_only` | `volume.read_only` |
| `quota_bytes` | `volume.size_bytes` |

**There is no path in the spec.** The node resolves
`/var/lib/wisper/volumes/<service-id>/<volume-id>` itself. A path that arrives over the
network and reaches a filesystem call is the bug class this platform exists to avoid, and
the id-based layout means renaming a service never moves a directory (design §11.1).

### 3.6 `Route`

One per `domain` row whose `service_id` is on this node.

| Field | Source | Notes |
|---|---|---|
| `domain` | `domain.hostname` | Lower-cased IDNA A-label, globally unique. |
| `workload_id` | the target service's id | |
| `port` | `domain.target_port`, else `service.container_port`, else 0 | A site has neither and needs neither: the edge serves the release directory from disk. |
| `tls_mode` | `domain.tls_mode` | `OFF` -> `TLS_MODE_DISABLED`; `ON_DEMAND` **and** `STATIC` -> `TLS_MODE_ON_DEMAND`. |
| `path_prefix` | not set in v1 | No column. |
| `force_https` | `domain.force_https` **and** `tls_mode == ON_DEMAND` **and** `verification_state == 'VERIFIED'` | |

`STATIC` collapses onto on-demand issuance because in v1 the node owns every certificate
and there is nowhere to upload one: `certificate` holds metadata and, deliberately, no
private key.

`force_https` is off while TLS is off and off while the hostname is still being validated.
A domain whose DNS has not arrived cannot get a certificate, and redirecting it to a port
that will fail the handshake turns "not set up yet" into "broken".

### 3.7 `DatabaseEngineSpec`

One per `database_engine` row on this node with `desired_state = 'RUNNING'`.

| Field | Source |
|---|---|
| `engine` | `database_engine.engine`, `POSTGRES` / `MYSQL` |
| `image` | `database_engine.image` - pinned by the panel; the node never picks a version |
| `listen_port` | `database_engine.port` |
| `admin_username` | `database_engine.admin_username` |
| `admin_password` | `database_engine.admin_password`, decrypted |
| `data_volume_id` | `database_engine.id` as a string - **the id, not `data_path`** |
| `nano_cpus` | `wisper.placement.database-engine-millicores * 1_000_000` (default 2000 millicores) |
| `memory_bytes` | `wisper.placement.database-engine-memory` (default 2GB) |
| `max_connections` | `wisper.placement.database-engine-max-connections` (default 200) |

The three settings are the same numbers `placement.LoadNodeCapacity` subtracts from the
node, so the ceiling the node applies and the space the scheduler believes is gone are one
figure.

An `engine` value the panel does not recognise throws. A spec that quietly said
`DATABASE_ENGINE_UNSPECIFIED` would have the node create nothing and report nothing wrong.

### 3.8 `DatabaseGrant`

One per `managed_database` row on any engine this node hosts whose `state <> 'DELETING'`.
A grant on its way out is left out of the spec, which is how the node comes to remove it.

| Field | Source |
|---|---|
| `id` | `managed_database.id` |
| `engine` | `database_engine.engine` |
| `database_name` | `managed_database.name` |
| `username` | `managed_database.db_username` |
| `quota_bytes` | `managed_database.quota_bytes` |
| `dedicated_instance` | `database_engine.mode == 'DEDICATED'` |
| `encoding` | `managed_database.db_charset`, empty for null |

The password is **not** here. It reaches the node once, in `ProvisionDatabase` or
`RotateDatabasePassword`, and is never resent - a secret on the wire on every generation
would be a secret in every packet capture. A node that lost its disk rebuilds the account
from the grant and reports `last_error`; the panel then re-issues a rotation.

### 3.9 `CronEntry`

One per enabled `cron_task` belonging to an **app** on this node. A static site has no
process to run a command inside. A disabled entry keeps its row, its history and its
schedule; switching one off must not lose what it did last week.

| Field | Source |
|---|---|
| `id` | `cron_task.id` |
| `workload_id` | `cron_task.service_id` - the container the command runs in |
| `schedule` | `cron_task.schedule`, five-field cron |
| `timezone` | `cron_task.timezone`, empty means UTC |
| `command` | `cron_task.command` (`text[]`), argv |
| `timeout_seconds` | `cron_task.timeout_seconds` |
| `allow_overlap` | `concurrency_policy == ALLOW` |

`FORBID` and `REPLACE` both arrive as "do not overlap": the wire has one boolean, and
skipping is the safe reading until the contract grows a third state.

The schedule is evaluated **on the node**. `cron_task.next_run_at` is a panel-side display
value the node never reads.

### 3.10 `FileRoot`

This list is the *entire* surface a customer has on a node's filesystem. There is no SSH,
no SFTP and no WebDAV.

| Kind | One per | `id` | `writable` |
|---|---|---|---|
| `FILE_ROOT_KIND_UPLOAD_STAGING` | node - always present, first | `wisper.placement.upload-staging-root-id`, default `upload-staging` | true |
| `FILE_ROOT_KIND_VOLUME` | `volume` row | `volume.id` | `!volume.read_only` |
| `FILE_ROOT_KIND_SITE` | site service | the service id | **false** |

- The staging root is present even on a node holding nothing: a spec is published when a
  service is placed and `deploy` pushes the archive immediately afterwards. Its id must
  equal `wisper.deploy.staging-root-id`; both settings share the default.
- A site root is never writable. An edit made in place would be silently reverted by the
  next deployment, which is worse than not being able to make it.
- `label` is what the breadcrumb shows: `"<slug> / <volume name>"` for a volume,
  `"<slug> releases"` for a site.
- `quota_bytes` is `volume.size_bytes` for a volume and `service.disk_bytes` for a site.

Every file operation names a root by id and a path relative to it. The panel never learns,
and never sends, an absolute path.

### 3.11 `RetentionPolicy`

One policy for the whole machine; the wire has no room for a second.

| Field | Source |
|---|---|
| `keep_releases` | `max(wisper.placement.keep-releases, max(service.keep_releases) over the sites on this node)` |
| `keep_build_workspaces` | `wisper.placement.keep-build-workspaces` (2) |
| `container_log_max_bytes` | `wisper.placement.container-log-max-bytes` (64MB) |
| `container_log_max_files` | `wisper.placement.container-log-max-files` (3) |
| `orphan_upload_ttl_seconds` | `wisper.placement.orphan-upload-ttl` (24h) |

`keep_releases` takes the **largest** number any site on the node is entitled to. The
smallest, or a fixed default, would silently delete releases a customer was promised they
could roll back to. Keeping too many costs disk; keeping too few costs a rollback that
cannot happen. Panel-side pruning in `deploy` still trims the rows; this is the node's own
floor underneath it, for when nobody has told it anything.

### 3.12 `SiteOptions`

Every value is the safe default, because the schema has no columns for these yet:
`spa_fallback = false`, `index_file` empty (the node uses `index.html`), `not_found_file`
empty, `directory_listing = false`. An accidental directory listing is a data leak, so
that one is not a default anybody gets by omission - it is stated out loud.

---

## 4. Determinism

The node hashes the deterministic protobuf encoding of the spec (`state.Store.SaveSpec`)
and compares it with the one it last converged to. That comparison is only meaningful if
the same rows always produce the same bytes, so every list has a fixed order and every
builder is a pure function of what it is handed.

| List | Ordered by |
|---|---|
| `workloads` | `service.id` |
| `Workload.env` | variable name, plain values and secrets interleaved |
| `Workload.mounts` | `volume.mount_path` (unique per service) |
| `routes` | `domain.hostname` (globally unique) |
| `engines` | `(engine, port)` |
| `databases` | `(engine, database_name)` |
| `cron` | `(service_id, name)` |
| `file_roots` | staging first, then per placed service in `workloads` order: volumes by mount path, then the site root |

`Workload.env` is a repeated field and not a map precisely for this reason: protobuf map
ordering is not deterministic, and a value that must not be logged also has to be marked
as such.

---

## 5. What the node reports back

The panel owns intent; the node owns fact. Nothing in this section is ever written back
into a spec, and no controller or use-case may write any of these columns - only the
handlers in `lhqm.furimeo.wisper.grpc`, through the classes named in
[`panel-ports.md`](panel-ports.md) §3.

| Message | Carried on | Written by | Lands in |
|---|---|---|---|
| `SpecApplied` | `Connect` | `node.PublishNodeSpec.markApplied` | `node_status.applied_generation`, `last_reconcile_at`, `reconcile_error` |
| `Heartbeat` | `Connect`, every `heartbeat_interval_seconds` | `node.RecordHeartbeat` | `node_status` liveness, health and capacity |
| `NodeHello` | `Connect`, first frame | `node.RecordHandshake` | `node_status` agent version, protocol, machine facts, doctor report |
| `NodeEvent` | `Connect` | `node.RecordNodeEvent` | `audit_log` and the node's page |
| `StatusBatch.workloads` | `ReportStatus` | `placement.RecordWorkloadStatus` | `placement.reported_state`, `reported_at`, `container_id`, `running_image_digest`, `restart_count`, `last_exit_code`, `last_error`, `health` |
| `StatusBatch.routes` | `ReportStatus` | `domain.RecordRouteStatus` | `certificate` (every column) |
| `StatusBatch.databases` | `ReportStatus` | `database.RecordDatabaseStatus` | `managed_database.used_bytes`, `used_bytes_measured_at` |
| `StatusBatch.cron` | `ReportStatus` | `service.RecordCronStatus` | `cron_task.last_run_at`, `last_finished_at`, `last_exit_code`, `last_duration_ms`, `last_error` |
| `StatSample` | `PushStats` | `stats.IngestStatSample` | `stat_sample` |

### `SpecApplied` is thin on purpose

It means received, understood and stored - not converged. What happened to each workload
arrives in the next `StatusBatch`, because convergence takes longer than an acknowledgement
should.

### `partial` is the most important boolean on the wire

`StatusBatch.partial == true` means Docker did not answer, so the statuses are last-known
rather than observed.

> **Nothing may be concluded to have gone away.** "Cannot see it" is not "does not exist".

The node sets it and keeps every container. The panel must not mark a missing workload
stopped, must not release its placement, and must not publish a spec that removes it. The
node reports `NODE_HEALTH_DEGRADED` with a `health_detail` an operator can read
("docker unreachable since 12:04"), retries with backoff, and deletes nothing. Confusing
the two is the fastest way to destroy a customer's data (AGENTS.md §4.5).

### Phases, and why they are not Docker's

`WorkloadPhase` describes what a customer is waiting on. `PULLING` is a phase here and is
"created" in Docker. `CRASH_LOOPING` is what a node reports once `RestartPolicy.max_retries`
is exhausted, rather than restarting forever and leaving the customer wondering.
`UNHEALTHY` is reported, not acted on: the panel decides, because a failing check during a
slow migration is not a reason to restart a database-backed app in a loop. `UNKNOWN` is
the per-workload form of `partial`.

### Protocol version

`NodeHello.protocol_version` is checked before any other frame is interpreted. A version
the panel cannot speak ends the stream with `FAILED_PRECONDITION` and shows "node needs
upgrading". Two versions quietly misunderstanding each other is the failure this handshake
exists to prevent; negotiating down is not offered, because a partly-understood control
channel is worse than none.

---

## 6. The Go side

`sasayaki/internal/spec` holds the daemon's own view of the document: plain Go structs
with the units already resolved, converted from the protobuf on receipt and back to it for
the statuses. Every package in sasayaki works with those types rather than with
`*wisperpb.NodeSpec` directly, so that a change in the generated code lands in one file
with tests around it.

`spec.FromProto` is total - it has no error return. A nil or empty message is an empty
spec, and an enum value this binary does not recognise becomes a named constant that says
so (`spec.KindUnknown`, `spec.MountKindUnknown`, `spec.EngineUnknown`,
`spec.FileRootUnknown`, `spec.DesiredUnspecified`). The reconciler then refuses that one
entry and reports it; a daemon that refused to parse the whole spec because one field was
new would be a daemon that stops converging the moment the panel is upgraded first.

Two defaults in that conversion are deliberate and go the safe way rather than the literal
way. An unspecified `ContainerRuntime` becomes `runsc`, because defaulting the other way
hands a workload less isolation than the panel asked for and says nothing about it. An
unspecified `TlsMode` becomes on-demand, because the other default serves plain HTTP for
something that asked for TLS. An unspecified `DesiredState`, by contrast, gets **no**
default at all: the reconciler neither starts, stops nor removes such a workload, because
guessing "running" starts something a customer paid to have stopped and guessing "stopped"
takes down something that is serving.

The four status types go the other way, `ToProto`, and also come back through `FromProto` -
`internal/state` stores them on disk as the protobuf they will be sent as, so that "this
container has been crash-looping for an hour" survives a daemon restart instead of resetting
to "just started".

The spec is stored on the node as the protobuf bytes it arrived as, not as columns
(`state.Store.SaveSpec`), and re-parsed through `spec.FromProto` on load. One document,
written, hashed and diffed as a unit.
