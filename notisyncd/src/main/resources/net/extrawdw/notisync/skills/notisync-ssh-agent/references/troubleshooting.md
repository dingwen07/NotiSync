# NotiSync SSH Agent troubleshooting

## Layered checks

Run these without replacing the user's existing agent configuration:

```text
notisync-ssh-agent status
notisync-ssh-agent doctor
notisync-ssh-agent keys
notisync-ssh-agent env
notisync status
```

Then, in a shell explicitly pointed at the reported endpoint, use `ssh-add -L` or `ssh-add -l`. Keep these layers distinct:

1. desktop SSH Agent process and endpoint;
2. NotiSync daemon identity/broker connection;
3. trusted own provider capability and current inventory;
4. local client actually using that endpoint;
5. phone approval, key policy, user verification, and signing provider;
6. remote SSH authentication or Git signing policy after a signature succeeds.

## Common desktop outcomes

- `notisync-ssh-agent is not running`: status result; starting it is a separate action.
- `already running`: use the reported endpoint rather than starting a competing instance.
- fixed Windows pipe conflict: AUTO should fall back to the private pipe; `openssh-compatible` deliberately fails. Check the running Windows OpenSSH agent/service before changing modes.
- `identity is not in the active provider cache`: the client requested a key not currently advertised by an active provider. Compare `keys` with `ssh-add -L`, check provider connectivity, and allow a deliberate refresh.
- `no eligible key provider`: a cached identity has no currently eligible provider.
- `sign request timed out`: no acceptable provider result arrived before the request deadline. The phone request may have expired or connectivity was lost.
- `all providers failed`: every eligible provider returned a provider failure; inspect Android request history for the specific cause.
- generic `sign_and_send_pubkey: signing failed` or Git `Couldn't find key in agent`: confirm the exact public key and endpoint before changing authorization or retrying.
- `Couldn't get agent socket` on Windows Git SSH signing: `gpg.ssh.program` likely resolved to Git for Windows' bundled `ssh-keygen`; use the native Windows executable when that is the user's intended setup.

Provider failure codes include `NOT_OWNER`, `KEY_NOT_FOUND`, `UNSUPPORTED_ALGORITHM`, `UNSUPPORTED_FLAGS`, `KEY_INVALIDATED`, `USER_VERIFICATION_CANCELLED`, `USER_VERIFICATION_LOCKOUT`, `REQUEST_EXPIRED`, `PROVIDER_BUSY`, and `INTERNAL_FAILURE`. Standard SSH clients may not display these codes; the Android history is the primary user-facing source.

An explicit Android rejection is terminal. A biometric/passkey cancellation is a provider failure, not authorization to retry indefinitely. Report it and wait for the user to request another attempt.

## Cache and provider selection

`notisync-ssh-agent keys` shows every physical cached provider row, including duplicate public keys on different devices and inactive-provider rows. `ssh-add -L` shows active aggregate identities. A difference between them can therefore be correct.

When the agent is running, `keys` queries its live database connection through a private extension. When stopped, it can read the disposable desktop cache offline. It does not start `notisyncd` simply to resolve device names; unresolved rows fall back to client IDs.

The desktop cache may be moved into the private recovery directory and recreated when it is unreadable. That does not delete Android private keys. Do not delete Android SSH storage or the desktop data directory as ordinary cache refresh.

## Logs and paths

- POSIX data/config/cache: `~/.notisync/`, with cache at `~/.notisync/state/notisync-ssh-agent.db`
- Windows data/config/cache: `%LOCALAPPDATA%\NotiSync\`, with cache under `state\`
- agent log: the platform NotiSync log directory as `notisync-ssh-agent.log`

The desktop daemon log and SSH Agent log are separate. Startup error reporting uses only log bytes appended by the current attempt; when reading manually, do not mistake an old append-only error for the current failure.

## Test without causing an unintended login

Prefer identity listing or a purpose-built sign/verify probe before a production SSH connection. On POSIX repository checkouts, `scripts/test-notisync-ssh-agent.sh` performs one OpenSSH `ssh-add -T` sign-and-verify request for each advertised identity. Each such probe can create a real phone approval request; obtain the user's consent before running it.
