#!/bin/sh
# Relay gpg invocations from WSL to the Windows NotiSync gpg bridge.
# git's gpg.program must be a single executable, so this wrapper uses WSL
# interop (cmd.exe) to run the Windows .cmd script with all args forwarded.
exec /mnt/c/Windows/System32/cmd.exe /d /c 'C:\Users\wangd\AppData\Local\Microsoft\WindowsApps\notisync-gpg.cmd' "$@"
