# NotiSync Seal setup

## Prerequisites

1. Install NotiSync Desktop and pair an Android device as a trusted own device.
2. Install a real GPG implementation on the desktop.
3. Install OpenKeychain on Android, import the private certificate there, then select it under **Tools -> Seal** in NotiSync.
4. Import the matching public certificate into the desktop real-GPG keyring.

Record the real GPG path before pointing Git at the adapter.

POSIX:

```bash
real_gpg="$(command -v gpg)"
notisync-gpg config set-real-gpg "$real_gpg"
notisync-gpg doctor
git config --global gpg.format openpgp
git config --global gpg.openpgp.program "$(command -v notisync-gpg)"
git config --global user.signingKey FULL_PRIMARY_FINGERPRINT
git config --global commit.gpgSign true
git config --global tag.gpgSign true
```

Windows PowerShell:

```powershell
$realGpg = (Get-Command gpg.exe).Source
notisync-gpg config set-real-gpg $realGpg
notisync-gpg doctor
git config --global gpg.format openpgp
git config --global gpg.openpgp.program (Get-Command notisync-gpg.cmd).Source
git config --global user.signingKey FULL_PRIMARY_FINGERPRINT
git config --global commit.gpgSign true
git config --global tag.gpgSign true
```

Use repository-local Git configuration instead of `--global` when only one repository should use Seal. Inspect existing values before overwriting them, especially `gpg.openpgp.program`, `gpg.program`, `user.signingKey`, `commit.gpgSign`, and `tag.gpgSign`.

`notisync-gpg doctor` verifies the stored real-GPG executable and obtains a live NotiSync daemon identity, starting `notisyncd` when needed. Its `Fallback: fail closed` result describes recognized remote signing; it does not mean every GPG operation is remote.

## Platform notes

- Windows Git must receive the absolute `.cmd` adapter shim path.
- POSIX should use the absolute result of `command -v` so Git does not depend on a different noninteractive `PATH`.
- On macOS, pinentry or smartcard UI usually means the invocation delegated to real GPG. Check the selector and final Git configuration rather than assuming Seal failed to notify.
- WSL has separate executables, home, GPG keyring, Git configuration, and NotiSync data. A Windows-side adapter path is not a WSL executable.

## Rollback

Restore the previous `gpg.openpgp.program` value, or unset only that value if there was none:

```bash
git config --global --unset gpg.openpgp.program
```

Do not delete the public certificate or change `user.signingKey` unless that is part of the user's intended rollback.
