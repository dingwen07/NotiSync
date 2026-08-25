---
name: notisync-ssh-agent
description: Set up and troubleshoot NotiSync SSH Agent on Windows, macOS, Linux, or WSL, including Android-backed SSH and Git signing. Preserve the user's configured SSH agent unless asked to replace it.
---

# NotiSync SSH Agent

`notisync-ssh-agent` is an optional standard SSH agent endpoint whose public identity cache is on the desktop and whose signing providers are trusted own NotiSync devices, normally Android. The private signing key remains with its Android storage/provider policy; the desktop verifies every returned SSH signature.

It is separate from:

- OpenSSH `ssh-agent` and the Windows OpenSSH Authentication Agent service;
- Pageant, gpg-agent's SSH support, 1Password, or another configured agent;
- Git's OpenPGP program and NotiSync Seal;
- `notisyncd`, which transports encrypted feature messages but does not implement the SSH agent protocol.

Before setup, inspect the current `SSH_AUTH_SOCK`, SSH `IdentityAgent`, running agent/service, and Git SSH-signing configuration. Do not stop, replace, or reconfigure an existing agent merely because NotiSync SSH Agent is installed. Make the switch only when the user requests NotiSync-backed SSH or a scoped test.

## Lifecycle and inspection

```text
notisync-ssh-agent start
notisync-ssh-agent status
notisync-ssh-agent env
notisync-ssh-agent doctor
notisync-ssh-agent keys
notisync-ssh-agent stop
notisync-ssh-agent config show
```

`start`, foreground runtime, and `doctor` start `notisyncd` on demand. `status`, `stop`, `env`, configuration, and offline `keys` do not start it merely for inspection.

`keys` prints physical cached rows by fingerprint, comment, and provider device. Standard `ssh-add -L`/`-l` sees the active aggregate identities. An `ssh-add` identity-list request triggers a best-effort inventory refresh but returns the current cache immediately, so a just-added phone key may appear on a later listing.

## Signing behavior

An SSH client asks the local endpoint for a signature. The agent sends the request to every eligible provider that advertises that exact cached public key. The first valid signature wins. An explicit user rejection is terminal for the request; provider failures are tolerated only while another eligible provider remains. The overall request times out by default after 120 seconds.

The phone may show requester process and destination/host context. This context is useful for review but is requester-reported; it is not a proof that the named process is benign. OpenSSH session binding can provide verified host-key context where supported.

Keys set to **Always ask** ignore remembered authorization for automatic approval, but stored remembered grants remain dormant rather than being deleted. **Forget** is the deletion action. Passkey/WebAuthn-backed SSH identities always require provider interaction and are not eligible for remembered authorization.

When an SSH or Git operation cannot complete, preserve the user's command/configuration and report the failure. Do not transparently fall back to another agent or key, retry an explicit rejection, disable signing, or change `SSH_AUTH_SOCK` globally. Standard SSH protocol failures often collapse provider details into a generic signing failure; use the Android request history plus the layered checks in [references/troubleshooting.md](references/troubleshooting.md).

## Setup routing

Read [references/setup.md](references/setup.md) before configuring endpoints. It covers:

- POSIX `SSH_AUTH_SOCK` and `IdentityAgent`;
- Windows named pipes and AUTO fallback;
- Git for Windows versus native Windows OpenSSH;
- Git SSH signing's separate `gpg.ssh.program`;
- WSL2 boundaries;
- Android key/provider readiness.

Use `notisync-ssh-agent env` after startup and trust the endpoint reported by the running instance rather than assuming a default.
