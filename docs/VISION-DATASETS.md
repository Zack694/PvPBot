# PvPBot Vision — Player Image Datasets & Pipeline (v2.1.0)

Goal: teach the Sight system to **recognize players from pixels** on top of the
existing 104-dim geometric observation. Two data sources, one pipeline.

## 1. PRIMARY — the in-game recorder (best data, zero effort)

The mod ships with an auto-labeling dataset builder:

```
/pvpbot vision on      # start recording (default OFF)
/pvpbot vision status  # positives / negatives / folder
/pvpbot vision off
```

- Saves to `<game dir>/pvpbot-vision/` — PNG crops of every visible player
  (positives) **plus matched empty-scenery crops (negatives)**, with a
  `manifest.jsonl` holding exact labels: name, distance, sneaking, onGround,
  hurt state, line-of-sight, screen position, frame size.
- Labels come from the client's own entity data — 100% accurate, no manual
  annotation. Crops match your texture pack, skins and lighting: the exact
  distribution the recognizer will run on.
- Auto-pauses at the `visionMaxFiles` cap (6000 by default).

Suggested collection routine: 10 minutes of normal duels with the recorder on
(1v1, practice bots, different maps/lighting). ~3-5k balanced crops.

## 2. PUBLIC datasets & pretrained models (web)

Curated from GitHub (checked 2026-10):

| Resource | What it is | Link |
|---|---|---|
| styalai/player-detection-on-Minecraft-with-YOLOv8 | YOLOv8 player detector + training notebooks | https://github.com/styalai/player-detection-on-Minecraft-with-YOLOv8 |
| benioriginal/Player-Detection-minecraft-using-yolov5 | YOLOv5 player detection (ESP-style live demo) | https://github.com/benioriginal/Player-Detection-minecraft-using-yolov5 |
| kanamoji/player-detection-Minecraft-YOLOv11 | YOLOv11 player detector weights | https://github.com/kanamoji/player-detection-Minecraft-YOLOv11 |
| danasydyk/CV-minecraft-mobs-detection | YOLOv8 hostile-mob detector (extra classes) | https://github.com/danasydyk/CV-minecraft-mobs-detection |
| MineRL (minerl.org) | large-scale Minecraft video + action dataset | https://minerl.org |
| Roboflow Universe — search "minecraft player" | many community YOLO datasets, exportable | https://universe.roboflow.com |
| Hugging Face — search "minecraft detection" | models/datasets hub | https://huggingface.co/models?search=minecraft |

**Already fetched for you** (in `download/vision-datasets/`):
- `minecraft-player-yolov8_styalai.pt` — pretrained YOLOv8 Minecraft player detector (6.2 MB)
- `minecraft-player-yolov11_kanamoji.pt` — pretrained YOLOv11 player detector (10.8 MB)

## 3. Planned pipeline (Phase 2 — after the dataset exists)

1. **Bootstrap auto-labeling**: run a pretrained YOLO (the .pt files above)
   over raw recordings to propose boxes on frames where the client entity
   list was incomplete (partial off-screen players) — then verify against the
   manifest labels.
2. **Train the in-mod recognizer**: a small CNN (crop -> "player confidence +
   coarse screen position") distilled from YOLO, exported to the same binary
   weight format the mod already uses (`NeuralNet`), so it runs inside the
   Java client with zero new dependencies.
3. **Fuse into the observation**: the recognizer's confidence becomes a new
   feature block appended to the v3 observation (a future v4 arch bump), giving
   the brain a pixel-level "I can SEE a player" signal that survives entity
   list gaps (lag, render distance) and verifies the geometric sight data.

*Note: the client's entity data already gives exact positions; pixel vision
adds robustness (occlusion/partial info) and an independent verification
channel. The recorder exists now so data accumulates while Phase 2 is built.*
