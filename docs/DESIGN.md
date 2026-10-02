# PvPBot — Architecture & Design Decisions

## Principles

1. **Input-level actuation only.** The bot's "body" is the same body a human has: WASD key
   states, a left click (`MinecraftClient#doAttack`), and mouse deltas injected as genuine GLFW
   cursor events (`Mouse#onCursorPos`), quantized to whole counts on the user's sensitivity
   grid. Nothing touches the packet pipeline.
2. **Learned decisions, engineered body.** Perception (what it measures), the action space
   (what it can press) and the reward (what "winning" means) are fixed engineering. Every
   behavior — when to click, when to W-tap, when to reset distance — lives in network weights
   that change as the user plays.
3. **Outcome-only rewards.** No technique is named anywhere in the reward function. Techniques
   must be *discovered* because they produce damage/kill advantages under real combat physics.
4. **Self-learning live.** The bundled brain is a starting point; the DQN keeps taking gradient
   steps during real duels and after every episode (training burst + save).

## The loop (per client tick)

```
END_CLIENT_TICK (BotController.tick)
 ├─ safety gates: screen open → pause+release; death → episode end + release
 ├─ TargetSelector: lock nearest player ≤24 blocks; combat hysteresis 10 s
 ├─ HitWatcher: hurt-time/health deltas → HIT/CRIT/MISS/TAKEN + rewards + combo state
 ├─ OpponentMemory.tick: 4 Hz snapshots into a 480-slot (120 s) ring; style stats
 ├─ TerrainSense: 10 voxel raycasts, cached 3 ticks
 ├─ AimController (every tick): 12 features → predicted head offset at t+3 →
 │    Humanizer.shapeAim (smoothing/noise/cap) → Actuator.queueLook
 ├─ DQN decision (every 2 ticks): Perception 56-dim → ε-greedy action (72) →
 │    Humanizer.submit (reaction delay) → applyAction (keys/attack) →
 │    remember(prev s,a,r,s') ; DQN.trainStep (batch 32, Adam)
 └─ Actuator.applyMouse: pending degrees → whole mouse counts → onCursorPos
```

Episode end (win/loss/draw/abort) → terminal transition → 30-step training burst → target-net
sync → save `policy.json`/`aim.json`/`meta.json` → CSV log row → curriculum advance.

## Networks

| Net | Arch | Training | Cadence |
|---|---|---|---|
| Policy Q | 56 → 96 → 96 → 72 | Double-DQN, γ=0.995, replay 300k | every 2 ticks + episode bursts |
| Aim | 12 → 48 → 48 → 2 | supervised on self-labeled (features, t+3 bearing) pairs | every 8 ticks, minibatch 8 |

Serialization: `NeuralNet.toJson` (arch + per-layer w/b) via Gson; the same schema is produced
by the Python pre-trainer, so sim-trained weights drop straight into the runtime.

## Curriculum (user-selected)

- Episodes 0-19: ε 0.45→0.15 linear, LR 2e-3 — rapid, exploratory.
- Episode 20+: ε 0.06, LR 5e-4 — stable exploitation.
- `/pvpbot explore <x>` overrides; `auto` returns to schedule.

## Pre-training (what "trained by the AI author" means here)

`pretrain/train.py` self-plays 8,000 duels in the simplified combat sim (`sim_pvp.py`) against
a curriculum of scripted archetypes (aggressive sprinter, w-tapper, crit-spammer, backpedaler)
plus frozen snapshots of earlier selves (league play). The state encoder is byte-for-byte the
same 56 features the live bot perceives, so the exported policy is immediately usable and only
needs to re-tune to vanilla's exact movement feel. Results: 90-100% win rates vs all archetypes
with emergent sprint-resets, crit usage and jump resets — see `docs/TRAINING_REPORT.md`.

## Safety behaviors

- All virtual keys released on: stop command, death, screen open, disconnect.
- Chat/inventory open pauses control (releases keys), resumes on close.
- Attack path goes through vanilla's own `attackCooldown` spam guard.
- No input is produced unless the user explicitly ran `/pvpbot start` and hasn't stopped it.
