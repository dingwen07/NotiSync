# NotiSync SSH Agent setup

## Common prerequisites

1. Install NotiSync Desktop and pair Android as a trusted own device.
2. In Android NotiSync, enable the SSH key provider and generate/import/use an existing key according to the desired storage and approval policy.
3. Start the desktop components:

   ```text
   notisync-ssh-agent start
   notisync-ssh-agent doctor
   notisync-ssh-agent keys
   ```

4. Configure only the intended shell/client to use the reported endpoint.
5. Test identity listing before a real connection: `ssh-add -L` or `ssh-add -l`.

If multiple Android providers hold a key used for `ssh-add` import, set the intended destination client ID with `notisync-ssh-agent config set-default-provider CLIENT_ID`. Clearing it restores provider selection behavior. This setting is reloaded for imports without restarting.

## Linux and macOS

The default endpoint is `~/.notisync/S.ssh-agent`. Start the process, then apply the environment in the current shell:

```bash
eval "$(notisync-ssh-agent env)"
ssh-add -L
```

For a persistent per-host/client choice, prefer OpenSSH `IdentityAgent` over replacing every shell's global agent. Inspect existing SSH configuration first. On macOS, launchd may populate another `SSH_AUTH_SOCK`; changing one shell does not remove or stop that other agent.

## Native Windows

Windows uses named pipes by default. AUTO mode first tries:

```text
\\.\pipe\openssh-ssh-agent
```

If that name is occupied, it falls back at bind time to a stable private `\\.\pipe\notisync-ssh-agent-...` name. Run `status` or `env` after startup to learn what was actually selected.

`notisync-ssh-agent env` prints a PowerShell assignment and an OpenSSH `IdentityAgent` line. Copy/evaluate only the assignment line when setting the current process; the whole two-line output is not a PowerShell script. When the fixed OpenSSH pipe was selected, native Windows OpenSSH normally finds it without `SSH_AUTH_SOCK`.

Modes:

```text
notisync-ssh-agent config set-endpoint auto
notisync-ssh-agent config set-endpoint custom
notisync-ssh-agent config set-endpoint openssh-compatible
notisync-ssh-agent stop
notisync-ssh-agent start
```

Endpoint-mode changes require an agent restart. `openssh-compatible` requires the fixed pipe and fails on conflict. `custom` avoids taking over the system pipe but requires client configuration. AUTO is the portable default. Do not stop the Windows OpenSSH Authentication Agent service unless the user explicitly chooses NotiSync to own its pipe name.

Git for Windows bundles an MSYS OpenSSH build that does not consume the native Windows named pipe. For SSH transport, select the native client deliberately, for example through a scoped `core.sshCommand` or the user's chosen `GIT_SSH` setup. Do not change this globally without checking existing configuration.

Git's SSH commit/tag signing is separate from SSH transport. On Windows it also needs native Windows `ssh-keygen.exe`:

```powershell
git config --global gpg.format ssh
git config --global gpg.ssh.program C:/Windows/System32/OpenSSH/ssh-keygen.exe
```

Keep `user.signingKey` pointed at the intended public key file or Git `key::ssh-...` value. PowerShell redirection can produce UTF-16 on older shells; write public-key files explicitly as ASCII/UTF-8 if Git or `ssh-keygen -Y sign` cannot parse them. Inspect existing `gpg.format`, `gpg.ssh.program`, and `user.signingKey` before overwriting.

## WSL

WSL2 cannot directly consume the Windows named pipe or a Windows AF_UNIX socket. Choose one explicit model:

- install/run NotiSync Desktop and its SSH Agent inside WSL as a separate Linux environment; or
- use a deliberate named-pipe bridge such as an established `npiperelay`/`socat` arrangement.

Do not claim the native Windows endpoint is reachable from WSL2 without such a bridge. WSL1 behavior differs, but should not be assumed.

## Explicit endpoints

`-a ADDRESS` overrides configured endpoints for one foreground/start/env/doctor invocation and may be repeated. On POSIX it must be an absolute AF_UNIX path. On Windows it may be a local named pipe or an absolute AF_UNIX path, including multiple endpoint types, but named pipe is the interoperable default for native Windows clients.
