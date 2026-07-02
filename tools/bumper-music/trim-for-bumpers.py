#!/usr/bin/env python3
"""
trim-for-bumpers.py

Trim and normalize downloaded CC0 music into exact bumper-length clips
with smooth fade-in / fade-out. Uses ffmpeg (must be installed).

Usage:
    python3 trim-for-bumpers.py --input-dir ./bumper-music/mystery --output-dir ./bumpers-ready --duration 5
    python3 trim-for-bumpers.py --input-dir ./bumper-music --output-dir ./bumpers-ready --duration 5 --all-moods
"""

import argparse
import json
import os
import random
import subprocess
import sys
from pathlib import Path

SUPPORTED_EXTS = {".mp3", ".wav", ".flac", ".ogg", ".m4a", ".aac"}


def discover_tracks(input_dir):
    """Find all audio files under input_dir."""
    tracks = []
    for ext in SUPPORTED_EXTS:
        tracks.extend(Path(input_dir).rglob(f"*{ext}"))
    return sorted(tracks)


def get_duration(path):
    """Return audio duration in seconds using ffprobe."""
    try:
        out = subprocess.check_output(
            ["ffprobe", "-v", "error", "-show_entries", "format=duration",
             "-of", "default=noprint_wrappers=1:nokey=1", str(path)],
            stderr=subprocess.DEVNULL,
            text=True,
        )
        return float(out.strip())
    except Exception:
        return None


def trim_track(input_path, output_path, target_duration, fade_ratio=0.15):
    """
    Trim a track to target_duration seconds.

    Strategy:
    - If track is shorter than target: loop it (crossfade repeat)
    - If track is longer than target: pick a random start point and fade
    - Apply fade-in and fade-out so it sounds polished
    """
    duration = get_duration(input_path)
    if duration is None:
        print(f"  ⚠ Cannot probe {input_path.name}, skipping")
        return False

    fade = min(target_duration * fade_ratio, 1.5)  # max 1.5s fade

    # Build ffmpeg filter_complex
    if duration < target_duration:
        # Loop the audio to fill duration
        loops = int(target_duration / duration) + 1
        filter_parts = [
            f"aloop=loop={loops}:size={int(duration * 48000)}",
            f"atrim=start=0:end={target_duration}",
            f"afade=t=in:st=0:d={fade}",
            f"afade=t=out:st={target_duration - fade}:d={fade}",
        ]
    else:
        # Pick random start (stay within bounds)
        max_start = max(0, duration - target_duration - 1)
        start = random.uniform(0, max_start) if max_start > 0 else 0
        filter_parts = [
            f"atrim=start={start}:end={start + target_duration}",
            f"afade=t=in:st={start}:d={fade}",
            f"afade=t=out:st={start + target_duration - fade}:d={fade}",
        ]

    filter_graph = ",".join(filter_parts)

    cmd = [
        "ffmpeg", "-y", "-i", str(input_path),
        "-vn",  # no video
        "-af", filter_graph,
        "-ar", "48000",  # standard sample rate
        "-ac", "2",      # stereo
        "-b:a", "192k",  # decent quality
        str(output_path),
    ]

    try:
        subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return True
    except subprocess.CalledProcessError as e:
        print(f"  ⚠ ffmpeg failed for {input_path.name}: {e}")
        return False


def normalize_loudness(paths, output_dir):
    """Optional: batch-normalize loudness with ffmpeg loudnorm filter."""
    # This is a future enhancement; for now per-track normalization is fine.
    pass


def main():
    parser = argparse.ArgumentParser(description="Trim CC0 music into exact bumper clips")
    parser.add_argument("--input-dir", required=True, help="Directory containing downloaded music")
    parser.add_argument("--output-dir", required=True, help="Where to write trimmed bumpers")
    parser.add_argument("--duration", type=float, default=5.0, help="Target duration in seconds")
    parser.add_argument("--all-moods", action="store_true", help="Process every subdirectory as a mood")
    parser.add_argument("--max-per-mood", type=int, default=0, help="Max tracks per mood (0 = unlimited)")
    parser.add_argument("--fade-ratio", type=float, default=0.15, help="Fade in/out as ratio of duration")
    args = parser.parse_args()

    # Verify ffmpeg exists
    if subprocess.run(["which", "ffmpeg"], capture_output=True).returncode != 0:
        print("Error: ffmpeg not found. Install it first:")
        print("  nix-shell -p ffmpeg")
        sys.exit(1)

    input_root = Path(args.input_dir)
    output_root = Path(args.output_dir)
    output_root.mkdir(parents=True, exist_ok=True)

    moods = []
    if args.all_moods:
        moods = [d for d in input_root.iterdir() if d.is_dir()]
    else:
        moods = [input_root]

    total = 0
    for mood_dir in moods:
        mood_name = mood_dir.name
        mood_out = output_root / mood_name if args.all_moods else output_root
        mood_out.mkdir(parents=True, exist_ok=True)

        tracks = discover_tracks(mood_dir)
        if not tracks:
            print(f"No tracks found in {mood_dir}")
            continue

        print(f"\n→ Processing mood: {mood_name} ({len(tracks)} tracks)")

        limit = args.max_per_mood if args.max_per_mood > 0 else len(tracks)
        for i, track in enumerate(tracks[:limit]):
            out_name = f"{mood_name}_{i+1:03d}_{target_duration}s.mp3"
            out_path = mood_out / out_name
            if out_path.exists():
                print(f"  Skip existing {out_name}")
                total += 1
                continue

            ok = trim_track(track, out_path, args.duration, args.fade_ratio)
            if ok:
                print(f"  ✓ {out_name}")
                total += 1

    print(f"\n✅ Done. {total} bumper clips ready in {output_root}/")


if __name__ == "__main__":
    main()
