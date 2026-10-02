package dev.z.pvpbot.ui;

import net.minecraft.text.Text;

/**
 * v1.0.10 — the detailed hover description for EVERY config setting
 * (user: "add Detailed Descriptions when you Hover a settings Name in the
 * config so I can easily Know what Each setting does fully"). Shared by the
 * Cloth Config bridge AND the built-in config screen so both show the same
 * explanations. Multi-line strings render as multi-line tooltips.
 */
public final class BotTooltips {

        private static Text t(String s) {
                return Text.literal(s);
        }

        public static final Text TRIGGERBOT = t(
                "Attack clicking mode: ON = the bot clicks whenever your crosshair is on the target AND the attack meter is inside the [band min, band max] window (you steer the crosshair, the bot clicks).\n" +
                "OFF = the bot never clicks at all — since v1.0.12 the neural policy can NOT attack; the TriggerBot is the only thing that may left-click.\n" +
                "Every click passes the same hard [min, max] meter gate — a click outside the window is physically blocked and retried the next tick, so 1.9 cooldown servers can never be machine-gunned.");

        public static final Text BAND_MIN = t(
                "Lower edge of the attack window. The bot may ONLY click while the CURRENT attack meter reads between band min and band max — below min the left click is hard-blocked (and retried the next tick).\n" +
                "0.82 = 82% charge. Higher = fewer but harder hits (more damage + knockback per hit). Lower = faster but weaker hits.\n" +
                "Since v1.0.12 this is an ABSOLUTE gate: no learned behavior, no server mode, no setting can click below it.");

        public static final Text BAND_MAX = t(
                "Upper edge of the attack window. Above max the left click is blocked too — the click must land inside [min, max].\n" +
                "Exception: a meter that already reached 100% may click (it can never climb back into the window) — that is exactly where a human's attack indicator sits when they swing.\n" +
                "Keep max >= min. 0.96 gives near-full-charge hits; a lower max makes the bot swing a bit sooner.");

        public static final Text WTAP_ENABLED = t(
                "Master switch for W-Tapping (S-tap style): after a sprint hit the bot releases W, holds S for the configured time, then walks forward again — this resets sprint so the NEXT hit carries full 1.9 knockback.\n" +
                "The exact loop: sprint hit -> W off -> S held (WTap S-hold ms) -> W back -> repeat on the next hit.");

        public static final Text SPRINT_HIT_ONLY = t(
                "Forces sprint ON for every non-retreating stance and only allows grounded clicks while ACTUALLY sprinting.\n" +
                "A grounded click without sprint is a vanilla SWEEP attack (the wide arc hit with no knockback) — this toggle makes every hit a proper sprint hit instead. Midair crit/descent clicks are exempt (sweeps need solid ground). If sprint is unavailable (hunger/server), a bypass latches after 60 ticks so attacks keep working.");

        public static final Text WTAP_CHANCE = t(
                "How often a sprint hit triggers the S-tap.\n" +
                "0.0 = never. 1.0 (or higher) = EVERY eligible sprint hit instantly stops W and presses S for the configured hold time — deterministic, no RNG, no mind veto. Values between roll per hit.\n" +
                "Physical gates always apply: target must be within ~3.2 blocks and the tap rate is limited.");

        public static final Text WTAP_PURE_S = t(
                "Share of taps that hold straight S vs. diagonal SA/SD (only used when WTap chance is below 1.0 — at 1.0 the tap is ALWAYS straight S).\n" +
                "0.8 = 80% straight S, 10% S+A, 10% S+D. Diagonals pull the tap sideways like real players who drift while resetting.");

        public static final Text WTAP_MIN_MS = t(
                "Shortest S-hold duration in milliseconds. The bot picks a random hold time between min and max each tap so the rhythm is never constant.\n" +
                "550-650 ms (~0.6 s) is the classic WTap feel: long enough to drop sprint fully, short enough to re-close before they recover.");

        public static final Text WTAP_MAX_MS = t(
                "Longest S-hold duration in milliseconds. Keep max >= min.\n" +
                "Above ~800 ms you start drifting noticeably far back; below ~300 ms sprint may not fully reset. 550-650 is the sweet spot.");

        public static final Text JUMP_RESET_MIN_MS = t(
                "Shortest duration of the jump-reset: after THEY hit you, the bot jumps within 100-150 ms and lands a hit while airborne — it resets THEIR sprint, cutting their next knockback.\n" +
                "Only fires at melee range (<= 3.5 blocks), never while backing off.");

        public static final Text JUMP_RESET_MAX_MS = t(
                "Longest delay before the jump-reset hop. The bot rolls a random delay between min and max each time so the counter-hit rhythm is not fixed.\n" +
                "Keep max >= min; 100-150 ms is the human-pro window.");

        public static final Text JUMP_RESET = t(
                "Jump-resetting master switch: when you take a hit at melee range, the bot hops immediately and punches back midair.\n" +
                "This is the defensive answer to W-Tappers — your return hit resets their sprint so their next hit knocks you less far. Never fires while retreating or tapping.");

        public static final Text SNEAK_HIT = t(
                "Sneak-hit chance: while the crosshair is on the target at click time, hold shift for the click.\n" +
                "A shifting defender's hitbox shrinks and their name hides — it also fakes out aim prediction.\n" +
                "0.0 = never, 1.0 = EVERY click is a shift-click (deterministic), between = rolled per click. Hard-gated to <= 3 blocks — never sneaks at a far target.");

        public static final Text SNEAK_JUMP_HIT = t(
                "The advanced variant: sneak AND jump on the same click (shift-click + hop). Extremely disorienting to fight against.\n" +
                "1.0 = every sneak window also hops. Requires Sneak hit chance to fire first.");

        public static final Text CRIT_CHANCE = t(
                "Jump-crit attempts: the bot jumps, RELEASES W (so it rises straight instead of drifting into you), holds the click until it is FALLING, and lands the hit on the way down — a vanilla critical (+50% damage).\n" +
                "0.0 = never. 1.0 = self-driving, fires every time the crit cooldown is ready and the target is 1.2-3.4 blocks away. Between = rolled when the policy votes to jump.\n" +
                "MidAir hits (rising) are a separate setting and stay available.");

        public static final Text MIDAIR_CHANCE = t(
                "Rising mid-air hits for raw knockback: the bot hops and punches on the way UP (not a crit — a displacement tool that launch opponents off edges/into walls).\n" +
                "0.0 = never, 1.0 = auto on its cooldown, between = rolled. Never fires while backing off, tapping, or escaping.");

        public static final Text BACKOFF_BELOW = t(
                "If the target is closer than this (blocks), start backing off — inside this ring you are inside their swing arc and trade badly.\n" +
                "1.35 is the calibrated default. The retreat is a diagonal ARC (wall-aware), capped in length, and it re-closes automatically — no endless retreating.");

        public static final Text BACKOFF_RELEASE = t(
                "Distance (blocks) at which the backoff stops and the bot walks back in. 2.1 = it creates roughly half a block of breathing room per trade.\n" +
                "The adaptive engine may stretch this 1.8-2.6 per opponent (rushers earn more room).");

        public static final Text BACKOFF_MAX = t(
                "Hard cap (game ticks, 20/s) on how long ONE backoff may last, no matter what. 24 ticks = 1.2 s.\n" +
                "Prevents the old bug of backing across the arena. The over-retreat governor additionally forces re-engagement if the bot drifted back too long overall.");

        public static final Text STRAFE_DISCIPLINE = t(
                "LEGACY toggle (default OFF). When ON, pure A/D strafes from the policy are remapped to forward movement so the bot never stands still or strafes for its own sake.\n" +
                "Leave OFF — the neural policy plus combo strafe handle movement far better now.");

        public static final Text AIM_ZONE = t(
                "WHERE the crosshair holds vertically:\n" +
                "0 = Head — wanders across chin-to-crown like a human tracking the head (default).\n" +
                "1 = Eyes — straight lock on the eye line (1.62 blocks up).\n" +
                "2 = Neck — straight lock at ~1.44 blocks, the classic PvP aim line.\n" +
                "3 = Chest — straight lock at ~1.13 blocks, maximum hit margin.\n" +
                "Straight zones hold a CONSTANT height (no vertical wandering). The aim point can never leave the target's hitbox in any zone.");

        public static final Text AIM_ASSIST = t(
                "The corrective-assist blend: the neural aim net predicts the correction, and a measured-error assist pulls the crosshair toward the live aim point every tick.\n" +
                "ON = hybrid (net + assist). OFF = pure neural net (less stable while it is still learning your opponents).");

        public static final Text AIM_ASSIST_STRENGTH = t(
                "How strongly the measured error dominates the blend. 0 = net only, 1 = fully trust the live measured error (clamped here even though the UI allows up to 3).\n" +
                "0.75-0.88 is the tuned range. At large sudden errors (>25 deg) the assist temporarily strengthens so the bot never chases a stale prediction at nothing.");

        public static final Text AIM_PREDICT = t(
                "Aim PREDICTION: the crosshair leads the target by velocity + acceleration (quadratic), including vertical motion — it shoots ahead of runners and meets jumps.\n" +
                "OFF = aim at where the target IS, not where it will be. Keep ON unless the bot seems to over-lead at point blank.");

        public static final Text FRAME_AIM = t(
                "Aim at render frame rate (60-240 Hz) instead of 20 ticks/s. Visually much smoother tracking and flicks, with the same humanized shaping underneath.\n" +
                "Recommended ON. Learning and clicking still run at tick rate — only the camera loop changes.");

        public static final Text SMOOTH_MIN = t(
                "Lower bound of the random per-flick smoothing factor. Smoothing = how much of the desired turn is applied this tick (lower = smoother/softer).\n" +
                "0.62 with max ~0.82 feels human. Values above ~0.95 get twitchy.");

        public static final Text SMOOTH_MAX = t(
                "Upper bound of the smoothing factor. Each new flick rolls between smooth min and max so the responsiveness varies like a real hand.\n" +
                "Keep max >= min and below ~0.98.");

        public static final Text AIM_LEAD = t(
                "Ticks (20/s) of lead used by aim prediction. 2 = the crosshair aims at where the target should be in 100 ms.\n" +
                "Higher leads meet fast runners better but over-lead strafing at point blank (the range cap already limits this). 0-2 recommended.");

        public static final Text TURN_CAP = t(
                "Maximum camera turn speed, degrees per tick. 55 is the tuned default — fast flicks without inhuman snaps.\n" +
                "Raise for snappier tracking (up to 180), lower for a calmer, more human look.");

        public static final Text AIM_NOISE = t(
                "Random micro-jitter added to every aim step (degrees). This is the tiny hand tremor that makes tracking indistinguishable from a human at the packet level.\n" +
                "0.15-0.4 recommended. 0 = robotic pixel-locks; big values = drunk aim.");

        public static final Text DECISION_MIND = t(
                "The deliberate 'inner voice': every N ticks the mind scores 32 tactical intents (sneak-hit rhythm, crit trade, bait-punish, tempo spike, orbit hold, chase-down...) from live fight features + what it has learned about THIS opponent, and biases movement/techniques toward the winner. It explores under-used intents so new strategies keep emerging.\n" +
                "Its thought appears in the HUD thought line and /pvpbot status.");

        public static final Text MIND_BIAS = t(
                "How strongly the mind's chosen intent biases the neural policy's movement decision (0 = suggest only, 1 = strong steer).\n" +
                "0.5-0.7 keeps the learned policy in charge while the mind nudges tactics.");

        public static final Text INNOVATION = t(
                "Chance per deliberation to deliberately try the LEAST-used intent — the exploration that lets the bot invent strategies nobody scripted.\n" +
                "0.1-0.2 = healthy experimentation. 0 = it only repeats what already worked.");

        public static final Text DELIBERATE_TICKS = t(
                "How often (game ticks, 20/s) the mind re-scores its intents. Every 10 ticks = 2 thoughts per second.\n" +
                "Lower = more reactive but twitchier; higher = calmer plans.");

        public static final Text MODEL_TECHNIQUES = t(
                "Let the MODEL vote when techniques fire (sneak-hit windows, tempo spikes, midair denial, combo-extension), instead of pure raw chance rolls.\n" +
                "Your chance settings stay the CEILING, and 0.0 / 1.0 values remain fully deterministic either way.");

        public static final Text COMBO_STRAFE = t(
                "During a live exchange (any hit dealt or taken in the last 2 s) inside 1.35-3.4 blocks, the bot orbits on WA/WD arcs instead of running in a straight line — sprint stays held, so it is literally strafe-while-sprint-hitting.\n" +
                "Wall-aware flips direction when a wall blocks the orbit; the hold tempo adapts per opponent.");

        public static final Text ADAPTIVE_STYLE = t(
                "Per-opponent adaptation engine: every 2 s it scores how the fight is going and drifts its TIMINGS (attack band, tap rate, air game) and MOVEMENT (orbit tempo, spacing) toward whatever counters THIS opponent's style — rushers get met with strafe, resetters with pressure, retreaters with pursuit.\n" +
                "Profiles persist per player UUID in config/pvpbot/adapt.json. Deterministic 0.0/1.0 chance settings are never touched.");

        public static final Text IMITATION = t(
                "Human-train mode (DQfD): watch YOU fight and record your movement/click choices as demonstration data that steers the policy's training.\n" +
                "Your clicking rhythm is NOT copied — pacing gates are structural — only decisions are learned.");

        public static final Text IMITATION_RATIO = t(
                "How large a fraction of each training batch is demonstration data vs. the bot's own experience. 0.3 = 30% human demos.\n" +
                "Higher = the bot mimics you harder; above ~0.6 it stops exploring on its own (clamped).");

        public static final Text KILL_REWARD = t(
                "Reward signal for winning a round. Kills paying big (default ~40) teach the policy to close fights, not just survive.\n" +
                "0-300 allowed; raising it makes the bot more aggressive about finishing.");

        public static final Text LOSS_REWARD = t(
                "Negative reward for losing a round (0 to -300). Bigger penalties make the policy more risk-averse about dying — lowering it makes the bot braver/more trade-happy.");

        public static final Text ROUND_TEXT = t(
                "Read the big on-screen round text (VICTORY / ROUND LOST / DEATH) as the source of truth for round results in training sessions.\n" +
                "Turn OFF on servers that show no round text — deaths and kills alone settle rounds then.");

        public static final Text ROUND_DEBOUNCE = t(
                "Milliseconds during which duplicate round results (text + death + kill firing together) collapse into ONE settle. 5000 ms covers double-announcements.\n" +
                "Raise if rounds get double-counted on a laggy server.");

        public static final Text HUD = t(
                "Master switch for the on-screen HUD (state, target, distance, mind thought, trade log, keystrokes, combo meter). Purely visual — zero gameplay effect.");

        public static final Text KEYSTROKES = t(
                "Keystrokes overlay: W / A / S / D / LMB / Space / Shift blocks that light up with the bot's REAL key states (your own presses light them too when you take over). Great for verifying the WTap S-press and sprint rhythm.");

        public static final Text THOUGHT_HUD = t(
                "Shows what the Decision Mind is currently deliberating, e.g. 'hmm... he's comboing me — sneak hit to break his rhythm?' with the active [INTENT] tag. Transparency into the bot's tactics.");

        public static final Text TRADE_LOG = t(
                "Scrolling log of hits dealt/taken with distances — useful to spot ghost hits, whiffs, or whether taps/crits are landing.");

        public static final Text TRAINING_STATS = t(
                "Overlay of training telemetry: episode count, epsilon, last loss, replay size. For nerds watching the brain learn.");

        public static final Text COMBO_METER = t(
                "Combo counter display: consecutive hits landed without taking one, resetting when you get hit.");

        public static final Text BACKOFF_ENABLED = t(
                "Master switch for the spacing backoff: when the opponent pushes closer than 'Backoff below', the bot creates space with a short wall-aware arc retreat instead of hugging.\n" +
                "OFF = the bot holds ground and trades at point blank (worse on 1.9 — you eat full knockback combos).");

        public static final Text HUMANIZE = t(
                "Humanized input shaping master switch: rate limits, easing, micro-noise and click bursts wrap EVERY action the bot takes so its inputs are indistinguishable from a player's at the packet level.\n" +
                "OFF = raw instant inputs (robotic, detectable). Keep ON.");

        public static final Text GRID_SNAP = t(
                "Mouse sensitivity grid snap: injected aim deltas are nudged to the same sub-pixel grid your sensitivity setting produces, so the bot's camera moves look native to your config.");

        // ---- v2.2.0 ----------------------------------------------------------

        public static final Text ANTI_WOBBLE = t(
                "The master smoothness dial (0 = raw tracking, 1 = maximum calm). Scales FIVE calm mechanisms at once:\n" +
                "1. the measured aim error is exponentially smoothed in TIME so the crosshair chases a calm estimate, not every per-frame jitter;\n" +
                "2. the micro-deadzone grows (sub-degree residuals stop being chased);\n" +
                "3. the horizontal wander sway shrinks;\n" +
                "4. the random smoothing fraction re-rolls on a longer human rhythm instead of every frame;\n" +
                "5. the sign-flip oscillation damper engages faster.\n" +
                "Set this to 1.0 if ANY wobble remains visible — it cannot slow real tracking down (the error estimate follows strafes in ~50ms).");

        public static final Text PURE_MODE = t(
                "PURE MODE: the v2 four-head brain (movement, sprint, jump, sneak, aim, click) is the ONLY authority — every v1 technique chance and the TriggerBot are bypassed.\n" +
                "The v1.0.12 physics laws REMAIN: the hard [band min, band max] meter gate, the 6.7 CPS click governor, the on-target raycast, the sprint-hit rule.");

        public static final Text PURE_IMMEDIATE = t(
                "PURE ATTACK LAW: ON = the pure brain clicks THE MOMENT its crosshair can hit the hitbox (the hard band window + click governors still pace the swing — no spam, no TriggerBot needed).\n" +
                "The trained click head can only add clicks on top; it can NEVER veto a valid hit again.\n" +
                "OFF = a mature click head (proven low loss) is the intent authority on its own.");

        public static final Text PURE_RETREAT = t(
                "Retreat governor for pure mode: after this many CONSECUTIVE backward ticks inside combat range — while not losing badly (their HP >= mine + 4) — the retreat is converted into an orbit strafe toward the target and a small reward penalty teaches the habit away.\n" +
                "v2.2.1 default 4 (about 0.2s of backing up allowed). 0 = governor off (learned behavior only).");

        public static final Text PURE_CLOSE = t(
                "v2.2.1 aggression floor — the other half of the back-off fix: the retreat governor only catches BACKWARD moves, but the brain also learned to HOVER just outside reach (idle strafing at 3m+) where nothing ever happens. After this many consecutive ticks beyond 3.0m — while not losing badly — the movement is overridden with a closing move until the bot is back inside the pocket (<= 2.5m), and a small reward penalty teaches the habit away.\n"
                +
                "20 = default (1s of hovering allowed). 0 = floor off.");

        public static final Text SPRINT_PATIENCE = t(
                "v2.2.1 sprint-gate patience in pure mode: vanilla only sprints while moving FORWARD, so while the brain strafes or backs up the sprint-hit gate used to hold valid clicks for up to 3 SECONDS (the old 60-tick bypass) — the main 'crosshair on the hitbox but it never attacks' blocker.\n"
                +
                "10 = default: after 0.5s of holding fire the click is allowed through. 2 = attack first, sweep never mind. v1 mode keeps the 60-tick tradeoff.");

        public static final Text PURE_AIM_HEAD = t(
                "Opt-in for the v2 aim head to join the camera blend. It stays on the BENCH unless it has earned execution: this toggle + >= 200000 training steps + a supervised aim loss <= 0.005.\n" +
                "While benched, the proven tracker owns the aim (identical to the first model) — the head keeps TRAINING every tick either way.");

        public static final Text PURE_SNEAK = t(
                "Opt-in for the sneak muscle. HARD-OFF unless this toggle is on AND the brain has >= 100000 training steps AND the sneak head decides with a decisive margin — and even then it can never hold longer than 8 ticks (then a 10-tick forced cooldown).\n" +
                "This is the 'pure model always holds shift' killer.");

        public static final Text PURE_AIM_BLEND = t(
                "Legacy bootstrapping blend toward the tracker (only read when the aim head is PROMOTED). 0 = head only, 1 = tracker only. Not an aim assist — it never runs while the head is benched.");

        public static final Text PURE_AIM_MAX = t(
                "Degree scale for the pure aim labels and the tracker rate limit: corrections are clamped to this many degrees per tick before shaping. 40 = default (a full 180° flick takes ~5 ticks).");

        public static final Text PURE_SHAPING = t(
                "Face-target reward shaping: improving the angular error to the target pays, losing the target (>25°) costs, holding it centered (<6°) pays a small bonus. A LEARNING signal for the pure brain — never an execution assist.");

        public static final Text IL_AUTOLOAD = t(
                "Auto-load every extractor session (*.jsonl) from .minecraft/config/pvpbot/il/ into BOTH brains' expert rings at launch.\n" +
                "The sessions are produced from PvP videos (good pvper gameplay + visible keystrokes) by scripts/il_extract.py in the source zip: keystrokes via HUD analysis/OCR, the opponent's screen position via color/YOLO detection, attack timings via the cooldown bar.\n" +
                "Loaded demos steer all later training via DQfD margin cloning. Check the live status line below, /pvpbot il status, or the buttons here.");

        public static final Text CLICK_MAX_DIST = t(
                "v2.3 click range (feet-to-feet blocks, v1 + pure). Every click still needs vanilla's own live raycast to hit the opponent's hitbox within the real 3.0 reach, so this is only an outer sanity cap.\n" +
                "The old hard 2.95 cap threw away ~0.3 blocks of legal reach (reach is measured eye -> nearest hitbox point, ~3.3 center-to-center) and let opponents out-range the bot. 3.2 = default.");

        public static final Text V2_LR = t(
                "v2.3 on-device learning rates of the v2 brain (rapid = first curriculum episodes, stable = afterwards). The brain ships PRETRAINED from hours of simulator training; the v1 rates (0.002) would wash that out within minutes.\n" +
                "Defaults 0.0001 / 0.00005. Raise only if you want it to re-learn fast from your own fights.");

        private BotTooltips() {}
}
