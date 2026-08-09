#!/usr/bin/env bash
# Copyright 2026 Rituraj Basak
# SPDX-License-Identifier: Apache-2.0

set -euo pipefail

repository_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
configuration_file="$repository_root/config/product.json"
source_mark="$repository_root/art/nixship.svg"

for command in jq magick; do
  if ! command -v "$command" >/dev/null; then
    echo "Missing required command: $command. Run through nix develop." >&2
    exit 2
  fi
done
if [[ ! -f "$source_mark" ]]; then
  echo "Missing monochrome source mark: $source_mark" >&2
  exit 2
fi

foreground="$(jq -r '.branding.foregroundColor' "$configuration_file")"
background="$(jq -r '.branding.backgroundColor' "$configuration_file")"
if [[ ! "$foreground" =~ ^#[0-9A-F]{6}$ || ! "$background" =~ ^#[0-9A-F]{6}$ ]]; then
  echo "Brand colors must be uppercase six-digit hexadecimal values." >&2
  exit 2
fi

working_directory="$(mktemp -d)"
trap 'rm -rf -- "$working_directory"' EXIT
prepared_mark="$working_directory/nixship-mark.png"
magick "$source_mark" \
  -bordercolor white -border 1 -alpha set -channel RGBA -fuzz 4% \
  -fill none -draw "alpha 0,0 floodfill" -shave 1x1 \
  -trim +repage \
  -define png:exclude-chunks=date,time -strip "$prepared_mark"

render_transparent() {
  local size="$1"
  local output="$2"
  local mark_size=$((size * 15 / 16))
  magick -size "${size}x${size}" xc:none \
    \( "$prepared_mark" -resize "${mark_size}x${mark_size}" \) \
    -gravity center -compose over -composite \
    -dither FloydSteinberg -colors 256 \
    -define png:exclude-chunks=date,time -strip "$output"
}

for density_and_size in mdpi:48 hdpi:72 xhdpi:96 xxhdpi:144 xxxhdpi:192; do
  density="${density_and_size%%:*}"
  size="${density_and_size##*:}"
  destination="$repository_root/app/src/main/res/mipmap-$density"
  render_transparent "$size" "$destination/ic_launcher.png"
  render_transparent "$size" "$destination/ic_launcher_round.png"
done

mkdir -p "$repository_root/app/src/main/res/drawable-nodpi"
magick "$prepared_mark" \
  -resize 264x264 -gravity center -background none -extent 432x432 \
  -dither FloydSteinberg -colors 256 \
  -define png:exclude-chunks=date,time -strip \
  "$repository_root/app/src/main/res/drawable-nodpi/nixship_mark.png"
magick "$prepared_mark" \
  -background white -alpha background -alpha off -colorspace Gray -negate -threshold 38% \
  -alpha copy -channel RGB -fill "$foreground" -colorize 100 +channel \
  -resize 264x264 -gravity center -background none -extent 432x432 \
  -define png:exclude-chunks=date,time -strip \
  "$repository_root/app/src/main/res/drawable-nodpi/nixship_mark_monochrome.png"
render_transparent 512 "$repository_root/fastlane/metadata/android/en-US/images/icon.png"

magick -size 1024x500 "xc:$background" \
  \( "$prepared_mark" -resize 420x420 \) \
  -gravity center -compose over -composite \
  -dither FloydSteinberg -colors 256 -strip \
  "$repository_root/fastlane/metadata/android/en-US/images/featureGraphic.png"

magick -size 320x180 "xc:$foreground" \
  \( "$prepared_mark" -resize 168x168 \) \
  -gravity center -compose over -composite \
  -dither FloydSteinberg -colors 256 -strip \
  "$repository_root/app/src/main/res/drawable/banner.png"

echo "Generated Nix Ship brand assets."
