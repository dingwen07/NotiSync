# Desktop tools

[Documentation](README.md) · [Getting started](getting-started.md) · [Troubleshooting](troubleshooting.md)

NotiSync Desktop connects command-line tools to your trusted devices through the `notisyncd` peer
daemon. Installers build from source and install for the current user.

| Command | Purpose | Platforms |
| --- | --- | --- |
| `notisync` | Manage devices, trust, local applications, and agent skills | Linux, macOS, Windows |
| `notisyncd` | Run and configure the peer daemon | Linux, macOS, Windows |
| `nsrun` | Report command output and accept remote process controls | Linux, macOS |
| `notisync-gpg` | OpenPGP adapter for [Seal](seal.md) | Linux, macOS, Windows |
| `notisync-ssh-agent` | [SSH agent](ssh-agent.md) backed by trusted device key providers | Linux, macOS, Windows |
| `nsscreen` | [Android screen viewer](screen-sharing.md) | Linux, macOS |

## Prerequisites

- Git and JDK 21 or newer. The Gradle build uses a Java 21 toolchain.
- Android SDK Platform 37 and a configured SDK location, because the shared build also configures
  the Android module. See [build setup](development.md#build-setup).
- On Linux and macOS: a C17 compiler, `pkg-config`, SDL 3 (or SDL 2), and FFmpeg development
  libraries (`libavcodec`, `libavutil`, `libswscale`) for the included screen helper.
- On macOS: Xcode Command Line Tools for the native launchers.

Windows distributions omit `nsrun`, `nsscreen`, and the native screen helper.

## Install

### Linux and macOS

```bash
git clone https://github.com/dingwen07/NotiSync.git
cd NotiSync
./scripts/install-desktop.sh
export PATH="$HOME/.local/bin:$PATH"
```

Add the `PATH` export to your shell's startup file. The default installation is
`~/.local/share/notisync`, with commands in `~/.local/bin`.

### Windows

In PowerShell:

```powershell
git clone https://github.com/dingwen07/NotiSync.git
Set-Location NotiSync
.\scripts\install-desktop.bat
```

The launcher runs the native PowerShell installer. Files go under
`%LOCALAPPDATA%\Programs\NotiSync`, and command shims go in
`%LOCALAPPDATA%\Microsoft\WindowsApps`, which is normally on `PATH`.

Both installers accept absolute `NOTISYNC_INSTALL_DIR` and `NOTISYNC_BIN_DIR` overrides.
The command shims remember the JDK 21+ verified during installation and use it when the current
shell has no valid `JAVA_HOME`.

## Pair with a phone

Set a recognizable name, then display the desktop pairing code:

```bash
notisync config set device-name "Workstation"
notisync devices pair show
```

On Android, open **Devices → Pair a device** and scan the terminal QR code. Complete mutual
trust by copying the phone's pairing link or payload back to the computer:

```bash
notisync devices pair inspect 'ANDROID_PAIRING_LINK_OR_PAYLOAD'
notisync devices pair accept --own 'ANDROID_PAIRING_LINK_OR_PAYLOAD'
notisync devices list
```

Verify the displayed identity before accepting. Device trust actions take the action first and
the device ID second:

```bash
notisync devices action approve DEVICE_ID
```

To deliberately approve every currently pending device, use `notisync devices action approve --all`.
For a custom broker, set the same URL on the phone and desktop as described in
[getting started](getting-started.md#use-a-custom-broker).

## Daemon lifecycle

Commands that need the daemon start it automatically. Status and offline configuration commands
do not. Use these commands for explicit control:

```bash
notisync daemon start
notisync status
notisync daemon restart
notisync daemon stop
```

`notisync daemon`, `notisync daemon status`, and `notisync status` only report status. The lower-level
`notisyncd start|stop|restart|status` commands are also available. `notisyncd status` writes JSON to
stdout when running, or a concise error to stderr when stopped.

The daemon and CLI use a local AF_UNIX API on all three platforms. Windows supports administrative
and send endpoints. Process-leased `/v1/receive` registration is unavailable on Windows because
AF_UNIX does not expose verified peer credentials; the daemon's encrypted broker receive path
still works. The [SSH agent](ssh-agent.md) uses its own endpoint.

## NotiSync Run

On Linux or macOS, prefix a command with `nsrun --`:

```bash
nsrun -- git commit
nsrun --update-interval 15s -- ./long-build
```

Run sends encrypted progress, input-wait, and completion updates to trusted own devices. The
Android **Run** tab and ongoing notification show the terminal tail and offer prompt input,
Interrupt, Terminate, Kill, and signal controls. Dismissing the notification does not signal the
process. `nsrun` preserves interactive terminal behavior and still runs the child if reporting
is unavailable.

```bash
nsrun config get
nsrun config set updateInterval 30s
nsrun config set stuckAfter 5m       # or: off
nsrun config set pty auto           # auto, always, or never
```

Private Run logs are stored under `~/.notisync/runs/`.

## NotiSync Seal

Use `notisync-gpg` to approve OpenPGP-signed Git commits and annotated tags with OpenKeychain on
Android. Follow the [Seal guide](seal.md) for provider setup, Git configuration, and verification.

## SSH Agent

Use `notisync-ssh-agent` to route SSH authentication and Git SSH signing requests to trusted phone
key providers. Follow the [SSH Agent guide](ssh-agent.md) for provider setup, Linux/macOS sockets,
Windows named pipes, WSL, and troubleshooting.

## Agent skills

The desktop distribution includes portable instructions for `notisync`, `notisyncd`, Run, Seal,
and SSH Agent:

```bash
notisync skills list
notisync skills list --long
notisync skills add notisync-seal
notisync skills add --all
notisync skills remove notisync-seal
```

Without `--agent`, add/remove targets the portable common agent directory and supported agent
environments detected for the current user. Choose targets with `--agent=common,codex`, or use
`--project=/absolute/path/to/project` for an existing project root.

Supported targets are `common`, `claude-code`, `codex`, `cursor`, `gemini`, `github-copilot`,
`junie`, and `opencode`. Paths follow the user's home and, where applicable, absolute `CODEX_HOME`
and `XDG_CONFIG_HOME` values.

## Data, logs, and updates

| Data | Linux | macOS | Windows |
| --- | --- | --- | --- |
| Configuration and daemon data | `~/.notisync/` | `~/.notisync/` | `%LOCALAPPDATA%\NotiSync\` |
| Daemon log | `$XDG_STATE_HOME/notisync/log/notisyncd.log` | `~/Library/Logs/NotiSync/notisyncd.log` | `%LOCALAPPDATA%\NotiSync\logs\notisyncd.log` |

If `XDG_STATE_HOME` is unset, Linux logs go under `~/.local/state/notisync/log/`. The current desktop
key provider stores unencrypted key material in `private-keys-v1/` under the private daemon data
directory; see the [security model](security.md#key-storage).

Inspect and remove persistent local-application registrations with:

```bash
notisync applications list
notisync applications remove APPLICATION_ID
```

For example, use `notisync applications remove nsrun` to remove a stale Run registration. Logs include
an ISO-8601 timestamp, severity, and thread name. The default level is `WARN`; enable more detail with
`notisyncd config set log-level info`.

To update, update your source checkout and rerun the installer. If the daemon is running, the installer
stops it before replacing the installation and starts the updated daemon afterward.
