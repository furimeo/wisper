# What only a real Linux node can prove

Most of wisper is checked by CI and by a developer's machine. Three things are not, and
they are three of the things the product's safety rests on. They are listed here rather
than mocked into looking as though they had been tested, because a fake that passes is
worse than a gap that is written down.

Everything below assumes a node enrolled and running: `sasayaki doctor` clean, the panel
showing it **Connected**.

## 1. gVisor actually contains the workload

`runsc` is the default runtime and the reason a customer's syscalls do not reach your
kernel. A developer's machine cannot check it: WSL2 runs its own kernel and gVisor's
support there is not the support a production kernel gives.

```bash
sasayaki doctor | grep runtime.runsc
```

Then start a service with `runtimeIsolation: RUNSC` from the panel and confirm the node
gave it what it asked for rather than quietly falling back:

```bash
docker inspect "$(docker ps -q --filter label=wisper.managed=true | head -1)" \
  --format 'runtime={{.HostConfig.Runtime}}'
```

It must print `runsc`. If it prints `runc`, the panel should already be showing that node
in red — check that it is. **A node running `runc` while the panel believes otherwise is
the one failure gVisor was adopted to prevent.**

Inside the container, the kernel should be gVisor's and not yours:

```bash
docker exec <container> uname -a          # expect a gVisor kernel string, not the host's
docker exec <container> cat /proc/version
```

## 2. Disk quota is enforced, not just recorded

CPU and memory are cgroup ceilings and hold everywhere. **Disk does not.** It needs XFS
with project quota, and on any other filesystem the limit is a number in the database that
nothing enforces — one customer can fill the disk and take every service on the machine
with them.

Give `/var/lib/wisper` its own XFS filesystem mounted with `prjquota`, then:

```bash
sasayaki doctor | grep storage.filesystem     # must not warn
```

Create a service with a small volume, then try to exceed it from inside the container:

```bash
docker exec <container> sh -c 'dd if=/dev/zero of=/data/fill bs=1M count=999999'
```

It must fail with `No space left on device` at the volume's limit, and **the rest of the
node must be unaffected**. Check the other services are still serving.

Decide this before a node has customer data on it. Moving the volume root afterwards means
moving that data.

## 3. Certificates from a real authority

The edge issues on demand through ACME. That path needs a real domain, port 80 reachable
from the internet, and Let's Encrypt's production or staging directory — none of which
exists on a laptop.

Point a hostname at the node, add it as a domain in the panel, and:

```bash
curl -sI https://that-hostname/ | head -1
openssl s_client -connect that-hostname:443 -servername that-hostname </dev/null 2>/dev/null \
  | openssl x509 -noout -issuer -dates
```

Use the staging directory while you are testing. The production rate limit is per
registered domain and a rebuild loop spends it in an afternoon.

Then check the part that matters more than issuance: **stop the panel** and confirm the
node still serves the site and still answers on-demand TLS for a hostname it already knows.
The `ask` decision is answered in-process from the node's cached route table precisely so
that a panel outage is not an outage.

## 4. The things worth doing on a real node anyway

Not impossible elsewhere, but only meaningful here:

- **Reboot the machine.** systemd should bring `sasayaki` back, and the reconcile loop
  should converge to the published spec without the panel doing anything.
- **Kill the daemon mid-flight** (`kill -9`) and start it again. It must reconverge from
  its own SQLite, not from the panel.
- **Stop Docker** while the daemon runs. The node must report `degraded` and **must not
  remove a single container** — mistaking "cannot see it" for "does not exist" is the
  fastest way to destroy a customer's data.
- **Re-run the installer.** It is an upgrade, not a break.
- **`sasayaki uninstall`** must leave `/var/lib/wisper` untouched.
