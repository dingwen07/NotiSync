---
name: notisync-run
description: Use NotiSync Run's POSIX `nsrun` to supervise commands, report encrypted progress to trusted Android devices, and accept remote input or signals. Use only when the user wants a shared, remotely controllable run.
---

# NotiSync Run

NotiSync Run is the `nsrun` wrapper. It starts a command on the current computer and keeps normal terminal behavior while reporting progress, prompts, and completion to NotiSync on trusted own devices. It is not a remote shell and does not move execution to the phone.

`nsrun` is distributed on Linux and macOS, not native Windows. In WSL, treat it as a separate POSIX installation; do not assume the native Windows NotiSync installation or local socket is shared.

## Invocation

```bash
nsrun -- git commit
nsrun --update-interval 15s -- ./long-build
nsrun --stuck-after off -- command arg
nsrun --pty always -- interactive-command
```

Use `--` when the child command or its first argument could be parsed as an `nsrun` option. The wrapper returns the child's exit code. If the child executable cannot be started, it reports the launch failure and returns 127.

Do not add `nsrun` around every long operation automatically. Use it when the user asked for NotiSync Run, phone progress, or remote interaction and understands that command context/output will be shared with their own devices.

## Availability and failure semantics

`nsrun` tries to start/connect to `notisyncd`, but reporting is best effort. Messages such as these mean the child continues locally:

```text
nsrun: NotiSync reporting setup timed out ...; command continues offline
nsrun: NotiSync reporting unavailable: ...
nsrun: NotiSync reporting setup was interrupted; command continues offline
```

Do not mistake those warnings for the child command's failure, and do not rerun a potentially non-idempotent child merely to regain reporting. Observe the child's own output and final exit status. If the user's objective requires phone control rather than only command completion, report that Run became unavailable even when the child succeeds.

## Phone controls

Android can display the terminal tail, prompt state, and completion. It may send input, `Interrupt`, `Terminate`, `Kill`, and supported signals/actions back to the local child. Dismissing a Run notification does not signal the process.

Remote input is redacted from the captured output projection after it is sent to the child, but this is not a general secret-management guarantee. Avoid passing secrets through monitored output when a safer channel exists.

## PTY behavior

- `auto` selects a PTY when appropriate and otherwise uses ordinary pipes.
- `always` forces PTY behavior for interactive programs.
- `never` is useful for deterministic noninteractive tools or when PTY presentation changes output.

If terminal echo, colors, prompts, or signal handling differ, compare the same command without `nsrun` and try the appropriate PTY mode before changing the child program.

Read [references/configuration-and-privacy.md](references/configuration-and-privacy.md) when configuring intervals, stuck detection, logs, or optional LLM summaries.
