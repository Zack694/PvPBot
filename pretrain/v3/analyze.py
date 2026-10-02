"""Behaviour report: learned brain vs simple baselines on the benchmark set.

  python3 analyze.py runs/main/best.pbm [--rounds 20]

Baselines drive the SAME pure-mode controller (tracker aim, click oracle,
governors), so the comparison isolates what the brain's movement/jump
decisions are worth.
"""
import argparse
import math
import os
import sys
from collections import Counter

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from env import Match  # noqa: E402
from net import load_pbm, np_forward, decide  # noqa: E402
from train import BENCH  # noqa: E402


def baseline_head(kind, rng):
    if kind == "w_only":
        return {"move": 1, "sprint": True, "jump": False, "sneak": False, "sneak_margin": 0, "click": 0.0}
    if kind == "random":
        return {"move": int(rng.integers(0, 9)), "sprint": bool(rng.random() < 0.5),
                "jump": bool(rng.random() < 0.1), "sneak": False, "sneak_margin": 0, "click": 0.0}
    if kind == "strafe":
        return {"move": int(rng.choice([5, 6])), "sprint": True, "jump": False, "sneak": False,
                "sneak_margin": 0, "click": 0.0}
    raise ValueError(kind)


BAND = None


def run(policy, rounds, seed0=0):
    tot = Counter()
    moves = Counter()
    jumps = 0
    decisions = 0
    dists = []
    dealt = taken = 0.0
    per = {}
    for kind, preset, seed in BENCH:
        rng = np.random.default_rng(seed + seed0)
        done = 0
        w = 0
        while done < rounds:
            lc = None
            if BAND:
                from bots import LearnerCfg
                lc = LearnerCfg()
                lc.band_min, lc.band_max = BAND
            m = Match(rng, opponent=kind, preset=preset, rounds=rounds - done, learner_cfg=lc)
            while m.result is None:
                o = m.observe()
                heads = {}
                for name, vec in o.items():
                    if isinstance(policy, str):
                        heads[name] = baseline_head(policy, rng)
                    else:
                        q = np_forward(policy[0], policy[1], np.asarray(vec, np.float32)[None, :])[0]
                        heads[name] = decide(q, 0.0, m.a.held, rng)
                    h = heads[name]
                    moves[h["move"]] += 1
                    jumps += h["jump"]
                    decisions += 1
                a, b = m.a.body, m.b.body
                dists.append(math.hypot(a.x - b.x, a.z - b.z))
                _, rr = m.act(heads)
                if rr is not None:
                    done += 1
                    tot[rr[0]] += 1
                    w += rr[0] == "WIN"
                    dealt += rr[1]["dealt"]
                    taken += rr[1]["taken"]
        per[f"{preset or 'scripted'}-{seed}"] = w
    n = sum(tot.values())
    names = ["idle", "W", "S", "A", "D", "WA", "WD", "SA", "SD"]
    mv = " ".join(f"{names[k]}:{100 * moves[k] / decisions:.0f}%" for k in range(9))
    d = np.asarray(dists)
    return {
        "winrate": tot["WIN"] / n, "per": per, "dmg_ratio": dealt / max(1e-6, taken),
        "moves": mv, "jump%": 100 * jumps / decisions,
        "dist": f"mean {d.mean():.2f} | in 2.2-3.3: {100 * np.mean((d > 2.2) & (d < 3.3)):.0f}% | <1.5: {100 * np.mean(d < 1.5):.0f}%",
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("models", nargs="*")
    ap.add_argument("--rounds", type=int, default=12)
    ap.add_argument("--baselines", action="store_true")
    ap.add_argument("--band", type=str, default=None)
    a = ap.parse_args()
    global BAND
    if a.band:
        BAND = tuple(float(x) for x in a.band.split(","))
    entries = []
    if a.baselines:
        entries += [("w_only", "w_only"), ("strafe", "strafe"), ("random", "random")]
    for p in a.models:
        d = load_pbm(p)
        entries.append((os.path.basename(p) + f"@{d['steps']}", (d["ws"], d["bs"])))
    for name, pol in entries:
        r = run(pol, a.rounds, seed0=999)
        print(f"== {name}: winrate {r['winrate']:.2f}  dmg ratio {r['dmg_ratio']:.2f}  jump {r['jump%']:.1f}%")
        print(f"   per-opponent wins/{a.rounds}: {r['per']}")
        print(f"   moves: {r['moves']}")
        print(f"   distance: {r['dist']}")


if __name__ == "__main__":
    main()
