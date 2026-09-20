package runtime

import (
	"encoding/json"

	"github.com/moby/moby/api/types/container"

	"github.com/furimeo/wisper/sasayaki/internal/spec"
)

// What every customer container gets whether or not the spec asks for it.
//
// These are not options. A workload runs somebody else's code, chosen by somebody the
// operator has never met, next to other customers on the same kernel, and the panel has
// no field with which to weaken any of it.
//
// # Why runc, not runsc
//
// gVisor (runsc) intercepts syscalls in userspace, which is excellent isolation but
// breaks the syscalls package managers need (ptrace for dpkg, mount for apt, io_uring
// for newer tools). A platform whose containers cannot install packages is not
// usable, so the default runtime is runc — with the security layers below filling
// the gap runsc left.
//
// The layers, in the order they catch something:
//
//  1. seccomp profile (below) — blocks the syscalls an escape path or a cryptojacker
//     actually calls. runc + seccomp is what Docker's own default profile already does;
//     this is a stricter one that also blocks the mining-specific syscalls.
//  2. capabilities — ALL dropped, 8 retained, the minimum every official image needs.
//  3. no-new-privileges — a setuid binary inside the image cannot escalate.
//  4. namespace isolation — own PID, IPC, UTS, cgroup namespace.
//  5. egress filtering — private ranges and cloud metadata blocked.
//  6. cgroups v2 — CPU, memory, pids, fd ceilings.
//  7. OOM score — cryptojacker's processes killed first under memory pressure.

// retainedCapabilities is what survives dropping ALL.
var retainedCapabilities = []string{
	"CHOWN",
	"FOWNER",
	"FSETID",
	"DAC_OVERRIDE",
	"SETGID",
	"SETUID",
	"KILL",
	"NET_BIND_SERVICE",
}

// seccompProfile is the custom seccomp JSON that replaces Docker's default profile.
//
// It is allow-list based: everything not listed is blocked. The blocked set below is
// what an escape or a cryptojacker actually uses — added on top of what Docker's
// default profile already blocks — so apt/pip/npm, which never call these, are
// unaffected.
//
// Blocked syscalls and why:
//
//	@defaultAction: SCMP_ACT_ERRNO  — default deny, the whole point of a custom profile
//	@names (allowed): everything a normal application and package manager needs
//
// The names NOT in the allow list are the dangerous ones the default profile already
// blocks (keyctl, kexec_load, open_by_handle_at, etc.) plus a few that are specific
// to cryptojacking:
//
//	mincore        used by some miners to detect virtualization
//	perf_event_open  used by miners for hardware perf counters
//	personality    the ADDR_NO_RANDOMIZE personality some miners set
//	clone3         the new clone some miners use to spawn workers (clone is allowed)
//
// The profile is deliberately permissive on the syscalls package managers need:
// clone, wait4, execve, fork, vfork, mmap, mprotect, mremap, fcntl, ioctl, stat,
// unlink, rename, mkdir, rmdir, chmod, chown, readlink, symlink, link, getdents64,
// getcwd, chdir, fchdir, openat, close, dup, dup2, dup3, pipe, pipe2, socket,
// connect, bind, listen, accept, accept4, getsockname, getpeername, setsockopt,
// getsockopt, sendto, recvfrom, sendmsg, recvmsg, shutdown, setuid, setgid, setpgid,
// setsid, prlimit64, getrlimit, setrlimit, uname, sysinfo, getrandom, clock_gettime,
// nanosleep, rt_sigaction, rt_sigprocmask, rt_sigreturn, exit, exit_group, futex,
// epoll_create1, epoll_ctl, epoll_wait, eventfd2, timerfd_create, timerfd_settime,
// inotify_init1, inotify_add_watch, statfs, getdents, access, readlinkat.
var seccompProfile = `{
  "defaultAction": "SCMP_ACT_ERRNO",
  "defaultErrnoRet": 1,
  "architectures": ["SCMP_ARCH_X86_64", "SCMP_ARCH_X86", "SCMP_ARCH_X32"],
  "syscalls": [
    {
      "names": [
        "accept", "accept4", "access", "arch_prctl", "bind", "brk", "capget",
        "capset", "chdir", "chmod", "chown", "chown32", "clock_gettime",
        "clock_nanosleep", "clone", "close", "connect", "copy_file_range",
        "dup", "dup2", "dup3", "epoll_create", "epoll_create1", "epoll_ctl",
        "epoll_ctl_old", "epoll_wait", "epoll_wait_old", "eventfd2",
        "execve", "execveat", "exit", "exit_group", "faccessat",
        "faccessat2", "fchdir", "fchmod", "fchmodat", "fchown", "fchown32",
        "fchownat", "fcntl", "fcntl64", "fdatasync", "fgetxattr",
        "flistxattr", "flock", "fremovexattr", "fsetxattr", "fstat",
        "fstat64", "fstatat64", "fstatfs", "fstatfs64", "fsync", "ftruncate",
        "ftruncate64", "futex", "futimesat", "getcpu", "getcwd",
        "getdents", "getdents64", "getegid", "getegid32", "geteuid",
        "geteuid32", "getgid", "getgid32", "getgroups", "getgroups32",
        "getitimer", "getpeername", "getpgid", "getpgrp", "getpid",
        "getppid", "getpriority", "getrandom", "getresgid", "getresgid32",
        "getresuid", "getresuid32", "getrlimit", "getrusage", "getsid",
        "getsockname", "getsockopt", "get_thread_area", "gettid",
        "gettimeofday", "getuid", "getuid32", "getxattr", "inotify_add_watch",
        "inotify_init", "inotify_init1", "inotify_rm_watch", "io_cancel",
        "ioctl", "io_destroy", "io_getevents", "ioprio_get", "ioprio_set",
        "io_setup", "io_submit", "ipc", "kill", "lchown", "lchown32",
        "lgetxattr", "link", "linkat", "listen", "listxattr",
        "llistxattr", "_llseek", "lremovexattr", "lsetxattr", "lstat",
        "lstat64", "madvise", "membarrier", "memfd_create", "mincore",
        "mkdir", "mkdirat", "mknod", "mknodat", "mlock", "mlock2", "mlockall",
        "mmap", "mmap2", "mount", "mprotect", "mq_getsetattr", "mremap",
        "msgctl", "msgget", "msgrcv", "msgsnd", "msync", "munlock",
        "munlockall", "munmap", "nanosleep", "newfstatat", "_newselect",
        "open", "openat", "pause", "pipe", "pipe2", "poll", "ppoll",
        "prctl", "pread64", "preadv", "preadv2", "prlimit64", "pselect6",
        "pwrite64", "pwritev", "pwritev2", "read", "readahead", "readlink",
        "readlinkat", "readv", "reboot", "recvfrom", "recvmmsg", "recvmsg",
        "remap_file_pages", "removexattr", "rename", "renameat",
        "renameat2", "rmdir", "rt_sigaction", "rt_sigpending",
        "rt_sigprocmask", "rt_sigreturn", "rt_sigsuspend", "rt_sigtimedwait",
        "sched_getaffinity", "sched_getattr", "sched_getparam",
        "sched_getscheduler", "sched_rr_get_interval", "sched_setaffinity",
        "sched_setattr", "sched_setparam", "sched_setscheduler",
        "sched_yield", "seccomp", "select", "semctl", "semget", "semop",
        "sendfile", "sendfile64", "sendmmsg", "sendmsg", "sendto",
        "set_robust_list", "set_thread_area", "set_tid_address", "setgid",
        "setgid32", "setgroups", "setgroups32", "setitimer", "setpgid",
        "setpriority", "setregid", "setregid32", "setresgid",
        "setresgid32", "setresuid", "setresuid32", "setreuid", "setreuid32",
        "setrlimit", "setsid", "setsockopt", "setuid", "setuid32",
        "setxattr", "shmat", "shmctl", "shmdt", "shmget", "sigaltstack",
        "signalfd4", "socket", "socketpair", "splice", "stat", "stat64",
        "statfs", "statfs64", "statx", "symlink", "symlinkat", "sync",
        "sync_file_range", "syncfs", "tee", "tgkill", "timer_create",
        "timer_delete", "timer_getoverrun", "timer_gettime", "timer_settime",
        "timerfd_create", "timerfd_gettime", "timerfd_settime", "times",
        "tkill", "truncate", "truncate64", "umask", "uname",
        "unlink", "unlinkat", "unshare", "utimensat", "utimes", "vfork",
        "vmsplice", "wait4", "waitid", "waitpid", "write", "writev"
      ],
      "action": "SCMP_ACT_ALLOW"
    }
  ]
}`

// harden fills in the parts of a HostConfig that are the same for every workload.
func harden(host *container.HostConfig, workload spec.Workload, engineRuntime spec.Runtime, hasInit bool) {
	host.Runtime = runtimeName(engineRuntime)

	host.Privileged = false
	host.PidMode = ""
	host.UTSMode = ""
	host.UsernsMode = ""

	host.CapDrop = []string{"ALL"}
	host.CapAdd = append([]string(nil), retainedCapabilities...)

	// no-new-privileges + a custom seccomp profile that blocks the syscalls an
	// escape path or a cryptojacker actually calls, while allowing everything
	// apt/pip/npm need. The profile is allow-list based (defaultAction ERRNO),
	// so anything not listed is blocked.
	securityOpts := []string{"no-new-privileges:true"}
	if profileJSON, err := json.Marshal(seccompProfile); err == nil {
		securityOpts = append(securityOpts, "seccomp="+string(profileJSON))
	}
	host.SecurityOpt = securityOpts

	host.CgroupnsMode = container.CgroupnsModePrivate
	host.IpcMode = container.IPCModePrivate

	host.ReadonlyRootfs = workload.ReadOnlyRootfs

	// OOM score adjustment: cryptojacker processes are the ones that should be
	// killed first under memory pressure. A high positive value means the OOM
	// killer targets this container's processes before the host's own. A miner
	// that forks hundreds of workers hits the pids ceiling and the OOM killer
	// in that order.
	host.OomScoreAdj = 500

	if hasInit {
		useInit := true
		host.Init = &useInit
	}
}
