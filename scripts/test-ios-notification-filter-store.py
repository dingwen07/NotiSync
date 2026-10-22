#!/usr/bin/env python3
"""Test the production iOS notification filter store on macOS, without a device.

Usage: python3 scripts/test-ios-notification-filter-store.py [--baseline-ref REV]
The optional revision must precede the read-lock fix. It provides a controlled
comparison using that revision's actual store, not a reconstructed old algorithm.
No app data, signing material, or network services are used. All build caches and
files live in a temporary directory. These tests do not establish iOS suspension
behavior or validate App Group entitlements; those require separate device testing.
"""

import argparse
import os
from pathlib import Path
import platform
import selectors
import subprocess
import tempfile
import time


REPO = Path(__file__).resolve().parents[1]
STORE = "ios/NotiSyncKit/SharedStore.swift"
MODELS = "ios/NotiSyncKit/ProtocolModels.swift"
FIXTURE = REPO / "ios/Tests/NotificationFilterStoreHostTests.swift"
CHILDREN = []


def source_at(path, revision=None):
    if revision:
        return subprocess.check_output(["git", "show", f"{revision}:{path}"], cwd=REPO, text=True)
    return (REPO / path).read_text()


def compile_probe(build_root, revision=None):
    import re

    source = source_at(STORE, revision)
    # Assert explicit boundaries so a production reorganization fails loudly rather
    # than silently testing a stale fixture or excluding part of the filter store.
    markers = (
        "nonisolated enum AppGroupStore {",
        "nonisolated enum NotificationFilterStore {",
        "\n/// Held signed client cards (`SignedBlob` CBOR, keyed by clientId), in the App Group.",
    )
    assert all(source.count(marker) == 1 for marker in markers), "SharedStore extraction markers changed"
    assert source.index(markers[0]) < source.index(markers[1]) < source.index(markers[2])
    source = source[:source.index(markers[2])]
    resolver = r"FileManager\.default\.containerURL\(\s*forSecurityApplicationGroupIdentifier:\s*NotiSyncConfig\.appGroup\s*\)"
    source, replacements = re.subn(resolver, "ProbeResolver.resolve()", source)
    assert replacements == 1, "Expected one App Group URL resolver substitution"
    build_root.mkdir()
    extracted = build_root / "SharedStore.swift"
    extracted.write_text(source)
    model_source = source_at(MODELS, revision)
    model_end = "\nnonisolated struct FilterSync: Sendable {"
    assert model_source.count(model_end) == 1, "ProtocolModels extraction marker changed"
    assert "nonisolated struct NotificationFilterRule: Sendable {" in model_source[:model_source.index(model_end)]
    # Keep the real filter/wire types, excluding unrelated SSH/KMP adapters below.
    models = build_root / "ProtocolModels.swift"
    models.write_text(model_source[:model_source.index(model_end)])
    binary = build_root / "store-tests"
    subprocess.run([
        "xcrun", "swiftc", "-swift-version", "5", "-parse-as-library",
        "-module-cache-path", str(build_root / "modules"),
        str(extracted), str(models), str(FIXTURE), "-o", str(binary),
    ], cwd=REPO, check=True, timeout=120)
    return binary


def start(binary, root, *args, nil_resolutions=0):
    env = dict(os.environ, STORE_TEST_ROOT=str(root), STORE_TEST_NIL_RESOLUTIONS=str(nil_resolutions))
    child = subprocess.Popen(
        [str(binary), *map(str, args)], env=env, text=True,
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
    )
    CHILDREN.append(child)
    return child


def expect_line(child, expected, timeout=5):
    with selectors.DefaultSelector() as selector:
        selector.register(child.stdout, selectors.EVENT_READ)
        assert selector.select(timeout), f"Timed out waiting for {expected!r}"
    actual = child.stdout.readline().strip()
    assert actual == expected, f"Expected {expected!r}, received {actual!r}"


def release(child):
    child.stdin.write("continue\n")
    child.stdin.flush()


def finish(child, timeout=20):
    out, err = child.communicate(timeout=timeout)
    assert child.returncode == 0, f"Child failed ({child.returncode}): {out}\n{err}"
    return out


def run(binary, root, *args, **kwargs):
    return finish(start(binary, root, *args, **kwargs))


def directory(parent, name):
    result = parent / name
    result.mkdir()
    return result


def lock_comparison(binary, root, baseline=None):
    run(binary, root, "seed", "A")
    holder = start(binary, root, "hold")
    try:
        expect_line(holder, "LOCKED")
        reader = start(binary, root, "read")
        expect_line(reader, "READY")
        finish(reader, timeout=3)
        assert holder.poll() is None, "reader completed only after holder exited"
        print("PASS patched preferences read completes while another process holds the store lock", flush=True)
        if baseline:
            old_reader = start(baseline, root, "read")
            expect_line(old_reader, "READY")
            try:
                old_reader.wait(timeout=0.3)
            except subprocess.TimeoutExpired:
                pass
            else:
                raise AssertionError("Baseline reader did not block; choose a revision before the read-lock fix")
            release(holder)
            finish(holder)
            finish(old_reader, timeout=3)
            print("PASS baseline preferences read blocks until the external lock is released", flush=True)
    finally:
        if holder.poll() is None:
            release(holder)
            finish(holder)


def atomic_replacement(binary, root):
    run(binary, root, "seed", "A")
    writer = start(binary, root, "atomic-writer", 600)
    readers = [start(binary, root, "atomic-reader") for _ in range(3)]
    for child in [writer, *readers]:
        expect_line(child, "READY")
    for reader in readers:
        release(reader)
    release(writer)
    finish(writer)
    for reader in readers:
        finish(reader)
    print("PASS atomic replacements expose only complete old/new snapshots to three reader processes", flush=True)


def concurrent_setters(binary, root):
    workers, updates = 4, 20
    children = [start(binary, root, "setter", f"worker-{index}", updates) for index in range(workers)]
    for child in children:
        expect_line(child, "READY")
    for child in children:
        release(child)
    for child in children:
        finish(child)
    run(binary, root, "verify-setters", workers, updates)
    print("PASS four writer processes preserve every Android, app, channel, and iOS filter update", flush=True)


def cache_replacement(binary, root):
    run(binary, root, "seed", "A")
    reader = start(binary, root, "cache-reader")
    expect_line(reader, "WARM")
    path = root / "notification-filters.json"
    previous = path.stat()
    run(binary, root, "seed", "B")
    # Preserve size and timestamps to specifically exercise the inode component of
    # the production cache stamp, rather than passing due to a newer mtime alone.
    os.utime(path, ns=(previous.st_atime_ns, previous.st_mtime_ns))
    replacement = path.stat()
    assert replacement.st_ino != previous.st_ino, "Fixture did not replace the inode"
    assert replacement.st_size == previous.st_size, "Fixture snapshots must have equal encoded sizes"
    assert replacement.st_mtime_ns == previous.st_mtime_ns, "Fixture must preserve the original mtime"
    release(reader)
    finish(reader)
    print("PASS decoded cache observes another process's atomic inode replacement", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline-ref", help="Git revision before the preferences read-lock fix")
    args = parser.parse_args()
    if platform.system() != "Darwin":
        parser.error("macOS with the Xcode Swift toolchain is required")
    with tempfile.TemporaryDirectory(prefix="notisync-filter-store-") as temp:
        root = Path(temp)
        started = time.monotonic()
        try:
            current = compile_probe(root / "current")
            baseline = compile_probe(root / "baseline", args.baseline_ref) if args.baseline_ref else None
            lock_comparison(current, directory(root, "locks"), baseline)
            atomic_replacement(current, directory(root, "atomic"))
            concurrent_setters(current, directory(root, "setters"))
            cache_replacement(current, directory(root, "cache"))
            run(current, directory(root, "resolver"), "resolver-success")
            print("PASS successful container URL lookup is cached across sequential reads and writes", flush=True)
            run(current, directory(root, "retry"), "resolver-retry", nil_resolutions=1)
            print("PASS failed container URL lookup is retried and a later success is cached", flush=True)
            bad_root = root / "not-a-directory"
            bad_root.write_text("force lock-directory creation failure")
            run(current, bad_root, "fallback")
            print("PASS fallback resolution does not deadlock; required-lock failure remains closed", flush=True)
            print(f"All notification-filter host checks passed in {time.monotonic() - started:.1f}s.")
        finally:
            # Release every child even on assertion/timeout; process exit also drops flock.
            for child in CHILDREN:
                if child.poll() is None:
                    child.terminate()
                    try:
                        child.wait(timeout=2)
                    except subprocess.TimeoutExpired:
                        child.kill()
                        child.wait(timeout=2)


if __name__ == "__main__":
    main()
