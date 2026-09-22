#!/usr/bin/env python3
"""Read a debug-build operational SQLite snapshot through adb, preserving binary output."""

import argparse
from contextlib import closing
from pathlib import Path
import re
import sqlite3
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb", help="Path to adb")
    parser.add_argument("--serial", help="Device serial (required when multiple devices are connected)")
    parser.add_argument("--package", default="net.extrawdw.apps.notisync")
    parser.add_argument("--output", required=True, type=Path, help="New plaintext SQLite file; never overwritten")
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+", args.package):
        parser.error("Invalid Android package name")
    command = [args.adb]
    if args.serial:
        command += ["-s", args.serial]
    command += [
        "exec-out", "content", "read", "--uri",
        f"content://{args.package}.debug.database/operational",
    ]
    try:
        # Exclusive creation protects an existing export. Direct bytes avoid PowerShell encoding.
        output = args.output.open("xb")
    except OSError as error:
        parser.exit(1, f"Cannot create output: {error}\n")
    try:
        with output:
            result = subprocess.run(command, stdout=output, stderr=subprocess.PIPE, check=False)
        with args.output.open("rb") as exported:
            valid_header = exported.read(16) == b"SQLite format 3\0"
        if result.returncode or not valid_header:
            raise RuntimeError(result.stderr.decode("utf-8", errors="replace").strip() or
                               "No SQLite snapshot returned. Check the device and installed debug build.")
        with closing(sqlite3.connect(args.output.resolve().as_uri() + "?mode=ro", uri=True)) as database:
            if database.execute("PRAGMA quick_check").fetchall() != [("ok",)]:
                raise RuntimeError("The exported database failed its integrity check")
    except (OSError, RuntimeError, sqlite3.Error, KeyboardInterrupt) as error:
        args.output.unlink(missing_ok=True)
        parser.exit(1, f"Export failed: {error}\n")
    print(f"Saved plaintext database to {args.output.resolve()}")


if __name__ == "__main__":
    main()
