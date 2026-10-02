#!/usr/bin/env python3
"""
pvpbot IL video fetcher — pull good-pvper sword PvP videos (with a visible
keystrokes HUD) and run the extractor automatically.

What it does:
  1. downloads the videos from VIDEOS.txt (or the built-in list below) with
     yt-dlp at 1080p (the keystrokes HUD needs >= 720p to read reliably),
  2. runs il_extract.py on every download,
  3. drops the *.jsonl sessions into il/ — copy them to
     .minecraft/config/pvpbot/il/ (or click "IL: open the il/ folder" in the
     config screen) and press /pvpbot il load.

Install once:
    pip install yt-dlp opencv-python numpy
    optional: pip install pytesseract ultralytics

Usage:
    python il_fetch.py                    # fetch + extract everything
    python il_fetch.py --list             # just print the curated list
    python il_fetch.py --ids 4D4rVfu0Srk y8H21i0zlHM
    python il_fetch.py --search "sword pvp montage keystrokes" --max 5
"""

import argparse
import os
import subprocess
import sys

# ---------------------------------------------------------------- curated list
# Modern 1.9+ sword PvP, from the mctierlist scene (Swight / ItzRealMe /
# Flowtives tier circle) and keystrokes-visible montages. IDs are YouTube
# video ids; the fetcher skips anything already downloaded. Replace freely —
# any video with a clean keystrokes HUD + 1.9+ combat works.
CURATED = [
    # ---- tier-test duels (mctierlist circle, keystrokes visible) ----
    ("y8H21i0zlHM", "Swight vs ItzRealMe | Tier 2 Sword Test"),
    ("4D4rVfu0Srk", "Swight, Flowtives, & ItzRealMe Play Hypixel"),
    ("Vyg9hdYiDjA", "I Fought Minecraft's Best Player [2024]"),
    ("gtOaNr3nw1g", "[Tier 1] Minecraft Sword PvP Montage"),
    ("PzfGYlJ6mf8", "Sword PvP Montage *keystrokes*"),
    # ---- montage / practice backups (add your own!) ----
    ("vVT2kDvyJAc", "The most satisfying minecraft PvP montage"),
]

VIDEOS_TXT = "VIDEOS.txt"


def sh(cmd):
    print(">", " ".join(cmd))
    r = subprocess.run(cmd)
    if r.returncode != 0:
        print("!! command failed:", " ".join(cmd))
    return r.returncode == 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ids", nargs="*", help="explicit YouTube video ids")
    ap.add_argument("--search", help="yt-dlp ytsearch:<query>")
    ap.add_argument("--max", type=int, default=5, help="max search results")
    ap.add_argument("--list", action="store_true", help="just print the list")
    ap.add_argument("--out", default="il", help="output jsonl dir")
    ap.add_argument("--keep-videos", action="store_true")
    args = ap.parse_args()

    if args.list:
        for vid, title in CURATED:
            print(f"{vid}  https://youtu.be/{vid}  {title}")
        return

    entries = []
    if args.ids:
        entries += [(v, "requested") for v in args.ids]
    if args.search:
        entries.append((f"ytsearch{args.max}:{args.search}", "search"))
    if not entries:
        if os.path.isfile(VIDEOS_TXT):
            for line in open(VIDEOS_TXT, encoding="utf-8"):
                line = line.strip()
                if line and not line.startswith("#"):
                    vid = line.split()[0]
                    entries.append((vid, " ".join(line.split()[1:]) or "list"))
        else:
            entries = list(CURATED)

    os.makedirs("downloads", exist_ok=True)
    os.makedirs(args.out, exist_ok=True)
    ok = 0
    for vid, title in entries:
        url = vid if vid.startswith("ytsearch") or vid.startswith("http") else f"https://youtu.be/{vid}"
        print(f"\n=== {title}  ({url})")
        out_t = os.path.join("downloads", "%(id)s.%(ext)s")
        if not any(v in f for f in os.listdir("downloads") for v in [vid.split(":")[-1]]):
            if not sh(["yt-dlp", "-f", "bestvideo[height<=1080]+bestaudio/best[height<=1080]",
                       "--merge-output-format", "mp4", "-o", out_t, url]):
                continue
        # extract
        vids = [os.path.join("downloads", f) for f in os.listdir("downloads")
                if vid.split("/")[-1].replace("ytsearch", "") in f or vid.startswith("ytsearch")]
        for v in vids:
            if sh([sys.executable, os.path.join(os.path.dirname(os.path.abspath(__file__)), "il_extract.py"),
                   v, "--out", args.out]):
                ok += 1
        if not args.keep_videos:
            for v in vids:
                try:
                    os.remove(v)
                except OSError:
                    pass
    print(f"\nDONE — {ok} sessions written to {args.out}/")
    print("Copy them into .minecraft/config/pvpbot/il/ and run /pvpbot il load")


if __name__ == "__main__":
    main()
