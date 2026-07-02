#!/usr/bin/env python3
"""
bumper-music-sourcer.py

Search and download CC0 (public domain) music and sounds from Freesound.org
for use as bumper audio tracks. Organizes downloads by mood/category.

Usage:
    export FREESOUND_API_KEY=your-key-here
    python3 bumper-music-sourcer.py --mood mystery --limit 10 --output-dir ./music
    python3 bumper-music-sourcer.py --all --output-dir ./music

Requirements:
    pip install requests
"""

import argparse
import os
import sys
import json
import time
import requests
from pathlib import Path
from urllib.parse import urlencode

FREESOUND_API = "https://freesound.org/apiv2"

# Pre-canned mood → search queries that tend to yield good CC0 bumper-length sounds
MOOD_QUERIES = {
    # Channel-specific mappings
    "mystery":       ["mystery ambient", "noir jazz", "detective suspense", "dark ambient"],
    "sci-fi":        ["sci fi ambient", "space ambient", "futuristic pad", "synth drone"],
    "comedy":        ["comedy music", "funny upbeat", "quirky music", "sitcom theme"],
    "japanese":      ["japanese traditional", "shamisen", "koto", "taiko ambient"],
    "chinese":       ["chinese traditional", "erhu", "guqin", "chinese ambient"],
    "animation":     ["cartoon music", "whimsical music", "playful music", "animated short"],
    "documentary":   ["documentary ambient", "nature ambient", "discovery music", "calm piano"],
    "history":       ["historical music", "renaissance lute", "medieval ambient", "classical guitar"],
    "music":         ["music documentary", "concert ambient", "festival music", "band rehearsal"],
    "food":          ["cooking music", "kitchen ambient", "light jazz", "upbeat acoustic"],
    "classic":       ["classic cinema", "old hollywood", "vintage jazz", "1920s music"],
    "prestige":      ["prestige drama", "cinematic strings", "emotional piano", "orchestral short"],
    "british":       ["british folk", "uk ambient", "london jazz", "british comedy"],
    "action":        ["action trailer", "epic short", "cinematic percussion", "intense ambient"],
    "horror":        ["horror ambient", "supernatural drone", "creepy music", "halloween ambient"],
    # Generic moods for any channel
    "upbeat":        ["upbeat instrumental", "happy acoustic", "positive music", "bright pop"],
    "chill":         ["chill lo-fi", "relaxing guitar", "soft ambient", "calm music"],
    "energetic":     ["energetic music", "fast upbeat", "driving music", "motivational"],
    "nostalgic":     ["nostalgic piano", "warm ambient", "retro music", "memory music"],
    "suspense":      ["suspense music", "tension ambient", "thriller music", "uneasy drone"],
    "romantic":      ["romantic piano", "love theme", "tender music", "warm strings"],
}


def search_freesound(api_key, query, duration_max=20, limit=15):
    """Search Freesound for CC0 sounds matching query, under duration_max seconds."""
    params = {
        "query": query,
        "filter": f"duration:[0 TO {duration_max}] license:\"Creative Commons 0\"",
        "sort": "downloads_desc",
        "fields": "id,name,tags,duration,download,previews,license",
        "page_size": limit,
    }
    url = f"{FREESOUND_API}/search/text/?{urlencode(params)}"
    resp = requests.get(url, headers={"Authorization": f"Token {api_key}"})
    resp.raise_for_status()
    return resp.json().get("results", [])


def download_sound(api_key, sound_id, download_url, dest_path):
    """Download a Freesound preview (or original) to dest_path."""
    # Use preview if available; otherwise fall back to original download
    headers = {"Authorization": f"Token {api_key}"}
    resp = requests.get(download_url, headers=headers, stream=True)
    resp.raise_for_status()
    with open(dest_path, "wb") as f:
        for chunk in resp.iter_content(chunk_size=8192):
            f.write(chunk)
    return dest_path


def ensure_dir(path):
    Path(path).mkdir(parents=True, exist_ok=True)


def fetch_mood(api_key, mood, output_dir, limit_per_query=8, max_duration=20):
    """Fetch sounds for a single mood and save into output_dir/<mood>/."""
    queries = MOOD_QUERIES.get(mood, [mood])
    mood_dir = Path(output_dir) / mood
    ensure_dir(mood_dir)

    manifest = []
    seen_ids = set()

    for q in queries:
        print(f"  Searching: '{q}' …")
        try:
            results = search_freesound(api_key, q, max_duration, limit=limit_per_query)
        except requests.HTTPError as e:
            print(f"    HTTP error: {e}")
            continue

        for r in results:
            sid = r["id"]
            if sid in seen_ids:
                continue
            seen_ids.add(sid)

            # Prefer high-quality preview, else original download
            download_url = (
                r.get("previews", {}).get("preview-hq-mp3")
                or r.get("previews", {}).get("preview-lq-mp3")
                or r.get("download")
            )
            if not download_url:
                continue

            # Build a clean filename
            safe_name = "".join(c if c.isalnum() or c in " -_" else "_" for c in r["name"]).strip()
            ext = ".mp3" if "mp3" in download_url else ".wav"
            filename = f"{sid}_{safe_name}{ext}"
            dest = mood_dir / filename

            if dest.exists():
                print(f"    Skip existing {filename}")
                manifest.append({
                    "id": sid, "name": r["name"], "file": str(dest),
                    "duration": r.get("duration"), "tags": r.get("tags", [])
                })
                continue

            try:
                print(f"    Downloading {filename} ({r.get('duration', '?')}s)")
                download_sound(api_key, sid, download_url, dest)
                manifest.append({
                    "id": sid, "name": r["name"], "file": str(dest),
                    "duration": r.get("duration"), "tags": r.get("tags", [])
                })
                time.sleep(0.5)  # be polite to the API
            except Exception as e:
                print(f"    Failed to download {sid}: {e}")

    # Write manifest
    manifest_path = mood_dir / "manifest.json"
    with open(manifest_path, "w") as f:
        json.dump(manifest, f, indent=2)
    print(f"  Saved {len(manifest)} tracks to {mood_dir}")
    return manifest


def main():
    parser = argparse.ArgumentParser(description="Source CC0 bumper music from Freesound")
    parser.add_argument("--mood", help="Mood/category to fetch (e.g. mystery, comedy, chill)")
    parser.add_argument("--all", action="store_true", help="Fetch every defined mood")
    parser.add_argument("--output-dir", default="./bumper-music", help="Root output directory")
    parser.add_argument("--limit", type=int, default=8, help="Max results per search query")
    parser.add_argument("--max-duration", type=int, default=20, help="Max track duration in seconds")
    parser.add_argument("--api-key", default=os.environ.get("FREESOUND_API_KEY"), help="Freesound API key")
    args = parser.parse_args()

    if not args.api_key:
        print("Error: Provide --api-key or set FREESOUND_API_KEY environment variable.")
        print("Get a free key at: https://freesound.org/apiv2/apply")
        sys.exit(1)

    if args.all:
        moods = list(MOOD_QUERIES.keys())
    elif args.mood:
        if args.mood not in MOOD_QUERIES:
            print(f"Unknown mood '{args.mood}'. Available: {', '.join(MOOD_QUERIES.keys())}")
            sys.exit(1)
        moods = [args.mood]
    else:
        print("Error: Specify --mood <name> or --all")
        sys.exit(1)

    print(f"Output directory: {args.output_dir}")
    for mood in moods:
        print(f"\n→ Fetching mood: {mood}")
        fetch_mood(args.api_key, mood, args.output_dir, args.limit, args.max_duration)

    print(f"\n✅ Done. Assets saved under {args.output_dir}/")
    print("You can now trim/loop these with ffmpeg for exact bumper durations.")


if __name__ == "__main__":
    main()
