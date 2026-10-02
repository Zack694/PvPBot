# IL TRAINING GUIDE — teaching both models from PvP videos

Turn recorded sword-PvP gameplay (good pvpers, visible keystrokes HUD) into
expert demonstrations that steer BOTH brains (the v1 DQN and the v2 four-head
PolicyNet) during every later training step — the same margin-cloning (DQfD)
used by live human-train, sourced from video instead of your own play.

## What you need

```
pip install yt-dlp opencv-python numpy      # required
pip install pytesseract ultralytics         # optional: OCR + YOLO opponent detection
```

## The 3-step loop

```
1)  python il_fetch.py                       # downloads the curated videos +
                                             # runs the extractor -> il/*.jsonl
    (or: python il_extract.py myclip.mp4 --out il/)

2)  copy every il/*.jsonl into
        .minecraft/config/pvpbot/il/
    (in-game shortcut: the config screen has
     "IL: open the il/ folder (drop sessions here)")

3)  in game:  /pvpbot il load        -> scans the folder, feeds both expert rings
              /pvpbot il train 60    -> 60 margin-clone bursts on the demos
              /pvpbot il status      -> sessions / transitions / skipped
```

Autoload: every boot re-feeds the ring automatically when `IL autoload`
is ON in the config screen (it is by default).

## What the extractor reads from each frame

| Signal        | How                                                                       |
|---------------|---------------------------------------------------------------------------|
| keystrokes    | the W/A/S/D/Space/Shift/LMB HUD cells (brightness per cell; OCR fallback) |
| opponent      | YOLO Minecraft-player model if installed, else nametag+skin heuristic, else `--regions` manual boxes |
| timings       | attack-cooldown indicator fill under the crosshair -> click labels; crosshair motion -> aim labels |
| OCR           | optional pytesseract read of the HUD cells to double-check key presses    |

The extracted states are APPROXIMATE reconstructions of the mod's 104/64-dim
observation vectors (a video can't see armor points or exact velocities).
That is fine on purpose: the DQfD expert rings only need the LABELS (moves,
sprint, jump, aim direction, clicks) to be roughly right — the live RL loop
corrects the residual drift during real fights.

## Video quality rules (what makes GOOD training data)

- 1.9+ combat (attack cooldown bar visible under the crosshair)
- keystrokes HUD on screen, readable at 720p+
- clean 1v1 sword fights (no bow spam, no crystal/archery)
- 30 fps or better, 1080p preferred
- 10-15 solid duels already produce a strong steering signal; 60+ minutes
  of total footage saturates the rings

## Command reference

```
python il_fetch.py --list                      # print the curated video list
python il_fetch.py --ids <videoId> [<id2> ...] # fetch specific videos
python il_fetch.py --search "sword pvp keystrokes" --max 5
python il_extract.py clip.mp4 --out il/        # one local file
python il_extract.py clip.mp4 --regions        # mark HUD/opponent boxes by hand
```

## Managing the expert rings

```
/pvpbot il clear     # wipe the rings (does NOT touch trained weights)
/pvpbot il load      # re-feed every *.jsonl (loading twice = double weight)
```
