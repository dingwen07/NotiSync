---
name: notisync-seal
description: Set up and troubleshoot NotiSync Seal OpenPGP signing for Git commits and tags through `notisync-gpg` and Android/OpenKeychain. Preserve the user's GPG configuration and fail closed when signing is rejected.
---

# NotiSync Seal

NotiSync Seal is a remote OpenPGP signing path for Git commit objects and annotated tag objects. Git invokes the desktop `notisync-gpg` adapter; the adapter uses the user's separately installed real GPG to resolve the public certificate, sends a bounded signing request to an eligible trusted own Android device, and verifies the returned detached signature before giving it to Git.

The desktop needs the matching public certificate, not the remote private key. Android uses OpenKeychain for private-key storage, passphrases, and signing interaction.

## Keep the GPG roles distinct

- **Real GPG** remains the user's ordinary GPG executable and public keyring.
- **`notisync-gpg`** is the Git-facing adapter configured for selected Git OpenPGP signing calls.
- **NotiSync Seal** is the Android review and remote-signing feature.
- **`gpg-agent`/smartcard** remain independent. Do not stop them, change their program, remove a signing key, or disable Git signing merely because a Seal request failed.

Unsupported or unrecognized GPG invocations are delegated unchanged to the configured real GPG. Recognized remote Git signing fails closed: it never silently falls back to a local secret key after timeout, rejection, provider failure, or invalid response.

## During a signing operation

Every Seal-routed Git signing request writes the normal `NotiSync Seal: compare hash ... on your phone` verification prompt to an available controlling terminal. This line is expected and is not an error. IDE or headless invocations without a controlling terminal can omit the prompt even though the phone request is still valid.

Pause for the user to compare the displayed seven-character payload hash with the phone and approve or reject there. The request prefix identifies the interaction but is not the comparison value. Do not reproduce a real comparison value in documentation or logs, approve on the user's behalf, suppress the comparison, cancel prematurely, or start parallel retries.

The phone review presents parsed commit or annotated-tag facts and the exact payload hash. A commit review does not show or claim to show the code diff. The working directory is authenticated requester-reported context but is not part of the signed Git object or OpenPGP signature.

If signing cannot complete, leave the Git operation unchanged and report the terminal reason. Never retry with unsigned commits, `--no-gpg-sign`, a different GPG program, or local fallback unless the user explicitly requests that change. Read [references/errors.md](references/errors.md) for exact rejection meanings and response behavior.

## Supported routing

Remote routing recognizes an unambiguous full primary fingerprint or 16-digit primary/signing-subkey long ID. A full primary fingerprint is preferred. Exact-subkey selectors ending in `!`, short IDs, email selectors, implicit/default selection, verification, encryption, lightweight tags, and other GPG operations go to real GPG.

Annotated tag requests require both a desktop adapter and Android provider version that advertise tag review support. Lightweight tags have no tag object to sign and are outside this path.

Read [references/setup.md](references/setup.md) before changing Git configuration or diagnosing why an invocation delegated to local GPG.

## Verification after success

Confirm that Git created the intended object and that real GPG can validate it:

```bash
git verify-commit HEAD
git verify-tag TAG_NAME
```

Use the command appropriate to what was created. Do not treat a successful phone interaction alone as proof; the adapter also verifies the exact returned signature, and Git must successfully write the object.
