# Pre-training report (v1.0.4 brain)

Kit: diamond sword + full diamond armor, no food (no healing).

Brain: 64 -> 480 -> 480 -> 72 (296,712 params, 1.13 MB float32)

Training: 1 hour exactly (3,606 s cumulative across 8 resumable chunks) |
26,573 episodes | 526k+ DQN steps | final epsilon 0.05 |
all sim opponents use v1.0.4 live-parity tactics (82-96% attack band,
W/WA/WD w-taps, 100-150 ms jump resets, too-close backoff) — much harder
than the v1.0.3 opponent set.

## Final win rates (n=100 each)

| opponent | win rate | avg dmg margin |
|---|---|---|
| aggressive (relentless, perfect timing, jump-resets 75%) | 0.31 | -4.7 |
| wtapper | 0.95 | +5.0 |
| critspam | 1.00 | +20.1 |
| backpedal | 0.94 | +4.2 |
| practicebot (mirror: aggressive + w-tap + jump reset + backoff) | 0.27 | -6.2 |

The two remaining sub-50% matchups are the perfect-timing spammers; the
sim gives them frame-perfect auto-facing and deterministic move logic.
Live learning (DQfD imitation from /pvpbot human-train + RL from every
duel) continues improving the policy on-device from this baseline.
