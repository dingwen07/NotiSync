# Troubleshooting

[Documentation](README.md) · [Getting started](getting-started.md) · [Desktop tools](desktop.md)

## Notifications do not arrive

Check the sending device first, then the recipient and broker:

1. On Android, grant notification access and enable the source app under **Apps**. Generate a new
   notification from that app; permission alone does not select apps for mirroring.
2. Confirm both devices trust one another and use the same broker URL. A one-way pairing exchange
   may leave the other device pending.
3. Confirm the recipient has permission to display notifications and that its device/app filters
   allow the notification.
4. For an iPhone Bluetooth source, keep the Android bridge nearby and check Bluetooth notification
   sharing. The iOS app itself does not capture other apps' notifications.
5. For a custom broker, check `/healthz` and `/v2/status`. If foreground delivery works but background
   delivery does not, check [FCM/APNs configuration](self-hosting.md), credentials, and app identifiers.

Actions and replies depend on what the original app exposes. They may be absent even when basic
notification mirroring works.

## Pairing or authentication fails

For NFC, keep the pairing screen open only on the scanning Android phone, with the other phone
unlocked and its pairing screen closed. If a Chinese OEM's default wallet blocks HCE, use the
affected phone as the NFC reader or pair by QR code. See the [NFC pairing guide](getting-started.md#nfc).

Verify automatic date/time on both devices: pairing data and signed requests include timestamps.
Check that the clients and broker use the current NS2 protocol. If the broker requires integrity,
confirm that it accepts the app's Firebase project/app ID and the build's attestation provider.
See [development setup](development.md#android) for debug builds.

## Desktop commands or delivery fail

Confirm JDK 21+ and the [installed command directory](desktop.md#install) are available, then inspect:

```bash
notisync status
notisync devices list
notisync applications list
```

`notisync status` does not start the daemon. Use `notisync daemon start` explicitly when needed.
For more detail, set `notisyncd config set log-level info` and inspect the
[platform log location](desktop.md#data-logs-and-updates). Reinstall from an updated checkout if
the tools and mobile app are on different versions.

For remote signing, use `notisync-gpg doctor` or `notisync-ssh-agent doctor` and check the phone's
request history. See [Seal](seal.md) and
[SSH Agent troubleshooting](ssh-agent.md#lifecycle-and-troubleshooting).

## Garbled SSH key names in Windows PowerShell

If `ssh-add -l` or `ssh-add -L` displays garbled non-ASCII key comments, configure PowerShell to
interpret Windows OpenSSH's output as UTF-8:

```powershell
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
ssh-add -l
```

This works in Windows PowerShell 5.1 and PowerShell 7. Add the encoding assignment to your
PowerShell `$PROFILE` to apply it in future sessions; it also affects other native commands in
that session. NotiSync sends UTF-8 key comments, but an older console code page can render the
bytes incorrectly. The SSH agent protocol and stored comments do not need conversion.

## Report a problem

Open a [GitHub issue](https://github.com/dingwen07/NotiSync/issues) with the affected platforms,
app/tool versions, reproduction steps, and relevant redacted diagnostics. Mention whether you
use the default or a self-hosted broker. Do not include private keys, push credentials, tokens,
or personal notification content.
