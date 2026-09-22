# NotiSync Seal

[Documentation](README.md) · [Desktop setup](desktop.md) · [Troubleshooting](troubleshooting.md)

NotiSync can use a trusted Android device and OpenKeychain to approve ordinary OpenPGP-signed Git
commits and annotated tags. The desktop still needs the public certificate and a real GPG installation.
The `notisync-gpg` adapter uses that GPG to resolve the requested certificate, delegates every
unsupported operation unchanged, and verifies the returned detached signature before Git receives it.
No private OpenPGP key is required on the desktop for the remotely selected certificate.

Before starting, [install the desktop tools and pair the phone as an own device](desktop.md#pair-with-a-phone).

## Set up signing

1. Confirm that the real GPG executable is available on `PATH`, then check the adapter:

   ```bash
   command -v gpg
   notisync-gpg doctor
   ```

   On Windows PowerShell:

   ```powershell
   Get-Command gpg.exe
   notisync-gpg doctor
   ```

   The adapter uses `gpg` (`gpg.exe` on Windows) from `PATH` by default. Only when GPG is not
   available on `PATH`, configure an absolute fallback with
   `notisync-gpg config set-real-gpg ABSOLUTE_PATH`. Symbolic links are accepted and resolved.

2. Install OpenKeychain on the Android device, import the private certificate there, then open
   **Tools > Seal** in NotiSync and select that certificate. OpenKeychain remains responsible for
   private-key storage, passphrases, and its approval interaction.

3. Keep the matching public certificate in the desktop GPG keyring. Configure Git with the adapter's
   absolute path and preferably the full primary fingerprint:

   ```bash
   git config --global gpg.format openpgp
   git config --global gpg.openpgp.program "$(command -v notisync-gpg)"
   git config --global user.signingKey FULL_PRIMARY_FINGERPRINT
   git config --global commit.gpgSign true
   git config --global tag.gpgSign true
   ```

   On Windows PowerShell, use
   `git config --global gpg.openpgp.program (Get-Command notisync-gpg.cmd).Source` for the second
   command. A 16-digit primary or signing-subkey long ID is also accepted, but a full fingerprint is
   less ambiguous. Git's `-S` override is honored because the adapter uses Git's final selector.

## What gets signed

Seal signs commit objects and annotated tag objects. Exact-subkey selectors ending in `!`, lightweight
tags, short IDs, email selectors, verification, encryption, and all other GPG invocations go directly
to the configured real GPG. A recognized remote request fails closed on timeout, rejection, provider
failure, or an invalid response; it never silently falls back to local signing. The phone review shows
the exact commit or tag facts and payload hash; commit review does not contain or claim to show the code
diff. It also shows the desktop process's working directory as requester-reported context; that path is
authenticated as coming from the trusted device but is not part of the Git object or its OpenPGP
signature.
Tag requests are routed only to Android clients that advertise annotated-tag review support, so update
both the desktop tools and the Android app before using remote tag signing.

## Verify a request

When Git is run from an interactive terminal, `notisync-gpg` prints a seven-character hash directly to
that controlling terminal. Compare it with the hash in Seal before approving. The adapter
never adds this message to stdout, which remains reserved for the detached signature required by Git;
headless and IDE invocations without a controlling terminal simply omit the message.

## Roll back

To roll back, restore the previous `gpg.openpgp.program` value (or run
`git config --global --unset gpg.openpgp.program`) and leave `user.signingKey` pointing at the desired
local key.
