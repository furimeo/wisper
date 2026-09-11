# Security

wisper runs other people's code on machines you are responsible for. A bug here is not a
crash, it is somebody else's customer reading your customer's files. Please treat it that
way, and we will too.

## Reporting a vulnerability

**Do not open a public issue.**

Use GitHub's private reporting: **Security → Report a vulnerability** on this repository.
That opens a channel only the maintainers can read.

Tell us what you can. A rough report today beats a perfect one next month:

- what you did, and what happened that should not have;
- which version, and whether the node was running `runsc` or `runc`;
- whether it crosses a boundary — one tenant reaching another, a customer reaching the
  node, a node reaching the panel, or anyone reaching the host.

We will confirm we have it, tell you what we think it is, and tell you when it is fixed. If
we disagree that it is a vulnerability we will say why rather than going quiet.

Please give us time to ship a fix before publishing. We are not going to name a number of
days and pretend it is a rule; tell us your timeline and we will work to it.

## What counts

The boundaries this product exists to hold:

| Boundary | What must not happen |
|---|---|
| Tenant → tenant | Reading, writing or reaching another organization's services, files, databases or traffic. |
| Container → node | Escaping the sandbox, reaching the Docker socket, or touching anything outside the volume. |
| Customer → panel | Acting as another account, escalating to platform operator, reading a secret you were not shown. |
| Node → panel | A compromised node affecting anything beyond its own workloads. |
| Anyone → secrets | Recovering an encrypted column, a webhook secret, a bootstrap token or a node credential. |

Also in scope: authentication and session handling, the enrolment flow, path traversal in
the file manager, the terminal, and anything that lets an unauthenticated request queue
work.

## What does not count

- **A node running `runc` instead of `runsc`.** That is a weaker configuration the panel
  reports in red on the node's own page. It is a deployment choice, not a vulnerability.
- **Missing disk quota on a filesystem without project quota.** Same: reported, explained,
  and documented as something to fix by mounting XFS with `prjquota`.
- Anything requiring an already-compromised platform operator account.
- Denial of service by a customer using the resources their plan allows.
- Reports from a scanner with no demonstrated impact.

## Supported versions

Pre-1.0. The latest release is the supported one. There are no backports yet.
