#!/bin/sh
# Makes the .icns icon of the macOS application from a square PNG, with the tools that macOS has (sips and iconutil).
# Usage: make-icns.sh <logo.png> <KaTTY.icns>
set -e
logo="$1"
icns="$2"
iconset="$(mktemp -d)/KaTTY.iconset"
mkdir -p "$iconset" "$(dirname "$icns")"
# An iconset has each size twice: for a screen of 1 pixel to the point, and for one of 2.
for size in 16 32 128 256 512; do
    sips -z "$size" "$size" "$logo" --out "$iconset/icon_${size}x${size}.png" > /dev/null
    sips -z "$((size * 2))" "$((size * 2))" "$logo" --out "$iconset/icon_${size}x${size}@2x.png" > /dev/null
done
iconutil -c icns "$iconset" -o "$icns"
rm -rf "$(dirname "$iconset")"
