# v3 simulator + trainer (v2.3 four-head brain)

Offline pretraining for the pure-mode brain (`PolicyNet`, 100 → 512 → 512 → 256 → 18).
The trained `.pbm` is bundled as `src/main/resources/assets/pvpbot/model/brain_v2.pbm`.

| File | What it is |
|---|---|
| `obs.py` | Line-for-line port of `ml/obs/ObsV4.java` (the 100-dim observation) |
| `physics.py` | Vanilla 1.21 movement, knockback, i-frames, crits, armor, eye-to-hitbox reach |
| `bots.py` | `LearnerCtl` mirrors the Java pure-mode pipeline; `ScriptedCtl` is a randomized human-like opponent |
| `env.py` | Duel: latency (0–3 ticks per side), rounds, rewards, CombatFrames |
| `train.py` | Ape-X style: actor processes + PER double-DQN learner + evaluator + self-play league |
| `analyze.py` | Brain vs baselines (W-only, strafe, random) on the fixed benchmark set |
| `make_fixture.py` | Writes the Java parity fixture (`src/test/resources/obs_v4_fixture.json`) |

## Train

```bash
pip install torch numpy        # CPU build is enough
python train.py --run ../runs/main --minutes 25          # resumes automatically
python analyze.py ../runs/main/latest.pbm --baselines --rounds 10
cp ../runs/main/latest.pbm ../../src/main/resources/assets/pvpbot/model/brain_v2.pbm
```

Runs are chunked: each one saves model, target, optimizer, the 2M replay and the league
to `--run`, and the next one resumes from there.

## Parity rules

* Change `obs.py` and `ObsV4.java` together, then rerun `make_fixture.py`.
  `./gradlew test` fails if Java and Python disagree.
* After re-bundling a brain, regenerate `src/test/resources/brain_v2_expected.json` (see the
  bundling snippet in the PR) so `BundledBrainTest` checks the new weights.
* Rewards, governors, the delay line and the click oracle in `bots.py` mirror
  `BotController` pure mode; keep them in sync.
