# Modern Sword PvP — Research Notes

Research that shaped the simulator's physics, the reward design and the state features.
Compiled Sept 2026 from combat guides, community glossaries, the Minecraft wiki combat
tutorial and competitive-PvP community threads.

## Core mechanics (Java 1.9+ combat)

- **Attack cooldown**: weapon speed 1.6 for a netherite sword → 12.5 tick recharge. Damage
  scales as `base × (0.2 + cp² × 0.8)` where `cp` is cooldown progress; a sub-1.0 hit deals
  reduced damage but still applies knockback. This asymmetry is the root of modern
  **hit-selection**.
- **Knockback**: base horizontal 0.4, **+0.5 extra when the attacker is sprinting** (sprint-hit
  bonus), vertical 0.4. Taking damage cancels your own sprint — the mechanic every sprint-reset
  technique exploits.
- **Crits**: ×1.5 damage, require falling (negative Y velocity, not on ground) **and NOT
  sprinting**. Sprinting cancels crits — hence the tension between KB-maxxing (stay sprinting)
  and crit-maxxing (release sprint mid-fall).
- **Armor**: full netherite = 20 armor points / 12 toughness; a clean netherite-sword hit lands
  for ~2.6 damage through it → duels are marathons of 8-12+ trades where reset timing decides
  everything.

## Techniques the bot is expected to (re)discover

| Technique | What it is | Why it wins |
|---|---|---|
| **W-tap** | release & re-press W/sprint around each hit | resets your sprint so *every* hit carries the sprint-KB bonus → combos |
| **S-tap** | tap S instead of W around the hit | same reset while staying glued to the opponent at close range |
| **Jump reset** | jump the instant you take a hit | cuts incoming KB, repositions you to counter-combo |
| **Crit / crit-chain** | land hits while falling, sprint off | 1.5× damage; chain by jumping over the KB you receive |
| **PCrit (punish crit)** | crit someone during *their* recovery after they hit you | punishes cooldown disadvantage with a 1.5× hit |
| **Hit-selecting** | choose to trade only when your cooldown ≥ theirs (or they just spent theirs) | wins the damage race without skill-symmetric trades |
| **Range control** | back off to ~3-4 blocks when your cooldown is down, re-engage at full charge | buys full-charge hits and resets |
| **Strafing (A/D spam)** | lateral direction changes | dodges raw aim tracking, forces whiffs |
| **Sprint reset vs crit tradeoff** | you cannot sprint-reset AND crit the same hit | the core modern minigame the policy must learn to navigate |
| **Terrain play** | wall/ledge/corner usage | cuts off strafe space, forces engagement geometry |

The reward function deliberately contains **zero technique-specific terms** — only damage,
kills/deaths, whiffs, combo pressure and survival. The pre-training run independently developed
sprint-resets (up to 85% of attacks), crit usage (20–75%) and jump resets, confirming these
emerge from physics + outcomes. `docs/TRAINING_REPORT.md` logs the progression.

## Notes on the scene referenced in the request

- **SWight** and **Itzrealme** are celebrated 1.9+-era sword duelists (SMP/duel circuit); their
  style is characterized by tight spacing, heavy sprint-reset usage and patient hit-selection —
  behaviors the reward model makes locally optimal, which is why they emerged in self-play.
- **HT1** = "High Tier 1" on the community tier lists (mctiers-style ladder: LT2 < HT2 < LT1 <
  HT1 < T1) for the sword gamemode.
- **mcpvp.club KitPvP**: kit-based sword fights; the bot's kit auto-detect
  (netherite/diamond/iron/leather/none) matches whatever kit you queue with.

## Simulation fidelity choices

Implemented faithfully: cooldown damage curve, crit conditions (incl. sprint cancellation),
sprint-hit KB bonus, KB-on-hit sprint cancellation for the victim, netherite armor reduction,
sprint-jump boost, 3-block reach with facing-cone check, whiffs not resetting cooldown.
Approximated: movement integration (calibrated to 4.32 / 5.61 m·s⁻¹ walk/sprint), arena-scale
geometry instead of server maps. The live bot keeps learning from real matches, which absorbs
the residual sim-to-real gap.
