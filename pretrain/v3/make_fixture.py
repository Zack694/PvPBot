"""Record a simulator rollout as the ObsV4 Java parity fixture.

Writes src/test/resources/obs_v4_fixture.json: the exact CombatFrames the
Python ObsV4 consumed (plus round/opponent resets) and the vectors it built.
ObsV4ParityTest replays the frames through the Java ObsV4 and compares.
"""
import json
import math
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from env import Match  # noqa: E402
from net import ARCH, decide, np_forward  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))


def main(ticks=900, seed=20261002):
    rng = np.random.default_rng(seed)
    ws = [rng.normal(0, math.sqrt(2.0 / ARCH[i]), size=(ARCH[i + 1], ARCH[i])).astype(np.float32) for i in range(len(ARCH) - 1)]
    bs = [np.zeros(ARCH[i + 1], np.float32) for i in range(len(ARCH) - 1)]
    ops = []
    m = Match(rng, opponent="scripted", preset="practice", rounds=3)
    m.recorder = ops
    # the constructor already ran new_round(); register its opponent reset
    ops.insert(0, {"op": "opp"})
    n = 0
    while n < ticks and m.result is None:
        obs = m.observe()
        heads = {}
        for name, o in obs.items():
            q = np_forward(ws, bs, np.asarray(o, np.float32)[None, :])[0]
            side = m.a if name == "a" else m.b
            heads[name] = decide(q, 0.4, side.held, rng)
        m.act(heads)
        n += 1
    out = os.path.join(ROOT, "src", "test", "resources", "obs_v4_fixture.json")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w") as fh:
        json.dump({"dim": 100, "ops": ops}, fh, separators=(",", ":"))
    ticks_n = sum(1 for o in ops if o["op"] == "tick")
    events = sum(1 for o in ops if o["op"] == "tick" and (o["f"]["i_hit_them"] or o["f"]["i_was_hit"]))
    print(f"wrote {out}: {ticks_n} ticks, {len(ops) - ticks_n} resets, {events} hit events, "
          f"{os.path.getsize(out) // 1024} KB")


if __name__ == "__main__":
    main()
