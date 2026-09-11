# sasayaki commands

Binding. See [README.md](README.md).

`cmd/sasayaki/main.go` is a shared file: it already dispatches all seven subcommands and
must not be edited to make a package fit. The packages named below must provide exactly
these functions.

## The signature

```go
func(ctx context.Context, args []string, out, errOut io.Writer) error
```

- `ctx` is cancelled on SIGINT or SIGTERM. A long-running command must honour it; a
  second signal kills the process outright, so there is no need to handle impatience.
- `args` is everything after the subcommand name. **Parse it yourself** with
  `flag.NewFlagSet(name, flag.ContinueOnError)` and return the error `Parse` gives you -
  `main` recognises `flag.ErrHelp` and exits 0 without printing anything more.
- Write output to `out`, not to `os.Stdout`. Tests capture it, and `--json` output must
  not be interleaved with progress chatter.
- Return `nil` for success. Any other error is printed as
  `sasayaki <command>: <err>` and the process exits 1.

## Exit codes

`deploy/install.sh` branches on these, so they are part of the contract.

| Code | Meaning |
|---|---|
| 0 | Success, or `-h` |
| 1 | The command failed |
| 2 | Usage error: no command, or an unknown one |

## The functions

| Subcommand | Function | Package |
|---|---|---|
| `run` | `daemon.Run` | `internal/daemon` |
| `doctor` | `bootstrap.Doctor` | `internal/bootstrap` |
| `enroll` | `bootstrap.Enroll` | `internal/bootstrap` |
| `install` | `bootstrap.Install` | `internal/bootstrap` |
| `uninstall` | `bootstrap.Uninstall` | `internal/bootstrap` |
| `upgrade` | `bootstrap.Upgrade` | `internal/bootstrap` |
| `version` | in `main.go` | - |

`internal/daemon` is the composition root for the running node: it opens the SQLite
state, dials the panel, and starts the reconcile loop. It is the only package allowed to
know about all of the others; `run.go` is expected to be the whole of it.

## Flags these commands must accept

The Makefile's `run-dev` target and the installer both invoke sasayaki, so these are
fixed:

**`run`**
- `--config <path>` - the node credential file. Default `/etc/wisper/node.json`.
- `--state-dir <path>` - SQLite, specs, sites, volumes. Default `/var/lib/wisper`.
- `--dev` - no systemd notifications, `runc` instead of `runsc`, debug logging. This is
  what makes the daemon runnable inside WSL2, where the kernel is not one gVisor
  supports (design §13.5).

**`doctor`**
- `--json` - the machine-readable report. The installer parses this; a human reads the
  default table.
- Exits non-zero when a **required** check fails. A missing `runsc` is a warning, not a
  failure: the node runs on `runc` and the panel shows it as less isolated rather than
  refusing to work (design §7.2).
- Changes nothing on disk. The installer runs it before it writes anything, and "doctor
  failed, nothing was installed" has to be true.

**`enroll`**
- `--token-file <path>` - the single-use bootstrap token. **Never `--token`**: argv is
  readable by every user on the machine through `ps`. Read from stdin when the path is
  `-`. Delete the file after reading it.
- `--panel <url>` - the gRPC endpoint. Pinned on first use (TOFU) and stored with the
  credential.
- `--config <path>` - where to write the credential. Default `/etc/wisper/node.json`,
  mode 0600, owned by root.

**`install`**
- Everything `enroll` takes, plus `--skip-doctor` for the operator who has already run it
  and knows what they are doing. Writes the systemd unit, enables it, starts it.
- Re-running it is an upgrade, not a failure. Running the installer twice is normal.

**`uninstall`**
- Removes the unit and the binary. **Leaves `/var/lib/wisper` alone.**
- `--purge` deletes customer data, and only after the operator types the node name to
  confirm. The default never destroys anything.

**`upgrade`**
- `--url <url>` and `--sha256 <hex>`, or `--binary <path>` for an offline node.
- Verifies the checksum before replacing anything, swaps the file atomically, keeps the
  previous binary, and rolls back to it if the new one fails to come up.

## Things that are not optional

- **Crash-only.** No cleanup on exit. The truth is in SQLite and on disk; being killed
  must be indistinguishable from a clean stop.
- **Never delete a container because Docker did not answer.** Report `degraded`, back
  off, retry. Mistaking "cannot see it" for "does not exist" is the fastest way to
  destroy a customer's data.
- **`sd_notify` every reconcile pass** when running under systemd, so `WatchdogSec`
  restarts a daemon whose loop has stopped ticking.
- **The version in `internal/version`** is what the handshake, the upgrade check and the
  doctor report all use. Do not add a second one.
