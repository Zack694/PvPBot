# PvPBot Pretrain Pipeline

Self-play pretrainer for the PvPBot sword-PvP brain. Trains the 64-dim ->
480x480 -> 72-action double-DQN policy against a rotating curriculum of
scripted archetypes (aggressive sprinter, w-tapper, crit-spammer, backpedaler,
practice-bot) plus frozen snapshots of itself (35% self-play), then trains the
12 -> 64x64 -> 2 aim network on recorded trajectories. Exports weights in the
Java NeuralNet JSON schema that the mod loads.

## Files

| File | What it is |
|------|------------|
| `train.py` | Main trainer: curriculum, episodes, rewards, shaping, checkpointing, JSON export |
| `dqn.py` | From-scratch numpy double-DQN (`DQN`) + MLP (`Net`) with Adam + backprop |
| `sim_pvp.py` | The PvP simulation: 1.9+ combat parity (cooldown damage curve, crit requires falling + NOT sprinting, sprint-hit KB bonus, diamond armor math, reach cone, arena walls) |
| `out/` | Outputs of the last run (see below) |

## Requirements

- Python 3.10+
- numpy (`pip install numpy`)

## Usage

```
python3 train.py [episodes] [outdir] [time_seconds] [resume]
```

- `episodes` — episode cap (default 12000). Ignored when a time budget is set.
- `outdir` — output directory (default `./out`)
- `time_seconds` — if > 0, stops on TIME instead of episode count
  (epsilon/lr schedule anchored to 20000 expected episodes, ~1h for the 480x480 brain)
- `resume` — literal 4th arg; continues from `outdir/checkpoint.json`
  (episode count, cumulative trained seconds, aim pool, all weights)

Examples:

```
# fresh 12000-episode run
python3 train.py

# exactly 1 hour, resuming the shipped checkpoint
python3 train.py 20000 out 3600 resume

# short smoke test
python3 train.py 50 out_smoke
```

A checkpoint is auto-saved every 240s (and at chunk exit), so a killed run
loses at most 4 minutes. The sandbox/server reaping background processes is
why the time-budget + resume modes exist: drive multiple foreground chunks
back to back with `resume`.

## Outputs (out/)

- `policy.json` — trained policy weights (~6.4MB JSON) — this is the file the
  mod embeds as `assets/pvpbot/model/policy.json`
- `aim.json` — trained aim-network weights — embeds as
  `assets/pvpbot/model/aim.json`
- `checkpoint.json` — full resume state (weights + target net + aim net +
  aim pool + episode counter + cumulative seconds)
- `TRAINING_REPORT.md` — final eval of the last run

To ship newly trained weights: copy `out/policy.json` + `out/aim.json` into
`src/main/resources/assets/pvpbot/model/` in the mod project and rebuild the
jar (`gradle build`). At runtime the mod also keeps training on-device and
saves improvements to `config/pvpbot/model/`, so embedded weights are only
the starting brain.

## Parity notes

Constants in `train.py` mirror the live bot (v1.0.5+ parity): attack band
0.82-0.96, w-tap 0.90 chance / 0.80 pure-S / 11-14 tick hold, jump reset
2-3 ticks after being hit, backoff 1.35 start / 2.1 release / 24-tick cap,
kill pays 45 (7.5x), movement shaping constants are per-decision (2x the
Java per-tick values). If you change live-bot config defaults, mirror them
here before retraining or the sim teaches stale habits.
