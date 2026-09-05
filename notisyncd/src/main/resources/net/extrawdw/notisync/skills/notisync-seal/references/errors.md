# NotiSync Seal errors

The comparison prompt emitted before these outcomes is normal for each Seal-routed interactive Git signing request. It is deliberately omitted from the examples below so real comparison values and request identifiers are not copied into agent instructions.

## Complete protocol rejection set

The protocol has exactly six stable rejection reasons:

| Reason | Meaning | Response |
| --- | --- | --- |
| `USER_REJECTED` | The phone's explicit Reject action was used. | Treat as a deliberate terminal decision. Do not retry without a new user request. |
| `PROVIDER_CANCELLED` | The Android review/provider interaction was canceled before a signature was produced. | Leave Git unchanged and ask the user whether to try again after they are ready. |
| `EXPIRED` | The bounded request lifetime ended. | Check phone availability, broker connection, and request timing before a deliberate retry. |
| `PROVIDER_UNAVAILABLE` | OpenKeychain or the selected provider could not service the request. | Confirm OpenKeychain is installed/enabled and Seal has an enrolled identity. |
| `UNSUPPORTED_KEY` | The selected OpenPGP key/provider cannot perform the requested signature. | Check the Android-selected certificate and update compatibility; do not switch selectors blindly. |
| `PROVIDER_FAILURE` | The provider failed without a safe signature. | Inspect the Android result and provider state; preserve fail-closed behavior. |

The corresponding Git failure has this shape, with one of the six reasons:

```text
error: gpg failed to sign the data:
notisync-gpg: signing request rejected (REASON)

fatal: failed to write commit object
```

For an amend, the existing `HEAD` remains the current commit when Git reports `failed to write commit object`. Report the reason and wait for the user; do not automatically repeat the request.

## Desktop adapter diagnostics

The stable rejection enum is not the whole failure surface. `notisync-gpg` also reports adapter, GPG, daemon, filesystem, process, and OS failures with the `notisync-gpg:` prefix.

Configuration and command diagnostics include:

- `configuration is missing; run 'notisync-gpg config set-real-gpg ABSOLUTE_PATH'`: GPG was not found on `PATH` and no fallback is configured.
- `real GPG path must be absolute`
- `real-gpg-path must be absolute`, `is not a regular file`, `is not executable`, or `must not resolve to notisync-gpg`
- invalid/missing/duplicate configuration options, bad quoting/escaping, timeout outside 30..300 seconds, payload bound outside its supported range, or any fallback other than `fail-closed`
- `real GPG failed its version check`
- `process timed out` or `process output exceeded safe bound`
- `signing interrupted`

Public-certificate resolution diagnostics include:

- `real GPG could not resolve the requested public certificate`
- `the selector is missing from the public keyring`
- `the selector is ambiguous in the public keyring`
- `real GPG did not report the primary fingerprint`
- `real GPG reported inconsistent primary certificate identity`
- `the selected certificate has no usable signing key`

Request and transport diagnostics include:

- `remote signing request expired`: desktop received no acceptable result before the deadline.
- `notisyncd did not respond within ... ms`: the local receive deadline elapsed; for a pending request this is another normal timeout surface.
- `notisyncd closed the signing response stream`: the local daemon ended the response stream before a terminal outcome.
- `notisyncd has no local client identity`: daemon exists but is not ready for authenticated requesting.
- `notisyncd is not running and its executable was not found`: desktop installation or `PATH` cannot supply the daemon launcher.
- `notisyncd did not become ready`: autostart launched or found the daemon but its local API never became ready.
- `Git signing payload exceeds configured bound`: the exact Git object is larger than the configured safe maximum.
- `refusing to send an invalid signing request`: an adapter invariant rejected the locally constructed request.
- local API errors such as daemon HTTP failures, malformed/truncated responses, connection refusal, socket closure, or response size/content-type bounds.
- owner/permission/path errors from validation of the private NotiSync data, config, or temporary verification files.
- JVM/OS process-launch diagnostics when real GPG or the daemon launcher cannot be executed. These messages are platform-provided and therefore are not a finite product enum.

## Invalid returned signatures

A result is accepted only after exact local verification. The complete adapter-owned verifier diagnostics are:

- `response is not an armored signature`
- `response has incomplete signature armor`
- `real GPG rejected the returned signature`
- `real GPG did not report exactly one valid signature`
- `real GPG returned a malformed VALIDSIG record`
- `real GPG returned an invalid signature timestamp`
- `returned signature is not a detached document signature`
- `returned signature belongs to a different primary certificate`
- `returned signature has a different primary key ID`
- `returned signature was created outside the request lifetime`

Those verifier exceptions are intentionally not returned directly to Git: the invalid response is ignored while the request waits for another valid provider result. If none arrives, the eventual visible outcome is a timeout. Do not interpret that timeout as proof that no phone replied.

Responses are also ignored when they are malformed, unauthenticated, not from an own device, addressed to another request/requester/key/object, outside clock bounds, or from a sender that does not match the authenticated record.

## Android-side states

Android setup can report that OpenKeychain is unavailable, the selected certificate is unsupported, or OpenKeychain could not complete setup. During review, the UI can show Seal not ready, request unavailable, request undisplayable/invalid, request changed, expired, canceled, rejected, or failed. When a terminal response reaches the desktop, these reduce to the six protocol reasons above; a local UI state may instead end with a desktop timeout if no acceptable response is delivered.

Additional routing clue:

- local pinentry/smartcard prompt: the call probably delegated to real GPG because the selector, payload, or operation was outside remote routing.
- delegated real-GPG errors normally pass through unchanged and may not carry a `notisync-gpg:` prefix. Absence of a Seal comparison prompt plus ordinary GPG output is evidence to inspect routing, not to classify the result as one of the six remote rejection reasons.

## Diagnose without weakening signing

1. Record the rejection/failure reason and Git exit without copying the per-request comparison value or request identifier.
2. Verify `notisync-gpg config show` and `notisync-gpg doctor`.
3. Inspect `git config --show-origin --get-regexp '^(gpg|user\.signingKey|commit\.gpgSign|tag\.gpgSign)'`.
4. Confirm the matching public certificate through the configured real GPG.
5. Check `notisync status` and that an eligible trusted own Android Seal provider is online.
6. Retry only when the user requests it and the previous request is terminal.

Never resolve one of these errors by disabling signing, creating an unsigned commit, or silently selecting a different identity.
