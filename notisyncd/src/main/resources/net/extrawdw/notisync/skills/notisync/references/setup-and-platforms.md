# Setup and platforms

## Desktop installation

NotiSync Desktop requires JDK 21 or newer. macOS also requires Xcode Command Line Tools.

POSIX installation builds the distribution and installs commands for the current user:

```bash
./scripts/install-desktop.sh
export PATH="$HOME/.local/bin:$PATH"
```

The default POSIX installation is `~/.local/share/notisync`, with shims in `~/.local/bin`. `NOTISYNC_INSTALL_DIR` and `NOTISYNC_BIN_DIR` override those paths.

On native Windows, use the repository's Windows installer:

```powershell
.\scripts\install-desktop.bat
```

It defaults to `%LOCALAPPDATA%\Programs\NotiSync` and `.cmd` command shims under `%LOCALAPPDATA%\Microsoft\WindowsApps`. The same two override variables are supported. Each shim calls the corresponding `.bat` launcher in the installed distribution. It preserves the current `JAVA_HOME` when `%JAVA_HOME%\bin\java.exe` exists; otherwise it uses the JDK 21+ location verified and recorded during installation.

Do not assume a Windows installation is also installed inside WSL. WSL is a separate POSIX environment for executable, data, and AF_UNIX endpoint purposes.

## Pairing sequence

1. Run `notisync devices pair show` on the desktop.
2. On Android, open **Devices -> Pair a device** and scan the desktop code.
3. Obtain the Android pairing link or payload.
4. Inspect it with `notisync devices pair inspect 'LINK_OR_PAYLOAD'`.
5. Accept it with `notisync devices pair accept --own 'LINK_OR_PAYLOAD'` when it is the user's own device.
6. Confirm the resulting state with `notisync devices list`.

The QR is the trust anchor; do not bypass comparison or silently classify an unknown peer as `own` merely to make a feature work.

## Data and logs

- Linux/macOS data: `~/.notisync/`
- Windows data: `%LOCALAPPDATA%\NotiSync\`
- macOS daemon log: `~/Library/Logs/NotiSync/notisyncd.log`
- Linux daemon log: `$XDG_STATE_HOME/notisync/log/notisyncd.log`, or `~/.local/state/notisync/log/notisyncd.log`
- Windows daemon log: `%LOCALAPPDATA%\NotiSync\logs\notisyncd.log`

Explicit `notisync.dataDir` or `NOTISYNC_DATA_DIR` arrangements may relocate state; prefer command output or the active installation configuration over assuming defaults.

## Skill installation targets

Supported `--agent` identifiers are `common`, `claude-code`, `codex`, `cursor`, `gemini`, `github-copilot`, `junie`, and `opencode`.

Global defaults use the user's home directory, `CODEX_HOME` when it is an absolute path, and `XDG_CONFIG_HOME` for OpenCode when it is absolute. Project-local installation uses the conventional project directories for the chosen agents. Path resolution is platform-neutral; never hardcode a username, drive letter, OneDrive path, or POSIX home.

`notisync skills add --all` installs these five independent skills:

- `notisync`
- `notisyncd`
- `notisync-run`
- `notisync-seal`
- `notisync-ssh-agent`
