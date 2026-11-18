#!/usr/bin/env python3
"""Start the Linux TIDAL player paired to a central relay."""
import argparse
import json
import os
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path,
                        default=Path.home() / ".config/tidal-relay/relay.json")
    parser.add_argument("--device", default="desktop")
    parser.add_argument("tidal_url", nargs="?")
    args = parser.parse_args()
    with args.config.open() as config_file:
        config = json.load(config_file)
    device = config["devices"][args.device]
    if device["platform"] != "desktop":
        parser.error("This device is not a desktop pairing")
    os.environ.update(TIDAL_RELAY_URL=config["origin"], TIDAL_RELAY_DEVICE=args.device,
                      TIDAL_RELAY_KEY=device["token"])
    binary = Path(__file__).resolve().parents[1] / "target/release/tidal-gui"
    if not binary.is_file():
        parser.error("Build the desktop client with cargo build --locked --release first")
    os.execv(binary, [str(binary), *([args.tidal_url] if args.tidal_url else [])])


if __name__ == "__main__":
    main()
