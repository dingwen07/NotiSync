# NotiSync SSH Agent

[Documentation](README.md) · [Desktop setup](desktop.md) · [Troubleshooting](troubleshooting.md)

NotiSync SSH Agent connects standard SSH clients to key providers on your trusted devices.
The desktop agent caches public identities, sends signing requests to the phone, and verifies
returned signatures. Key storage and approval follow the phone's selected provider policy.

It supports SSH authentication and Git's SSH signing format. For OpenPGP-signed commits and tags
through OpenKeychain, use [NotiSync Seal](seal.md).

Start with [provider setup](#set-up-the-provider), then configure your client on
[Linux/macOS](#linux-and-macos), [Windows](#windows), or [WSL](#wsl). Optional workflows include
[importing desktop keys](#import-a-key-from-the-desktop) and [Git SSH signing](#git-ssh-signing).

## Set up the provider

1. [Install NotiSync Desktop and pair the phone as an own device](desktop.md#pair-with-a-phone).
2. Open the phone's SSH key provider. On Android, use **Tools → SSH Keys**. Enable the provider
   and add a key using one of the options below.
3. Start and inspect the desktop agent:

   ```bash
   notisync-ssh-agent start
   notisync-ssh-agent doctor
   notisync-ssh-agent keys
   notisync-ssh-agent env
   ```

The agent starts `notisyncd` on demand. Configure your SSH client to use the endpoint reported by
`env`, following the platform instructions below. Check your existing agent configuration before
switching clients; you can choose NotiSync for one shell or host.

### Choose a key

| Option | What to do |
| --- | --- |
| Generate a key | Create a new SSH key on the phone and choose the available storage, export, and authentication settings. Hardware support varies by device and algorithm. |
| Import an existing key | On Android, use **Import file** or **Paste key** for supported OpenSSH or PuTTY PPK v2/v3 private keys. Enter the passphrase when requested and review the key before importing. |
| Use a passkey | Create or recover a WebAuthn-backed SSH identity using the phone's credential provider. Signing requires interaction with that provider. |

On Android, inspect **Key Storage** and **Export** in key details to see the actual protection and
exportability. A key generated inside secure hardware without an export copy cannot be exported.
The iOS provider stores managed private keys in its Keychain; passkey private keys remain with
the selected credential provider.

For passkeys, save the recovery record offered by NotiSync. It contains public metadata, not the
private key. Restoring the record still requires access to the same credential or its
credential-provider-backed copy. Passkey compatibility depends on the provider and SSH client;
a credential that works for a website is not automatically compatible with SSH.

### Register the public key

Copy the intended public key from the phone or select its complete line from `ssh-add -L`.
Add that **public key** to the destination server's `authorized_keys` or your Git host's SSH-key
settings. NotiSync pairing establishes trust between your devices; it does not grant access to
remote servers.

Save one selected public-key line as `~/.ssh/notisync.pub` on Linux/macOS, or a `.pub` file under
your user profile on Windows, if you want to use the key-selection and Git examples below. Keep
the key on a single line. On Windows, save it as UTF-8 or ASCII; older PowerShell redirection can
produce a UTF-16 file that OpenSSH cannot parse.

## Linux and macOS

After starting the agent, select it for the current shell and list the available public keys:

```bash
eval "$(notisync-ssh-agent env)"
ssh-add -L
```

The default socket is `~/.notisync/S.ssh-agent`. For a persistent per-host choice, set OpenSSH's
`IdentityAgent` to the reported socket in the relevant host section of your SSH configuration.
Changing `SSH_AUTH_SOCK` in one shell does not stop another agent, including macOS's launchd agent.

For example, after saving the selected public key as `~/.ssh/notisync.pub`, add a host entry to
`~/.ssh/config`. Replace the host/user values and use the endpoint reported by your agent:

```sshconfig
Host my-server
    HostName server.example.com
    User your-user
    IdentityAgent ~/.notisync/S.ssh-agent
    IdentityFile ~/.ssh/notisync.pub
    IdentitiesOnly yes
```

Connect with `ssh my-server` and review the signing request on the phone. `IdentityFile` can point
to the public key when the agent holds access to its private counterpart; `IdentitiesOnly` limits
which identities are offered. See [OpenSSH client configuration](https://man.openbsd.org/ssh_config).

## Windows

Native Windows OpenSSH uses named pipes. In the default AUTO mode, NotiSync first tries
`\\.\pipe\openssh-ssh-agent`. If that name is occupied, it selects a private
`\\.\pipe\notisync-ssh-agent-...` endpoint instead.

Run `notisync-ssh-agent env` **after startup** to see the selected endpoint. Its output contains
a PowerShell assignment and an OpenSSH `IdentityAgent` line. Copy only the assignment into
PowerShell, or use the `IdentityAgent` line in the intended SSH host configuration. The complete
two-line output is not a PowerShell script.

When the fixed OpenSSH pipe is selected, native Windows OpenSSH normally finds it without
`SSH_AUTH_SOCK`. Use `ssh-add -L` to check the identities exposed to your client.

> [!IMPORTANT]
> Git for Windows' bundled MSYS OpenSSH does not consume the native Windows named pipe. Configure
> the intended Git repository or client to use native Windows OpenSSH. Git's SSH commit/tag signing
> also needs native Windows `ssh-keygen.exe`; changing the SSH transport command alone is insufficient.

For Git SSH transport, run this inside the repository that should use native Windows OpenSSH:

```powershell
git config --local core.sshCommand C:/Windows/System32/OpenSSH/ssh.exe
```

The selected client still needs access to the endpoint reported by `env`. See
[Git SSH signing](#git-ssh-signing) for the separate signing configuration. For garbled non-ASCII
key names, see [PowerShell console encoding](troubleshooting.md#garbled-ssh-key-names-in-windows-powershell).

### Endpoint modes

| Mode | Behavior |
| --- | --- |
| `auto` | Try the fixed OpenSSH pipe, then fall back to a private pipe if occupied. This is the default. |
| `custom` | Use a private pipe and explicitly configure clients to select it. |
| `openssh-compatible` | Require the fixed OpenSSH pipe; startup fails if another agent owns it. |

To choose a private endpoint:

```powershell
notisync-ssh-agent config set-endpoint custom
notisync-ssh-agent stop
notisync-ssh-agent start
notisync-ssh-agent env
```

Use `set-endpoint auto` to return to the default, followed by the same restart sequence. Leave an
existing Windows OpenSSH Authentication Agent service running unless you intend to replace it.
For explicit addresses and multiple endpoints, see the
[advanced setup reference](../notisyncd/src/main/resources/net/extrawdw/notisync/skills/notisync-ssh-agent/references/setup.md#explicit-endpoints).

## WSL

WSL2 cannot directly use a native Windows named pipe or Windows AF_UNIX socket. Either install
NotiSync Desktop and its SSH Agent inside WSL as a separate Linux environment, or configure an
explicit named-pipe bridge such as `npiperelay` with `socat`.

With a separate WSL installation, pair that daemon as its own device, start the Linux agent, and
follow the [Linux setup](#linux-and-macos) inside WSL. The native Windows installation's pairing
and configuration do not configure the WSL daemon.

## Import a key from the desktop

Once your shell points at NotiSync SSH Agent, select the destination phone before using `ssh-add`:

```bash
notisync devices list
notisync-ssh-agent config set-default-provider PHONE_CLIENT_ID
ssh-add /absolute/path/to/private-key
```

Replace `PHONE_CLIENT_ID` with the trusted phone's device ID and use your actual private-key path.
The selected provider must be active. This selection is required even if there is only one phone.
Approve the import on that phone, then check `ssh-add -L` for the resulting identity.

The import transfers private key material to the selected provider through encrypted NotiSync
messaging. It does not remove the original file from the desktop. A key already generated on the
phone only needs public-key discovery; it does not need a desktop `ssh-add` import.

`notisync-ssh-agent config clear-default-provider` clears the import destination. Subsequent
imports fail until an active destination is selected again. Changing this setting takes effect
for the next import without restarting and does not restrict signing to that provider.

## Git SSH signing

Use these commands inside the repository you want to configure. Check and record existing
`gpg.format`, `gpg.ssh.program`, and `user.signingKey` values before replacing them, especially if
you already use [Seal](seal.md) or another signing setup.

On Linux/macOS, with your shell pointing at NotiSync and a selected public key saved locally:

```bash
git config --local gpg.format ssh
git config --local user.signingKey "$HOME/.ssh/notisync.pub"
git config --local commit.gpgSign true
git config --local tag.gpgSign true
```

On Windows, use native Windows `ssh-keygen.exe` and a UTF-8/ASCII public-key file:

```powershell
git config --local gpg.format ssh
git config --local gpg.ssh.program C:/Windows/System32/OpenSSH/ssh-keygen.exe
git config --local user.signingKey "$env:USERPROFILE/.ssh/notisync.pub"
git config --local commit.gpgSign true
git config --local tag.gpgSign true
```

Git's signing process must inherit the agent environment. An SSH host's `IdentityAgent` setting
selects the agent for SSH connections; it does not configure Git's separate `ssh-keygen` signer.

When you next create a commit or annotated tag, review the request on your phone. Register the
public key as a signing key with your Git host if required. Local signature verification also needs
a trusted-signers file configured through `gpg.ssh.allowedSignersFile`. See
[Git's signing settings](https://git-scm.com/docs/git-config) for key selection and verification.

## Approvals and key inventory

When your SSH client requests a signature, the agent forwards it to eligible trusted providers
advertising that public key. The first valid signature wins. An explicit rejection ends the request;
provider errors are tolerated only while another eligible provider remains. The default signing
timeout is 120 seconds.

### Approval policies

- **Always ask:** each request needs manual approval. Previously remembered grants remain stored
  but are ignored while this policy is active.
- **Allow remembering:** eligible requests can use remembered authorization. On Android, long-press
  **Approve** during review to choose the available application, host, and duration scope.
- **Passkeys:** provider interaction is required for every signature; approvals cannot be remembered.

Per-use biometric requirements still apply independently of remembered approval. To remove a saved
grant, use **Forget authorization** in the key's remembered-authorizations list. Switching to
**Always ask** does not delete grants. Forgetting a saved host entry also does not remove key
authorizations.

The review can show the requesting application, working context, and destination. Application and
process details are reported by the trusted desktop; they do not prove that the process is benign.
OpenSSH session binding can provide verified host-key context when supported.

### Listing and removing identities

`notisync-ssh-agent keys` shows cached entries per provider. `ssh-add -L` and `ssh-add -l` show the
active identities available to SSH clients. An identity-list request triggers a best-effort inventory
refresh while returning the current cache, so a newly added phone key may appear on a later listing.

`ssh-add -d PUBLIC_KEY_FILE` hides one identity from this desktop agent; `ssh-add -D` hides all
currently offered identities. Neither deletes the private keys on the phone. Delete a phone key
through the provider UI when that is your intent. Removing a passkey identity from NotiSync does
not delete the underlying credential from its credential provider.

## Lifecycle and troubleshooting

```bash
notisync-ssh-agent status
notisync-ssh-agent config show
notisync-ssh-agent stop
```

`status`, `env`, configuration, and offline key inspection do not start the daemon merely to
inspect it. `start` and `doctor` start the daemon when needed. Restart the agent after changing
its endpoint mode.

If signing fails, confirm the selected endpoint, provider connectivity, advertised public key,
and phone request history. Agent logs are named `notisync-ssh-agent.log` in the
[platform log directory](desktop.md#data-logs-and-updates); they are separate from daemon logs.

| Symptom | Check |
| --- | --- |
| No keys in `ssh-add -L` | Check the endpoint, mutual own-device trust, provider readiness, and current inventory. Retry the listing after a refresh. |
| `identity is not in the active provider cache` | Compare the requested public key with active identities; cached per-provider rows can include inactive entries. |
| `no eligible key provider` | The cached key has no currently eligible provider. Check the phone's connectivity and provider status. |
| `ssh-add` import fails | Select an active default provider, check the key format, and review the phone's import history. |
| `sign request timed out` or `all providers failed` | Check the phone's request history for expiry, connectivity, key-storage, or user-verification errors. |
| `Couldn't get agent socket` during Git signing on Windows | Check `gpg.ssh.program` and the signing process's agent environment; use native Windows `ssh-keygen.exe`. |
| Signing succeeds but login fails | Confirm that the remote server authorizes that public key for the intended user. |
| Passkey `clientDataJSON` format error | The credential provider's response format is incompatible with OpenSSH; inspect the phone's reported error. |

The cache lives at `~/.notisync/state/notisync-ssh-agent.db` on POSIX and under
`%LOCALAPPDATA%\NotiSync\state\` on Windows. It contains desktop inventory state; do not delete
phone key storage or the entire daemon data directory to refresh it. The
[troubleshooting reference](../notisyncd/src/main/resources/net/extrawdw/notisync/skills/notisync-ssh-agent/references/troubleshooting.md)
has additional error and recovery details.

### Test signing without logging in

After listing identities, you can test one selected public key with:

```bash
ssh-add -T /absolute/path/to/notisync.pub
```

This performs a real sign-and-verify operation and can prompt on the phone; it does not log in to
a server. See the [ssh-add manual](https://man.openbsd.org/ssh-add). On POSIX, the repository's
[smoke-test script](../scripts/test-notisync-ssh-agent.sh) performs one such request per advertised
identity, so expect a separate approval for each key whose policy requires it.

## Return to a previous agent

Restore the previous `SSH_AUTH_SOCK` or remove the NotiSync-specific `IdentityAgent` entry from the
affected host configuration. Restore any Git settings changed for this setup, including
`core.sshCommand`, `gpg.format`, `gpg.ssh.program`, and `user.signingKey`, while keeping your intended
signing policy. Then stop NotiSync SSH Agent if it is no longer needed. Other NotiSync features can
continue using the peer daemon.
