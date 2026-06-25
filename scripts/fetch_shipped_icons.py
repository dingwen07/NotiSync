#!/usr/bin/env python3
"""Populate the shipped app-icon pack from Apple's public iTunes Lookup API.

For every iOS bundle id known to BundleIdMap.kt, this fetches the real app icon (third-party AND nearly
all Apple first-party apps) and writes it as a compact WebP under app/src/main/assets/appicons/, keyed by
the lowercased bundle id — exactly the key ShippedIcons looks up at runtime.

The bundle-id list is parsed straight out of BundleIdMap.kt, so it can't drift from the runtime mapping.
Apple's artwork CDN re-renders to any size/format via the URL suffix, so we ask for `/256x256bb.webp`
directly (no re-encode). A few pure-OS surfaces (Settings, App Store) have no store entry and are reported
at the end — those ship as generic placeholders from generate_placeholder_icons.py instead.

Only the iOS icons are fetched here. These are PUBLIC App Store artwork, kept entirely separate from the
app's encrypted private-asset pipeline; bundling them is a deliberate, reviewable choice (run + commit).

Usage:
    python3 scripts/fetch_shipped_icons.py            # fetch missing icons
    python3 scripts/fetch_shipped_icons.py --force    # re-fetch all (refresh art)
    python3 scripts/fetch_shipped_icons.py --size 192 # custom px

Requires only the Python standard library.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, ".."))
BUNDLE_MAP = os.path.join(ROOT, "app", "src", "main", "java", "net", "extrawdw", "apps", "notisync",
                          "appicon", "BundleIdMap.kt")
OUT_DIR = os.path.join(ROOT, "app", "src", "main", "assets", "appicons")
LOOKUP = "https://itunes.apple.com/lookup"
ARTWORK_SUFFIX = re.compile(r"/\d+x\d+[a-z]*\.(?:jpg|jpeg|png|webp)$", re.IGNORECASE)
UA = "NotiSync-icon-fetch/1.0 (+https://github.com/; build tooling)"


def bundle_ids() -> list[str]:
    with open(BUNDLE_MAP, encoding="utf-8") as f:
        src = f.read()
    # matches: put("com.apple.MobileSMS", Entry(...))
    return sorted(set(re.findall(r'put\(\s*"([^"]+)"', src)))


def lookup_artwork(bundle_id: str, country: str = "us") -> str | None:
    qs = urllib.parse.urlencode({"bundleId": bundle_id, "country": country, "entity": "software"})
    req = urllib.request.Request(f"{LOOKUP}?{qs}", headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=15) as resp:
        data = json.load(resp)
    results = data.get("results") or []
    if not results:
        return None
    app = results[0]
    return app.get("artworkUrl512") or app.get("artworkUrl100") or app.get("artworkUrl60")


def lookup_artwork_multi(bundle_id: str, countries: list[str]) -> tuple[str | None, str | None]:
    """(artwork_url, country) from the first storefront that has the app, else (None, None). Mirrors the
    runtime AppStoreIconClient's US-then-CN fallback so the shipped pack can include CN-store-only apps."""
    for c in countries:
        try:
            art = lookup_artwork(bundle_id, c)
        except Exception:  # noqa: BLE001 - one storefront's failure shouldn't abort the rest
            art = None
        if art:
            return art, c
    return None, None


def to_webp(url: str, size: int) -> str:
    return ARTWORK_SUFFIX.sub(f"/{size}x{size}bb.webp", url)


def download(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=20) as resp:
        return resp.read()


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--force", action="store_true", help="re-fetch even if the file already exists")
    ap.add_argument("--size", type=int, default=256, help="square icon size in px (default 256)")
    ap.add_argument("--delay", type=float, default=0.4, help="seconds between lookups (be polite)")
    ap.add_argument("--countries", default="us,cn",
                    help="comma-separated App Store storefronts to try in order (default us,cn)")
    args = ap.parse_args()
    countries = [c.strip() for c in args.countries.split(",") if c.strip()]

    os.makedirs(OUT_DIR, exist_ok=True)
    ids = bundle_ids()
    print(f"{len(ids)} bundle ids from BundleIdMap.kt, storefronts {countries} -> {OUT_DIR}\n")

    fetched = skipped = 0
    no_entry: list[str] = []
    failed: list[str] = []

    for bid in ids:
        out = os.path.join(OUT_DIR, f"{bid.lower()}.webp")
        if os.path.exists(out) and not args.force:
            skipped += 1
            continue
        try:
            art, hit = lookup_artwork_multi(bid, countries)
            if not art:
                no_entry.append(bid)
                print(f"  --   {bid}: no store entry (tried {','.join(countries)})")
                continue
            data = download(to_webp(art, args.size))
            if not data:
                failed.append(bid)
                continue
            tmp = out + ".tmp"
            with open(tmp, "wb") as f:
                f.write(data)
            os.replace(tmp, out)
            fetched += 1
            print(f"  ok   {bid} [{hit}] -> {os.path.basename(out)} ({len(data)} bytes)")
        except Exception as e:  # noqa: BLE001 - best-effort tooling
            failed.append(bid)
            print(f"  ERR  {bid}: {e}", file=sys.stderr)
        time.sleep(args.delay)

    print(f"\nfetched {fetched}, skipped(existing) {skipped}, "
          f"no-store-entry {len(no_entry)}, failed {len(failed)}")
    if no_entry:
        print("\nNo App Store entry (ship a placeholder via generate_placeholder_icons.py):")
        for b in no_entry:
            print(f"  {b}")
    if failed:
        print("\nFailed (retry later):")
        for b in failed:
            print(f"  {b}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
