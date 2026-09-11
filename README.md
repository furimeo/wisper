# wisper

Self-hosted web hosting. Customers get an isolated slot they can run anything in, and a
panel they can do everything from - including a real terminal and a real file editor.

Two programs:

| | Name | What it is |
|---|---|---|
| Panel | **wisper** | Java 21, Spring Boot MVC, PostgreSQL. One executable jar with the React client inside it. |
| Node daemon | **sasayaki** | Go. One static binary with Caddy linked in. One per machine. |

They talk over gRPC, and **the node always dials the panel** - so a node needs no inbound
port, no public hostname and no certificate of its own. The panel can sit on a public
address or behind a tunnel with no public address at all; nodes reach it either way.

```
   customers ──HTTPS──▶ wisper (one jar; public address or tunnel)
                              │ gRPC, node dials out
              ┌───────────────┼───────────────┐
              ▼               ▼               ▼
          sasayaki        sasayaki        sasayaki
          caddy :80/:443  + docker + gVisor on each node
              ▲
              └── visitors reach customer sites directly, never through the panel
```

That last line is the point: the panel going down costs you the ability to manage the
platform, not the ability to serve it.

## What a customer gets

- **Apps** - any container: Node, Python, Go, a bot, a worker, a cron job. Deployed from a
  Git push, an uploaded archive or an image reference.
- **Static sites** - built in a throwaway container, published by swapping a symlink, so a
  deploy is atomic, a rollback is instant, and an idle site costs nothing.
- **Managed databases** - PostgreSQL or MySQL, one shared engine per node, one database and
  one least-privilege user per customer.
- **A terminal and a file manager in the browser.** There is no SSH and no SFTP by design:
  the panel may be behind a tunnel that carries no raw TCP, and plenty of customers work
  from a phone. So the web has to carry that weight, and uploads resume after a dropped
  connection because a 4G connection is the normal case, not the edge case.
- **Backups** - scheduled snapshots of volumes and databases to S3-compatible storage, and
  restore behind one button.

Customers rent a **slot**, not a machine: a plan caps the CPU, memory, disk and counts an
organization may use, each service declares its own ceiling, and the node applies the real
cgroup limits. Many tenants share every node.

## Isolation

gVisor (`runsc`) by default, so a customer's syscalls hit a userspace kernel rather than
yours. Per-tenant Docker networks, every capability dropped, `no-new-privileges`, a pids
ceiling, and egress blocked to private ranges and the cloud metadata endpoint. The Docker
socket is never exposed to a customer container.

A node that cannot provide something says so instead of pretending: no `runsc` installed,
or a volume filesystem without project quota, and the panel shows a red panel on that node
explaining exactly what is weaker and what it means.

## Running the panel

Needs a JVM 21 and PostgreSQL 17. Nothing else - no nginx, no PHP, no Redis, no Node at
runtime.

```bash
createdb -O wisper wisper                  # the database, owned by the panel's role
export WISPER_CRYPTO_KEY_1="$(openssl rand -base64 32)"
java -jar wisper.jar
```

Three environment variables exist and two have defaults:

| Variable | Default | |
|---|---|---|
| `WISPER_DB_USER` | `wisper` | |
| `WISPER_DB_PASSWORD` | `wisper` | |
| `WISPER_CRYPTO_KEY_1` | none | **Required.** AES-256 key for every encrypted column. |

There is no default key and none is generated at boot on purpose: a key invented on startup
would encrypt a customer's database password and then be unable to read it back after a
restart.

Migrations run themselves. On a database with no accounts in it the panel creates the first
operator and prints the password once, at `WARN`. Sign in, change it, turn on two-factor.

## Adding a node

Needs Docker, and `runsc` if you want gVisor. Create the node in `/admin/nodes`; the panel
gives you a single-use token and the exact command, with the checksum to verify by eye:

```bash
curl -fsSL https://your-panel/install.sh -o install.sh
sha256sum -c <<< "<the hash the panel shows>  install.sh"
sudo ./install.sh --token-file token.txt
```

The installer runs `sasayaki doctor` before it writes anything, and refuses to install onto
a machine that cannot host workloads. Run `sasayaki doctor` yourself first if you want to
see what it checks - it changes nothing.

Tokens are never passed on the command line. `argv` is readable by every user on the
machine through `ps`.

## Building

```bash
cd panel && ./gradlew bootJar          # one jar, React bundle included
cd sasayaki && make build-linux        # one static binary
```

The `.proto` files under `proto/` are the single contract between the two, generating both
the Java and the Go stubs, so a field renamed on one side fails to compile on the other.

## Status

Early. It runs: a node enrols, the reconcile loop converges, containers come up under real
cgroup limits, the edge serves them, and the panel drives all of it. It has not been run in
anger.

Not verified anywhere but a real Linux node - see `docs/verify-on-linux.md`: gVisor under a
production kernel, XFS project quota, and certificates from a real ACME server.

Deliberately not built: Kubernetes, DNS automation, billing, multi-region, SSH/SFTP.

## Documentation

`docs/contracts/` holds the binding documents - the seams between panel and node, the HTTP
and view conventions, the database invariants, the translation rules. They were written
before the code and the code is held to them.

## Licence

[Apache-2.0](LICENSE), with a patent grant. Do what you like with it, including running a
hosting business on it and keeping your changes to yourself.

wisper puts a small "Powered by wisper" line in the panel's footer. That is not a licence
condition and you may change it - `NOTICE` says so, and points at the component. It is
there because somebody evaluating a hosting provider should be able to find out what runs
it, and because that footer is how a project like this gets found at all.
