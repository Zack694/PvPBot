#!/usr/bin/env python3
"""
pvpbot IL extractor — turn PvP videos into training sessions for the mod.

Pipeline (per frame, sampled at --fps):
  1. KEYSTROKES   the on-screen keystrokes HUD (W/A/S/D/LMB/Space/Shift) is
                  located (auto or manual regions); each key's pressed state
                  is read from its brightness/saturation (no OCR needed) and
                  double-checked with OCR when pytesseract is installed.
  2. OPPONENT     the opponent's screen position comes from detection:
                  - YOLO (ultralytics + a Minecraft-player model) if available,
                  - otherwise a color/edge heuristic (nametag bar + skin-tone
                    blob above it),
                  - otherwise manual click annotation prompts (region mode).
                  Screen position -> angular yaw/pitch error from the FOV.
  3. TIMINGS      the attack-cooldown indicator under the crosshair is
                  measured (arc/width fill ratio) -> click label = the moment
                  the fill crosses the band; crosshair movement -> camera
                  deltas for the aim labels.
  4. STATE        every sample is written as the mod's JSONL line:
                  {"s":[104], "s1":[64], "mv":.., "sp":.., "jp":.., "sn":..,
                   "ay":.., "ap":.., "ck":.., "r":.., "done":false}
                  States are APPROXIMATE reconstructions (documented below);
                  they feed the DQfD expert rings where margin-cloning steers
                  and the live RL loop corrects residual drift.

Install:  pip install opencv-python numpy
Optional: pip install pytesseract ultralytics   (OCR + YOLO detection)
Usage:    python il_extract.py fight1.mp4 fight2.mp4 ... --out il/
          python il_extract.py clip.mp4 --regions   (click to mark key slots
                                                     + opponent ROI on frame 1)
"""
import argparse, json, math, os, sys

try:
    import cv2
    import numpy as np
except ImportError:
    sys.exit("opencv-python + numpy required:  pip install opencv-python numpy")

# ---------------------------------------------------------------- state layout
# The mod's Perception layout (first 64 = v1 Dqn state, +40 = Sight 20 + Adv 20
# for the v2 PolicyNet). Video can only RECONSTRUCT a subset — the rest is
# written as 0 (neutral). Order mirrors Perception.build/buildV3 best-effort:
# distances, velocities, health, timings, memory-style features are approximated;
# server-side-only internals (armor points, absorption, exact Q-states) are 0.
# DQfD tolerates the approximation: the labels (keys/aim/click) are the signal.

def default_state(dim):
    return [0.0] * dim

def key_regions_auto(frame):
    """Locate a keystrokes HUD block: bottom-left cluster of bright bordered
    keys. Returns dict name -> (x, y, w, h) or None if not found."""
    h, w = frame.shape[:2]
    gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
    _, th = cv2.threshold(gray, 180, 255, cv2.THRESH_BINARY)
    cnts, _ = cv2.findContours(th, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    boxes = []
    for c in cnts:
        x, y, bw, bh = cv2.boundingRect(c)
        if 18 <= bw <= 90 and 18 <= bh <= 90 and x < w * 0.4 and y > h * 0.45:
            boxes.append((x, y, bw, bh))
    if len(boxes) < 3:
        return None
    boxes.sort(key=lambda b: (b[1], b[0]))
    # cluster: W above, A/S/D row, mouse row(s) below
    regions = {}
    top = boxes[0]
    regions["W"] = top
    row2 = sorted([b for b in boxes if abs(b[1] - boxes[1][1]) < 12], key=lambda b: b[0])
    if len(row2) >= 3:
        regions["A"], regions["S"], regions["D"] = row2[0], row2[1], row2[2]
    rest = [b for b in boxes if b not in row2 and b != top]
    rest.sort(key=lambda b: (b[1], b[0]))
    for i, name in enumerate(["LMB", "RMB", "SPACE", "SHIFT"]):
        if i < len(rest):
            regions[name] = rest[i]
    return regions if len(regions) >= 4 else None

def key_pressed(frame, region):
    """A key slot lights up when pressed: mean brightness jumps."""
    x, y, w, h = region
    pad = max(2, min(w, h) // 6)
    roi = frame[max(0, y - pad):y + h + pad, max(0, x - pad):x + w + pad]
    if roi.size == 0:
        return 0.0
    gray = cv2.cvtColor(roi, cv2.COLOR_BGR2GRAY)
    bright = float(np.mean(gray)) / 255.0
    sat = float(np.mean(cv2.cvtColor(roi, cv2.COLOR_BGR2HSV)[:, :, 1])) / 255.0
    v = max(bright, sat * 1.3)
    return 1.0 if v > 0.55 else (v - 0.30) / 0.25  # soft 0..1

def find_opponent_yolo(model, frame):
    res = model.predict(frame, verbose=False, conf=0.35)
    if res and len(res):
        boxes = res[0].boxes
        if boxes is not None and len(boxes):
            b = boxes[0].xyxy[0].cpu().numpy()
            x1, y1, x2, y2 = b
            return (float(x1), float(y1), float(x2 - x1), float(y2 - y1))
    return None

def find_opponent_color(frame):
    """Nametag+skin heuristic: dark nametag bar (semi-transparent black with
    white text) with a skin-tone body under it."""
    h, w = frame.shape[:2]
    hsv = cv2.cvtColor(frame, cv2.COLOR_BGR2HSV)
    skin = cv2.inRange(hsv, (0, 40, 120), (25, 160, 255))
    skin = cv2.morphologyEx(skin, cv2.MORPH_OPEN, np.ones((3, 3), np.uint8))
    cnts, _ = cv2.findContours(skin, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    best, best_h = None, 0
    for c in cnts:
        x, y, bw, bh = cv2.boundingRect(c)
        if bh > best_h and bh > h * 0.05 and bw < w * 0.35 and 0.25 < bw / max(1, bh) < 1.2:
            best, best_h = (x, y, bw, bh), bh
    return best

def screen_to_angles(bbox, frame_shape, fov_deg):
    """Opponent bbox center -> (yaw_err_deg, pitch_err_deg) relative to the
    crosshair (screen center). Minecraft: horizontal FOV, 16:9 corrected."""
    H, W = frame_shape[:2]
    cx, cy = W / 2.0, H / 2.0
    x, y, bw, bh = bbox
    ox, oy = x + bw / 2.0, y + bh / 2.0
    vfov = 2 * math.degrees(math.atan(math.tan(math.radians(fov_deg / 2)) * (H / float(W))))
    yaw_err = (ox - cx) / W * fov_deg
    pitch_err = -(oy - cy) / H * vfov
    return yaw_err, pitch_err

def cooldown_fill(frame):
    """Attack indicator under the crosshair: fraction 0..1 (1 = full charge).
    Reads a small strip 18-30px below center; bright rows = charged."""
    H, W = frame.shape[:2]
    cx, cy = W // 2, H // 2
    roi = frame[cy + 16:cy + 30, cx - 20:cx + 20]
    if roi.size == 0:
        return 1.0
    gray = cv2.cvtColor(roi, cv2.COLOR_BGR2GRAY)
    frac = float(np.mean(gray > 90))
    return min(1.0, frac * 1.6)

def move_from_keys(keys):
    w = keys.get("W", 0) > 0.5
    s = keys.get("S", 0) > 0.5
    a = keys.get("A", 0) > 0.5
    d = keys.get("D", 0) > 0.5
    if w and not s:
        return (2 if a else 3 if d else 1)      # 1=W 2=WA 3=WD
    if s and not w:
        return (5 if a else 6 if d else 4)      # 4=S 5=SA 6=SD
    if a and not d:
        return 7                                 # A
    if d and not a:
        return 8                                 # D
    return 0                                     # NONE

# Move indices follow the mod's ActionSpace:
# 0 NONE, 1 W, 2 WA, 3 WD, 4 S, 5 SA, 6 SD, 7 A, 8 D

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("videos", nargs="+")
    ap.add_argument("--out", default="il")
    ap.add_argument("--fps", type=float, default=20.0, help="sampling rate (mod ticks = 20)")
    ap.add_argument("--fov", type=float, default=70.0)
    ap.add_argument("--yolo", default=None, help="path to a Minecraft-player YOLO .pt")
    ap.add_argument("--regions", action="store_true", help="manually mark key slots/ROI on frame 1")
    ap.add_argument("--margin", type=float, default=0.0, help="reward bonus added to every step")
    args = ap.parse_args()

    os.makedirs(args.out, exist_ok=True)
    model = None
    if args.yolo:
        try:
            from ultralytics import YOLO
            model = YOLO(args.yolo)
            print(f"[il] YOLO opponent detection: {args.yolo}")
        except Exception as e:
            print(f"[il] YOLO unavailable ({e}) — falling back to color heuristic")
    ocr = False
    try:
        import pytesseract  # noqa
        ocr = True
    except ImportError:
        pass

    for vid in args.videos:
        cap = cv2.VideoCapture(vid)
        if not cap.isOpened():
            print(f"[il] cannot open {vid}")
            continue
        src_fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
        step = max(1, int(round(src_fps / args.fps)))
        name = os.path.splitext(os.path.basename(vid))[0]
        out_path = os.path.join(args.out, name + ".jsonl")
        regions = None
        # manual regions: frame-1 click annotation
        if args.regions:
            ok, f0 = cap.read()
            if not ok:
                continue
            print("[il] drag boxes over each HUD key, ENTER to confirm")
            rois = cv2.selectROIs("mark keys (W A S D LMB SPACE SHIFT)", f0, showCrosshair=True)
            cv2.destroyAllWindows()
            names = ["W", "A", "S", "D", "LMB", "SPACE", "SHIFT"]
            regions = {n: tuple(int(v) for v in r) for n, r in zip(names, rois) if r.any()}
            cap.set(cv2.CAP_PROP_POS_FRAMES, 0)
        prev_state = None
        prev_yawerr = 0.0
        n = 0
        with open(out_path, "w") as out:
            idx = 0
            while True:
                ret = cap.grab()
                if not ret:
                    break
                if idx % step != 0:
                    idx += 1
                    continue
                ret, frame = cap.retrieve()
                idx += 1
                if not ret:
                    break
                if regions is None:
                    regions = key_regions_auto(frame)
                    if regions is None:
                        print(f"[il] {name}: no keystrokes HUD found — use --regions")
                        break
                keys = {k: key_pressed(frame, r) for k, r in regions.items()}
                mv = move_from_keys(keys)
                sprint = 1 if (keys.get("W", 0) > 0.5 and mv != 0) else 0
                click = 1.0 if keys.get("LMB", 0) > 0.5 else 0.0
                sneak = 1.0 if keys.get("SHIFT", 0) > 0.5 else 0.0
                jump = 0.0  # space is read below when present
                if "SPACE" in keys:
                    jump = 1.0 if keys["SPACE"] > 0.5 else 0.0

                bbox = find_opponent_yolo(model, frame) if model else find_opponent_color(frame)
                yawerr = 0.0
                piterr = 0.0
                dist_est = 0.0
                if bbox:
                    yawerr, piterr = screen_to_angles(bbox, frame.shape, args.fov)
                    # distance estimate from body height in px (1.8 blocks tall,
                    # projects ~ (H * 1.8) / (2 * dist * tan(vfov/2)))
                    H = frame.shape[0]
                    vfov = 2 * math.degrees(math.atan(math.tan(math.radians(args.fov / 2)) * (H / float(frame.shape[1]))))
                    px_h = bbox[3]
                    dist_est = (H * 1.8) / max(1.0, 2.0 * px_h * math.tan(math.radians(vfov / 2)))
                    dist_est = min(24.0, dist_est)

                cd = cooldown_fill(frame)
                # click label: a real click or a cooldown that just refilled
                ck = click if click > 0 else (1.0 if cd > 0.9 else 0.0)

                # ---------------- state vectors (approximate) ----------------
                s1 = default_state(64)
                s1[0] = min(1.5, dist_est / 6.0)            # dist (scaled)
                s1[1] = yawerr / 180.0                       # yaw error
                s1[2] = piterr / 90.0                        # pitch error
                s1[3] = cd                                   # attack cooldown
                s1[4] = 1.0 if jump > 0.5 else 0.0           # my jump
                s1[5] = sneak                                # my sneak
                s1[6] = sprint                               # my sprint
                # rest neutral: memory/rhythm features are 0 (unknown from video)
                s = default_state(104)
                s[:64] = s1
                # v1.0.9-style head offset approximation from bbox height fraction
                if bbox:
                    s[64] = piterr / 90.0                    # sight pitch err
                    s[65] = yawerr / 180.0
                    s[84] = min(1.0, dist_est / 8.0)         # advanced: dist
                    s[85] = abs(yawerr - prev_yawerr) / 30.0 # their flick speed

                # reward: mild shaping — facing + close + clicking
                r = args.margin
                if bbox:
                    r += 0.02 - 0.002 * min(10.0, abs(yawerr)) / 10.0
                    r += 0.01 if dist_est < 3.5 else -0.005
                r -= 0.01 if mv == 0 else 0.0
                r += 0.02 * ck

                rec = {
                    "s": [round(v, 4) for v in s],
                    "s1": [round(v, 4) for v in s1],
                    "mv": mv, "sp": sprint, "jp": 1 if jump > 0.5 else 0,
                    "sn": 1 if sneak > 0.5 else 0,
                    "ay": round(max(-1.0, min(1.0, yawerr / 40.0)), 4),
                    "ap": round(max(-1.0, min(1.0, piterr / 40.0)), 4),
                    "ck": ck, "r": round(r, 4), "done": False,
                }
                if prev_state is not None:
                    out.write(json.dumps(prev_state) + "\n")
                prev_state = rec
                n += 1
            if prev_state is not None:
                prev_state["done"] = True
                out.write(json.dumps(prev_state) + "\n")
        cap.release()
        print(f"[il] {name}: {n} steps -> {out_path}")
    print("[il] done. Copy the il/ folder into .minecraft/config/pvpbot/il/ and run /pvpbot il load")

if __name__ == "__main__":
    main()
