---
name: notisyncd
description: Operate and troubleshoot the NotiSync Desktop `notisyncd` daemon on Windows, macOS, or Linux, including lifecycle, connectivity, configuration, storage, logs, and autostart. It is not a GPG or SSH agent.
---

# NotiSync Daemon

NotiSync Daemon (`notisyncd`) is NotiSync Desktop's own-device peer and owner-private local API. Use **NotiSync Daemon** in user-facing prose; reserve `notisyncd` for the command, process, paths, and portable skill ID. It maintains the desktop identity, encrypted broker connection, trust state, application registrations, and message routing for features such as Run, Seal, and NotiSync SSH Agent.

It is not `gpg-agent`, OpenSSH `ssh-agent`, the Windows OpenSSH Authentication Agent service, or a replacement for any of them. Starting or stopping `notisyncd` does not directly change `GPG_AGENT_INFO`, `SSH_AUTH_SOCK`, Git signing configuration, or SSH client configuration.

## Lifecycle

```text
notisyncd                 # foreground
notisyncd foreground
notisyncd start
notisyncd stop
notisyncd restart
notisyncd status
notisyncd config get
notisyncd config set OPTION VALUE
notisyncd pair ...
notisyncd devices ...
```

With no command it runs in the foreground. `status` does not autostart: success writes JSON to stdout; a stopped daemon writes `notisyncd: not running` to stderr and exits nonzero. `notisync daemon status` is the human-readable administrative equivalent.

Use foreground mode to observe startup failures. Use detached `start` for routine operation. Do not start multiple instances against the same data directory; the owner-validated lock and local socket intentionally reject that.

## Autostart boundary

These live operations start the daemon on demand when it is absent and the installed launcher can be found:

- operational `notisync` commands such as config, devices, applications, and quarantine;
- `nsrun` reporting setup;
- recognized remote signing and `doctor` in `notisync-gpg`;
- NotiSync SSH Agent `start`, foreground runtime, and `doctor`.

Help, configuration-only paths, status/stop, local GPG delegation, and offline cached-key listing intentionally avoid daemon startup. Do not diagnose a non-starting status command as broken autostart.

On Windows, the command on `PATH` is normally a `.cmd` shim while in-distribution autostart normally resolves `bin\notisyncd.bat`; both belong to the same installation. Launcher discovery accepts only `notisyncd.exe`, `.bat`, or `.cmd`. On POSIX it accepts the executable extensionless `notisyncd`. An error mentioning Windows `CreateProcess` error 193 or `ERROR_BAD_EXE_FORMAT` usually means an obsolete installation selected a POSIX launcher; update the desktop distribution rather than attempting to execute that file through a shell.

Read [references/platforms-and-troubleshooting.md](references/platforms-and-troubleshooting.md) for configuration options, paths, logs, and platform-specific diagnostics.

## Configuration responsibilities

`notisyncd config set` accepts `broker-url`, `device-name`, `auto-apply-trusted-device-tables`, `log-level`, and `websocket-ping-seconds`. The broker URL is the HTTP(S) base URL also configured on the Android peer; live delivery derives WS(S).

Feature-specific files are separate. In particular, `nsrun.conf`, `notisync-gpg.conf`, and `notisync-ssh-agent.conf` are not daemon configuration and should not be edited to repair daemon connectivity.

## Safe diagnosis

1. Run `notisyncd status` without changing state.
2. If stopped, run foreground mode when a startup error needs explanation, or `start` when the user asked to run it.
3. Check the active platform log and owner-private data directory.
4. Run `notisync status` to see broker connection state, identity, capabilities, and trust quarantine.
5. Keep failures separated: daemon availability, broker connectivity, device trust/capability, and the feature helper are distinct layers.

Never delete `~/.notisync`, `%LOCALAPPDATA%\NotiSync`, trust state, or private material as a generic repair. Those actions can destroy the desktop identity and require explicit user authorization.
