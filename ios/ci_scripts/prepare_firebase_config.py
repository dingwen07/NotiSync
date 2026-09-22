#!/usr/bin/env python3
"""Restore Xcode Cloud's Firebase configuration into the app's resource folder."""

import base64
import binascii
import os
from pathlib import Path
import plistlib


def main():
    variable = "GOOGLE_SERVICE_INFO_PLIST_BASE64"
    destination = Path("ios/NotiSync/GoogleService-Info.plist")
    encoded = os.environ.get(variable)

    if encoded is not None:
        try:
            contents = base64.b64decode("".join(encoded.split()), validate=True)
        except (binascii.Error, ValueError):
            raise SystemExit(f"error: {variable} is not valid Base64") from None
    elif destination.is_file():
        # Keep an existing local configuration unchanged when testing the hook.
        contents = destination.read_bytes()
    else:
        raise SystemExit(
            f"error: Set {variable} in the Xcode Cloud workflow's Environment "
            "section to the Base64-encoded GoogleService-Info.plist, "
            "and mark its value Secret / Keep value redacted."
        )

    try:
        configuration = plistlib.loads(contents)
    except Exception:
        # Parser errors can contain configuration values; never print them.
        raise SystemExit("error: Firebase configuration is not a valid plist") from None

    required_keys = ("GOOGLE_APP_ID", "BUNDLE_ID", "PROJECT_ID", "GCM_SENDER_ID", "API_KEY")
    if not isinstance(configuration, dict) or any(
        not isinstance(configuration.get(key), str) or not configuration[key].strip()
        for key in required_keys
    ):
        raise SystemExit(
            "error: Firebase configuration must contain nonempty strings for "
            + ", ".join(required_keys)
        )

    if encoded is not None:
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(contents)
    print(f"Prepared {destination} for Xcode.")


if __name__ == "__main__":
    main()
