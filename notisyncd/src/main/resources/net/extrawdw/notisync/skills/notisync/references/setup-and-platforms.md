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

1. Run `notisync devices pair` on the desktop and leave it running.
2. On the joining device, open **Devices -> Device Pairing** and scan the QR. The QR secret authenticates the exchange automatically; no code entry is needed.
3. Review the host CARD in the joining app's existing approval sheet and choose its trust category.
4. Review the joining device CARD printed in the terminal and explicitly choose its trust category, or cancel.
5. Confirm the resulting state with `notisync devices list`.

The session expires after three minutes and permits one attempt. Start a new session after failure.
For clients or brokers without Secure Exchange, or the original optical flow, use `notisync devices pair show [--payload]`,
then obtain the phone's link, inspect it with `notisync devices pair inspect 'LINK_OR_PAYLOAD'`, and
accept with `notisync devices pair accept --own 'LINK_OR_PAYLOAD'` only when appropriate.

Do not bypass identity review or silently classify an unknown peer as `own` merely to make a feature work.

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
