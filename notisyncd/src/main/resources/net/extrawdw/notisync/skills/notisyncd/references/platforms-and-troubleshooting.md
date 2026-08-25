# Platforms and troubleshooting

## Paths

| Platform | Data | Logs |
| --- | --- | --- |
| Linux | `~/.notisync/` | `$XDG_STATE_HOME/notisync/log/` or `~/.local/state/notisync/log/` |
| macOS | `~/.notisync/` | `~/Library/Logs/NotiSync/` |
| Windows | `%LOCALAPPDATA%\NotiSync\` | `%LOCALAPPDATA%\NotiSync\logs\` |

The local API endpoint is `S.notisyncd` under the data directory on all three platforms. Windows also uses AF_UNIX for this API, but it cannot supply POSIX peer credentials; owner-only filesystem ACLs are the local trust boundary there.

Detached daemon logs rotate at 10 MiB with five retained files. Log entries include timestamp, severity, and thread. The default level is `WARN`; temporarily use `notisyncd config set log-level info` only when the daemon is live and additional detail is useful.

## Common results

- `notisyncd: not running`: normal stopped-state result from `status`.
- `notisyncd is already running`: another healthy instance answered, or the owner-validated instance lock is held. Do not start another data directory merely to evade the lock unless isolation was the goal.
- `notisyncd exited during startup; see ...`: inspect only the referenced current log before changing configuration.
- `notisyncd did not become ready`: startup did not expose the local API within the timeout. Check process/log/data ownership and launcher selection.
- refusal to remove a non-socket `S.notisyncd`: a safety check found an unexpected filesystem node. Do not delete it blindly; inspect ownership, type, and how it was created.
- owner or permission validation failure: NotiSync refuses shared or foreign-owned private state. Move to a directory owned by the actual user or repair the intended directory's permissions with explicit authorization.

## Windows

The native user installation has two launcher layers:

- `%LOCALAPPDATA%\Microsoft\WindowsApps\notisyncd.cmd` is the command shim normally found through `PATH` by PowerShell or cmd. Running `notisyncd ...` resolves this shim through Windows `PATHEXT` command lookup.
- `%LOCALAPPDATA%\Programs\NotiSync\bin\notisyncd.bat` is the installed Gradle Windows launcher called by the shim. Daemon autostart from another NotiSync command normally finds this sibling launcher directly in the distribution.

These paths are parts of one installation, not duplicate daemons. The installation's `bin` directory also contains an extensionless `notisyncd` POSIX shell script because Gradle produces both platform launchers. Do not invoke, rename, or place that extensionless script on native Windows `PATH`.

The `.cmd` shim preserves the current `JAVA_HOME` when `%JAVA_HOME%\bin\java.exe` exists. If `JAVA_HOME` is unset or that file is absent, it sets `JAVA_HOME` to the JDK 21+ location verified and recorded by the installer, then calls `notisyncd.bat` with the original arguments. A valid-looking but incompatible current `JAVA_HOME` is not version-checked again by the shim.

Launcher discovery also accepts `notisyncd.exe` for compatible custom/native packaging, but the current Windows user installer produces the `.cmd`-to-`.bat` layout above. Use `Get-Command notisyncd.cmd` to inspect the active shim when diagnosing `PATH` resolution.

`notisyncd` is independent of the Windows OpenSSH Authentication Agent service. A named-pipe conflict belongs to `notisync-ssh-agent`, not this daemon's AF_UNIX API.

## macOS

The installed command is a native launcher hosting the JVM. Xcode Command Line Tools are required when building. Logs are in the macOS user log directory rather than under `~/.notisync` by default.

## Linux and WSL

Linux honors absolute `XDG_STATE_HOME` for logs. WSL should be treated as a distinct Linux installation and identity unless explicitly configured otherwise. Do not assume a Windows daemon socket, process, or installation is reachable from WSL2.

## Pair and broker checks

If the daemon is live but a feature cannot reach Android, distinguish:

1. `connectionState` is not connected: broker/network/config problem.
2. peer is pending, revoked, quarantined, or not `own`: trust/classification problem.
3. peer lacks the required capability: app/version/feature readiness problem.
4. peer is capable but does not respond: feature-specific provider or user-interaction problem.

Use the relevant feature skill after the first three layers are healthy.
