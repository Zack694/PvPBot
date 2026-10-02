"""Quick end-to-end check of the v3 sim: random brain vs every opponent kind.
Verifies observation shape/finiteness, transitions, rewards and throughput."""
import math
import os
import sys
import time

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from env import Match  # noqa: E402
from net import ARCH, decide, np_forward  # noqa: E402
from obs import DIM  # noqa: E402


def main(ticks=4000, seed=7):
    rng = np.random.default_rng(seed)
    ws = [rng.normal(0, math.sqrt(2.0 / ARCH[i]), size=(ARCH[i + 1], ARCH[i])).astype(np.float32) for i in range(len(ARCH) - 1)]
    bs = [np.zeros(ARCH[i + 1], np.float32) for i in range(len(ARCH) - 1)]
    kinds = [("scripted", None), ("scripted", "practice"), ("scripted", "crit"), ("policy", None)]
    n_trans = 0
    results = []
    t0 = time.time()
    steps = 0
    m = None
    k = 0
    rew = []
    while steps < ticks:
        if m is None or m.result is not None:
            opp, preset = kinds[k % len(kinds)]
            k += 1
            m = Match(rng, opponent=opp, preset=preset)
        obs = m.observe()
        heads = {}
        for name, o in obs.items():
            assert len(o) == DIM, len(o)
            arr = np.asarray(o, np.float32)
            assert np.all(np.isfinite(arr)), "non-finite obs"
            q = np_forward(ws, bs, arr[None, :])[0]
            side = m.a if name == "a" else m.b
            heads[name] = decide(q, 0.3, side.held, rng)
        trans, rr = m.act(heads)
        for tr in trans:
            n_trans += 1
            rew.append(tr[4])
            assert np.isfinite(tr[4])
        if rr is not None:
            results.append(rr[0])
        steps += 1
    dt = time.time() - t0
    print(f"ticks {steps}  transitions {n_trans}  rounds {len(results)} {dict((r, results.count(r)) for r in set(results))}")
    print(f"reward mean {np.mean(rew):.4f}  min {np.min(rew):.2f}  max {np.max(rew):.2f}")
    print(f"throughput {steps / dt:.0f} ticks/s (single process)")
    assert n_trans > 0
    print("SMOKE TEST PASS")


if __name__ == "__main__":
    main()
