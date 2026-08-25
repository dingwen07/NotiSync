---
name: notisync
description: Use and troubleshoot the NotiSync `notisync` CLI for daemon status, device trust, application registrations, quarantine recovery, and agent skill management. Use dedicated Seal or SSH Agent skills for signing.
---

# NotiSync CLI

`notisync` administers this user's NotiSync Desktop identity through the owner-private local API exposed by `notisyncd`. It is not GPG, an SSH client, or an SSH agent, and it does not replace any of those programs.

## Operational model

- `notisync status`, `notisync daemon`, and `notisync daemon status` only inspect the daemon. They intentionally do not start it.
- `config`, `devices`, `applications`, and `quarantine` need the live API and start `notisyncd` on demand when possible.
- `notisync daemon start|stop|restart` is explicit lifecycle control.
- `notisync skills` is local file management. It neither needs nor starts the daemon.
- Treat pairing and trust changes as security decisions. Inspect the candidate or device list before accepting, approving, revoking, purging, or clearing quarantine.

## Command map

```text
notisync status
notisync daemon [status|start|stop|restart]
notisync config get
notisync config set device-name NAME
notisync devices [list]
notisync devices pair show [--payload]
notisync devices pair inspect LINK|PAYLOAD|-
notisync devices pair accept [--own|--other] LINK|PAYLOAD|-
notisync devices action ACTION DEVICE_ID
notisync devices action approve --all
notisync applications [list]
notisync applications remove APPLICATION_ID
notisync quarantine approve|clear
notisync skills [add|remove|list]
```

Device actions take the action first and device ID second. `approve --all` is deliberately limited to pending approvals; never substitute it for a targeted action unless the user asked to approve every pending device.

Pairing is mutual. The desktop code or QR contains public material, but scanning only one direction does not finish trust on both devices. Prefer `pair inspect` before `pair accept`, and use `--own` only for a device controlled by the same user. Read [references/setup-and-platforms.md](references/setup-and-platforms.md) for installation, platform paths, pairing, and skill-management details.

## Failure handling

- `notisync: notisyncd is not running` from `status` is a status result, not evidence that installation is broken. Start it explicitly or run an operational command if that matches the user's goal.
- If an operational command says the daemon executable was not found, verify the desktop installation and `PATH`; do not install skills, change GPG, or replace `SSH_AUTH_SOCK` as a workaround.
- A trust-store quarantine is not an ordinary stale-device problem. Inspect status and device state before choosing `quarantine approve` or `clear`; those actions have different trust consequences.
- Application registrations such as `nsrun`, `notisync-gpg`, and `notisync-ssh-agent` are NotiSync local-API consumers. Removing one does not uninstall its executable or change Git/SSH configuration.

## Agent skills

Manage bundled skills with these commands:

```text
notisync skills list
notisync skills list --long
notisync skills add notisync-seal
notisync skills add --all
notisync skills remove notisync-seal
```

By default, `skills add` installs to the portable common agent directory and detected supported agents. Use `--agent=common,codex` to select targets or `--project=/absolute/project` for project-local installation.
