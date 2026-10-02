# PvPBot

Self-learning sword-PvP AI for Fabric 1.21.11 (client mod, input-level control).

## v2.3.0-brain-preview

* **Pretrained pure-mode brain bundled.** `brain_v2.pbm` (238k offline training steps, ~170k simulated rounds)
  loads automatically when there is no compatible `models/v2-autosave.pbm`. Turn it on with `/pvpbot v2 on`.
  On the benchmark set it wins ~72% of rounds (W-only baseline 50%, strafe-only 57%, random 25%).
* **Observation v4 (100 dims).** Built from plain frames by `ml/obs/ObsV4`; a JUnit test proves the
  in-game Java code and the training simulator produce identical numbers.
* **Fixes:** real opponent velocity from position deltas (client `getVelocity()` reads ~0 for other
  players), dead v1 opponent features, n-step bootstrap discount (both brains), pure-mode governors now
  act on the stored decision, FIFO reaction delay (no dropped decisions), click range uses full vanilla
  reach (`clickMaxDist`, default 3.2), lower on-device learning rates for the pretrained brain.
* Old 104-input v2 brains are incompatible and get replaced by the bundled brain.

Offline training: see [`pretrain/v3/README.md`](pretrain/v3/README.md).
