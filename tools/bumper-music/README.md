# Bumper Music Sourcing

This directory contains tools to automatically download royalty-free (CC0) music and sounds for use as channel bumpers.

## Quick Start

1. **Get a free Freesound API key** (one-time):
   - Go to https://freesound.org/apiv2/apply
   - Create an account and request an API key (usually approved instantly)

2. **Run the sourcer**:
   ```bash
   cd /net/projects/niten/tunarr-scheduler/tools/bumper-music
   export FREESOUND_API_KEY=your-key-here
   
   # Download music for a single mood
   python3 bumper-music-sourcer.py --mood mystery --limit 10
   
   # Download music for ALL moods (recommended starter pack)
   python3 bumper-music-sourcer.py --all --limit 8
   ```

3. **Trim to bumper length** (optional):
   ```bash
   python3 trim-for-bumpers.py --input-dir ./bumper-music/mystery --output-dir ./bumpers-ready --duration 5
   ```

## Channel → Mood Mappings

The sourcer comes with pre-defined moods. Map them to your channels:

| Channel | Suggested Mood(s) |
|---------|-------------------|
| Enigma TV | `mystery`, `suspense`, `noir` |
| Galaxy | `sci-fi`, `suspense` |
| Toon Town | `animation`, `upbeat` |
| Nippon TV | `japanese`, `chill` |
| Hua Network | `chinese`, `documentary` |
| Tasty TV | `food`, `upbeat`, `chill` |
| Golden Reels | `classic`, `nostalgic`, `noir` |
| Prime Series | `prestige`, `documentary` |
| Spectrum | `comedy`, `upbeat` |
| Britannia | `british`, `documentary` |
| Muse | `music`, `upbeat`, `chill` |
| InfoBytes | `documentary`, `sci-fi` |
| Chronicles | `history`, `documentary` |
| Spotlight | `prestige`, `action` |

## What You Get

Each mood directory contains:
- `*.mp3` / `*.wav` — Downloaded CC0 tracks (under 20 seconds by default)
- `manifest.json` — Metadata: Freesound ID, name, duration, tags

All tracks are **Creative Commons 0** (public domain). No attribution required. Safe for commercial streaming use.

## About Freesound

Freesound is a collaborative database of Creative Commons licensed sounds. The CC0 license means:
- ✅ Use commercially
- ✅ No attribution required
- ✅ Modify, remix, loop
- ✅ Use in streaming/broadcast

## Tips

- The `--max-duration` flag defaults to 20s. For 5-second bumpers you may want to trim or loop.
- Freesound has rate limits; the script sleeps politely between requests.
- If a mood returns few results, the script tries multiple search queries per mood.
- You can edit `MOOD_QUERIES` in the script to add your own search terms.

## Alternative Sources (No API Key)

If you prefer not to use Freesound:
- **Musopen** (musopen.org) — Public domain classical music, no API
- **Chosic** (chosic.com) — CC0 music with direct downloads
- **Creazilla** (creazilla.com) — Public domain audio
- **Pixabay Music** — Free, no attribution, requires account
