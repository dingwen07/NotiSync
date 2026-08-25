# Configuration and privacy

## Commands

```text
nsrun config get
nsrun config set updateInterval 30s
nsrun config set stuckAfter 5m
nsrun config set stuckAfter off
nsrun config set pty auto|always|never
nsrun config set logRetentionDays DAYS
nsrun config set logMaxBytes BYTES
nsrun config set llm BASE_URL MODEL API_KEY
nsrun config set llm.clear
```

Durations use integer `s`, `m`, `h`, or `d` suffixes. The update interval must be 5 seconds through 1 day. Stuck detection must be `off` or 10 seconds through 7 days. Stuck detection is based on silence; direct terminal wait detection remains available when silence-only detection is disabled.

The configuration is `~/.notisync/nsrun.conf` on POSIX. Private Run logs live under `~/.notisync/runs/` and default to 30-day/100-MiB retention constraints. A malformed ordinary runtime configuration is moved aside and replaced with safe defaults so the requested child can still run; explicit `config` commands remain strict so the user can diagnose and repair it.

## Data boundaries

Command arguments, working directory, terminal output projection, process state, and prompt/control context can be sent end-to-end encrypted to trusted own NotiSync devices. Local private logs also capture the Run projection. Decide whether the wrapped operation is suitable for those destinations before starting it.

`--llm` is separately opt-in and valid only after configuring an OpenAI-compatible endpoint. It may send capped command/output and bounded working-tree context to that configured external service. The API key is stored in the private Run configuration and is never daemon configuration. Do not enable `--llm` or store a credential without the user's request.

## Troubleshooting

- `missing command`: pass the child command after the Run options, preferably after `--`.
- `unknown option`: an option intended for the child was parsed by `nsrun`; insert `--`.
- `--llm requires LLM settings`: configure all endpoint/model/key values or omit `--llm`.
- Run is absent on native Windows: this is a packaging boundary, not a `PATH` bug. Use Linux/macOS or a separately installed WSL environment.
- A stale `nsrun` application registration can be inspected with `notisync applications list` and removed with `notisync applications remove nsrun`; removal is cleanup, not a routine reporting fix.
