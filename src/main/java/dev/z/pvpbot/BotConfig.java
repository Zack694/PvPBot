package dev.z.pvpbot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Mod configuration, persisted to config/pvpbot/config.json.
 *  Editable in-game: /pvpbot config (Cloth Config screen if installed,
 *  otherwise the built-in advanced config screen). */
public final class BotConfig {

        // v2.2 tuning. MIGRATION: older saved configs keep their old values
        // on disk, so load() upgrades anything below configVersion 12.
        public int configVersion = 14;

        // ---- v2.2.0 AIM CALM (user: "still a bit wobbly even if Anti Wobble is
        //      set to max") — the master smoothness dial, 0 = raw tracking (old
        //      behavior), 1 = maximum calm. Scales: the measured-error EMA time
        //      constant, the micro-deadzone, the wander sway amplitude, the
        //      smoothing re-roll window and the sign-flip oscillation damper.
        public float antiWobble = 0.65f;

        // ---- v2.2.0 PURE ATTACK LAW (user: "90% of the time the Pure Model
        //      should attack immediately when its crosshair can hit the hitbox,
        //      without triggerbot") — when ON, a pure-mode click opens the
        //      moment the deterministic oracle says so (crosshair on the
        //      hitbox + hard band gate + gaps + range). The trained click head
        //      can only ADD clicks on top; it can never veto a valid hit.
        public boolean pureImmediateAttack = true;

        // ---- v2.2.1 SPRINT-GATE PATIENCE: how many ticks the sprint-hit
        //      gate may hold a valid click while sprint has not engaged yet
        //      (vanilla only sprints while moving FORWARD — strafing/backing
        //      never engages it). The old 60-tick (3 s) hold was the main
        //      "crosshair on the hitbox but it never attacks" blocker in
        //      pure mode. Pure mode uses 10 ticks (0.5 s) then lets the
        //      click through; v1 keeps 60 (the sweep-avoidance tradeoff).
        public int sprintGatePatiencePure = 10;

        // ---- v2.2.0 RETREAT GOVERNOR (user: "reduce the Pure Model backing
        //      off tooooo much") — after this many consecutive backward ticks
        //      inside combat range (and while not losing badly), the retreat
        //      is converted into an orbit strafe. 0 = governor off.
        //      v2.2.1: default tightened 6 -> 4 (the user asked AGAIN).
        public int pureRetreatLimit = 4;

        // ---- v2.2.1 AGGRESSION FLOOR (the other half of "backing off
        //      tooooo much"): if the pure brain idles BEYOND closing range
        //      (3.0m) for this many consecutive ticks while not losing badly,
        //      the movement is overridden with a closing move until the bot
        //      is back inside the pocket (<= 2.5m). 0 = floor off.
        public int pureCloseLimit = 20;

        // ---- v2.2.0 IMITATION-FROM-VIDEO (user: "train on good pvper videos
        //      with keystrokes") — auto-load every *.jsonl session that the
        //      IL extractor produced from .minecraft/config/pvpbot/il/ at boot.
        public boolean ilAutoLoad = true;

        // ---- humanizer (max stealth profile) ----
        public boolean humanize = true;
        public int reactionMinTicks = 1;   // 50ms
        public int reactionMaxTicks = 3;   // 150ms  (60–130ms band incl. aim smoothing)
        public float aimSmoothMin = 0.50f; // v1.0.5: was 0.28 — aim closed errors ~2x slower
        public float aimSmoothMax = 0.70f; // v1.0.5: was 0.45
        public float aimMaxTurnDeg = 40f;  // per tick hard cap (was 28 — capped flicks)
        public float aimNoiseDeg = 0.12f;  // micro jitter
        public int attackMinIntervalTicks = 1; // tiny anti-double-click gap only
        public boolean sensitivityGridSnap = true; // v1.0.5: kept for compat; mouse deltas are now fractional (sub-count) — see Actuator

        // ---- attack timing (user: click only inside a randomized 82%-96% band) ----
        public float attackCooldownMin = 0.82f; // band start
        public float attackCooldownMax = 0.96f; // band end — each next swing re-rolls a threshold inside the band
        public boolean triggerBot = true;       // click the instant the crosshair crosses the target inside the band

        // ---- aim ----
        public boolean aimAssistEnabled = true;   // corrective-assist blend (toggleable; net still contributes)
        public float aimAssistStrength = 0.75f;   // 0 = net only, 1 = full corrective assist (0-3.0 in UI, clamped 0-1)
        public boolean frameAim = true;           // aim loop runs every RENDER FRAME (60Hz+), not 20Hz
        public float frameAimHz = 60f;            // target rate for smoothing calculations
        public int aimLeadTicks = 2;              // v1.0.5: lead the target by its velocity (kills trailing misses)
        public boolean aimPredict = true;         // v1.0.6: QUADRATIC prediction (velocity + acceleration lead)

        // ---- w-tap (v1.0.5 user spec: SPRINTING + HIT + press S ~0.6s + release) ----
        public boolean wtapEnabled = true;
        public float wtapChance = 0.90f;
        public float wtapPureWChance = 0.80f; // pure S-tap share; rest splits SA / SD diagonals
        public int wtapMinMs = 550;           // was 60 — user: hold S ~0.6s, then release
        public int wtapMaxMs = 650;           // was 140

        // ---- sneak hits (user: shift + hit, sometimes shift + jump + hit) ----
        // v1.0.7 CHANCE SEMANTICS — every chance in this file is now
        // deterministic at the extremes: 0.0 = NEVER, 1.0 (or more) = ALWAYS
        // on every eligible opportunity (cooldowns only apply to the
        // probabilistic in-between values). The sneak roll happens AT CLICK
        // TIME, so 1.0 really makes every hit a shift-hit.
        public boolean sneakHitsEnabled = true;
        public float sneakHitChance = 0.18f;
        public float sneakJumpHitChance = 0.06f;
        public int sneakHitCooldownTicks = 40;

        // ---- v1.0.7 sprint-hit rule (no more sweep hits / whiffs) ----
        // Sprint is force-enabled for every non-retreating stance and a
        // grounded click only fires on a REAL sprint — a grounded charged
        // click while walking is a vanilla SWEEP attack. Sneak windows are
        // the deliberate exception (shift-click IS the sneak technique).
        public boolean sprintHitOnly = true;

        // ---- jump reset (user: jump exactly 100-150ms after being hit — nothing else) ----
        public boolean jumpResetEnabled = true;
        public int jumpResetMinMs = 100;
        public int jumpResetMaxMs = 150;

        // ---- spacing (user: never stand inside the opponent, but never
        //      back off forever either) ----
        public boolean backoffEnabled = true;
        public float tooCloseDist = 1.35f;     // v1.0.6: was 1.05 — the bot kept hugging the opponent
        public float backoffReleaseDist = 2.1f; // v1.0.6: was 1.8 — room to swing again
        public int maxBackoffTicks = 24;        // v1.0.6: hard cap — backing off can NEVER run longer

        // ---- strafe discipline (v1.0.5 DEFAULT OFF: the DQN owns movement now.
        // The old mask mapped A/D to STAND STILL and SA/SD to BACK UP when not
        // comboing — that was the "stands still / backs off sometimes" bug.
        // Movement is learned via reward shaping + your human-train demos.) ----
        public boolean strafeDiscipline = false;

        // ---- decision thinking (v1.0.6): the deliberate "inner voice" that
        //      weighs intents (lunge / sneak trap / crit / reset …) from live
        //      situation features + the DQN's own Q-votes + learned success,
        //      then SOFTLY biases action choice. Not a scripted strat list —
        //      intent weights are learned online and explored. ----
        public boolean decisionMindEnabled = true;
        public float decisionBias = 0.25f;      // 0 = pure DQN, 1 = intent always wins mismatch
        public float innovationChance = 0.05f;  // chance per deliberation to try the LEAST-used intent
        public int mindDeliberateTicks = 10;    // deliberate ~2x per second (emergency overrides sooner)

        // ---- v1.0.8 model-decides + adaptation + combo strafe ----
        // modelTechniques: the decision mind VOTES on when sneak/jump/tap fire
        // (within the config chance ceilings — 0.0/1.0 stay deterministic).
        public boolean modelTechniques = true;
        // comboStrafe: while a combo is live, movement orbits (WA/WD arcs)
        // instead of straight W, keeping the target locked in front.
        public boolean comboStrafe = true;
        // adaptiveStyle: per-opponent learned execution profiles (attack band,
        // strafe tempo, spacing, wtap/air-game multipliers), persisted to
        // config/pvpbot/adapt.json — the bot adapts timings & movement to each
        // opponent across sessions.
        public boolean adaptiveStyle = true;

        // ---- crit / midair moderation (user: not a crit spammer) ----
        public float critAttemptChance = 0.15f; // grounded melee jumps are crit attempts — only sometimes
        public int critCooldownTicks = 100;
        public float midAirChance = 0.10f;      // occasional rising air hit for pure knockback
        public int midAirCooldownTicks = 80;

        // ---- rewards (user: kills must pay 5-10x more) ----
        public float winReward = 45f;   // 7.5x the old 6f — a kill is THE goal
        public float lossReward = -10f;

        // ---- learning (imitation + RL) ----
        public boolean imitationEnabled = true;  // DQfD: expert (human-train) demos stay in a dedicated buffer
        public float imitationRatio = 0.35f;     // v1.0.5: was 0.25 — movement demos weigh more
        public float imitationMargin = 0.8f;     // large-margin cloning strength on expert actions
        public int expertBufferCapacity = 60000;
        public int curriculumEpisodes = 20;
        public float epsilonRapidStart = 0.45f;
        public float epsilonRapidEnd = 0.15f;
        public float epsilonStable = 0.06f;
        public float lrRapid = 0.002f;
        public float lrStable = 0.0005f;
        public int trainBatch = 16;              // on-device batch (phone-friendly)
        public int trainEveryTicks = 4;          // on-device training cadence
        public int replayCapacity = 300000;
        public Float epsilonOverride = null;     // manual /pvpbot explore

        // ---- v2.0 PHASE 2-b: PURE MODE (the four-head brain is the only authority)
        // When ON, the v2 PolicyNet decides movement, sprint, jump, sneak, aim AND
        // clicks. The TriggerBot, every hand-tuned technique chance, the DecisionMind
        // and the aim-assist percentages are all BYPASSED. The v1.0.12 physics laws
        // REMAIN: the hard [min,max] band gate, the 6.7 CPS click governor, the
        // on-target check, the grounded/range jump sanity. pureAimAssist is NOT the
        // old aim-assist — it is the v2 bootstrapping blend toward the proven tracker
        // while the aim head matures (set 0.0 for 100% learned aim).
        public boolean pureMode = false;
        public float pureAimAssist = 0.55f;      // legacy blend knob (used only if the aim head is promoted)
        public float pureAimMaxDeg = 40f;        // aim head output scale (deg/tick)
        public boolean v2Imitation = true;       // feed human-train demos to the four-head brain too

        // ---- v2.1.0 TRAINING WHEELS (user: "give the Pure Model a bit of training
        // wheels — advanced DATA, not execution assists"). The first model learned
        // better because its aim NEVER depended on an immature head: the proven
        // tracker owned the camera. v2.1 restores that law: the PolicyNet aim head
        // keeps TRAINING, but its output reaches the mouse ONLY after it earns it
        // (minimum training steps AND a proven-low supervised loss AND an explicit
        // opt-in). Same philosophy the v1.0.12 ATTACK ban used for clicks.
        public boolean pureAimHead = false;      // aim-head execution OFF — tracker owns pure aim (like the first model)
        public int pureAimHeadMinSteps = 200000; // promotion bar 1: minimum training steps
        public float pureAimHeadMaxLoss = 0.005f;// promotion bar 2: supervised aim loss must be under this
        public float pureAimHeadWeight = 0.30f;  // promotion bar 3 (opt-in): max share of the head in the blend
        public boolean pureSneak = false;        // sneak muscle HARD-OFF until the head proves itself ("always holds shift" killer)
        public int pureSneakMinSteps = 100000;   // sneak promotion bar: minimum training steps
        public boolean pureShaping = true;       // face-target + range reward shaping (a LEARNING signal, not an execution assist)

        // ---- v2.3 BRAIN UPGRADE ----
        // Click range (feet-to-feet blocks). The live vanilla raycast at click
        // time already enforces the real 3.0 reach (eye -> hitbox), so the old
        // 2.95 feet-distance cap only threw away ~0.3 blocks of legal reach
        // (opponents out-ranged the bot). Shared by v1 and pure mode.
        public float clickMaxDist = 3.2f;
        // On-device learning rates for the v2 brain. It now ships PRETRAINED
        // (hours of simulator training) — the v1 rates (2e-3) would wash that
        // out within minutes; these keep refining it instead.
        public float v2LrRapid = 1.0e-4f;
        public float v2LrStable = 5.0e-5f;
        // v2.3.2 classic-stack counters (simulator-tested)
        public boolean comboBreaker = true;   // taken 2+ hits in a row -> sprint-strafe off their aim line
        public boolean critDenial = true;     // they jump in crit range -> hold reach edge, no crit trading

        // ---- v2.1.0 VISION RECORDER (player-image dataset builder) ----
        // Captures auto-labeled crops of every visible player straight from the
        // framebuffer (plus matched negatives) to game-dir/pvpbot-vision/ —
        // the training set for the future pixel-sight recognizer.
        public boolean visionRecord = false;
        public int visionEveryMs = 400;          // capture interval
        public int visionCrop = 96;              // crop size in framebuffer px
        public int visionMaxFiles = 6000;        // disk safety cap, recorder auto-pauses at the limit
        public int visionMaxDist = 48;           // players further than this are not labeled

        // ---- v2.0 PHASE 3-a: N-STEP + PRIORITIZED REPLAY + ENTROPY DECAY
        public int nStep = 3;                    // n-step return horizon (both brains)
        public float entropyTau = 200000f;       // exploration-surplus decays exp(-steps/tau)

        // ---- v2.0 PHASE 3-b: TRAINING HARNESS (checkpoints + eval + league)
        public int checkpointEveryEpisodes = 25; // auto-snapshot during training sessions
        public int evalEpisodes = 10;            // default /pvpbot eval length

        // ---- combat ----
        public float engageRadius = 24f;
        public float disengageTicksNoCombat = 200; // 10s
        public int episodeHardCapTicks = 12000;    // 10 min

        // ---- round result detection (user: read the BIG on-screen text) ----
        public boolean roundTextDetection = true;
        public int roundDebounceMs = 5000; // 1 result per 5s — duplicates inside the window are cancelled

        // ---- hud ----
        public boolean hudEnabled = true;
        public boolean tradeLogEnabled = true;
        public boolean trainingStatsEnabled = true;
        public boolean comboMeterEnabled = true;
        public boolean keystrokesEnabled = true;  // v1.0.6: WASD + LMB + Space + Shift widget
        public boolean thoughtHudEnabled = true;  // v1.0.6: show the mind's deliberation line

        // ---- v1.0.11 HUD CUSTOMIZER (user: "add a HUD customizer to edit the
        //      Position and Size of each huds") ----
        // Every HUD element stores an ANCHOR corner/line, pixel OFFSETS from it
        // and a SCALE. Edited live by the drag-and-drop HUD editor
        // (/pvpbot hud) and persisted in config.json like everything else.
        // Anchors: 0 = top-left, 1 = top-right, 2 = bottom-left, 3 = bottom-right,
        //          4 = top-center, 5 = bottom-center, 6 = crosshair (screen center).
        public static final class HudElement {
                public int anchor = 4;
                public int ox = 0, oy = 0;
                public float scale = 1f;
        }

        public final java.util.Map<String, HudElement> hudLayout = new java.util.HashMap<>();

        /** Element record (creates the sensible default on first touch). */
        public HudElement el(String id) {
                HudElement e = hudLayout.get(id);
                if (e == null) {
                        e = defaultHud(id);
                        hudLayout.put(id, e);
                }
                return e;
        }

        private static HudElement defaultHud(String id) {
                HudElement e = new HudElement();
                switch (id) {
                        case "tracker" -> { e.anchor = 4; e.oy = 8; }        // top-center locator bar
                        case "cooldown" -> { e.anchor = 6; e.oy = 14; }      // under the crosshair
                        case "hp" -> { e.anchor = 2; e.ox = 8; e.oy = -46; } // bottom-left above hotbar
                        case "flash" -> { e.anchor = 6; e.oy = -30; }        // above the crosshair
                        case "combo" -> { e.anchor = 5; e.oy = -68; }        // bottom-center
                        case "stats" -> { e.anchor = 1; e.ox = -152; e.oy = 6; } // top-right panel
                        case "log" -> { e.anchor = 1; e.ox = -120; e.oy = 90; }  // right side
                        case "keys" -> { e.anchor = 2; e.ox = 8; e.oy = -144; }  // bottom-left widget
                }
                return e;
        }

        /** Reset one element (or all with id == null) to its default layout. */
        public void resetHud(String id) {
                if (id == null) {
                        hudLayout.clear();
                } else {
                        hudLayout.remove(id);
                }
        }

        // ---- v2.0 PHASE 1 — threaded 120Hz aim (user: "60 or 120Hz smooth aim") ----
        // The aim loop runs on a dedicated thread at a fixed wall-clock rate,
        // fully decoupled from both the 20-tick simulation AND the render FPS
        // (33-62 on the user's laptop). Fractional double-precision deltas are
        // accumulated between frames and injected once per render frame.
        public boolean threadedAim = true;
        public int aimThreadHz = 120;             // 60 or 120
        public boolean aimThreadInterpolate = true; // dead-reckon the target between ticks

        // ---- v2.0 PHASE 1 — practice-bot targeting (TheoBald / HerosBot dummies) ----
        // When true the target selector also considers non-player LivingEntities
        // (practice-bot mobs/dummies). Players are ALWAYS eligible; this only
        // widens the pool for training against mod bots. Default off so the bot
        // never randomly attacks cows on a server.
        public boolean targetNonPlayers = false;

        // ---- v2.0 PHASE 1 — Focus (Eco) Mode ----
        // Battery saver for long unattended training runs: black overlay,
        // particles minimal, render distance 2, entity scaling 50%, fps cap,
        // master volume 0. Every touched setting is snapshotted and restored
        // exactly on toggle-off (or on next launch after a crash).
        public boolean focusMute = true;
        public int focusRenderDistance = 2;
        public int focusFrameCap = 15;

        // ---- memory ----
        public int memorySeconds = 240;         // v1.0.9: 4 minutes (user spec)

        // ---- v1.0.9 intercept chase (user: predict the fleeing path, sprint-jump
        //      there; sprint-jump chase ONLY when the opponent is 10+ blocks out) ----
        public boolean chasePredictEnabled = true;  // predict their path (velocity EMA) and cut the corner
        public float chaseSprintJumpMinDist = 10f;  // bunny-hop chase distance floor (blocks)
        public float chasePredictMinDist = 4.5f;    // predicted-direction chase kicks in here when they flee

        // ---- v1.0.9 head-priority aim (user: stop aiming at the legs/feet) ----
        public boolean aimHeadPriority = true;      // aim wander locked to the head zone (0.75-0.93 hitbox height)

        // ---- v1.0.10 aim ZONE selector (user: "add config where I could select
        // Prioritize Aim, Like I want it to Aim Straight like in the neck or
        // chest") ---- 0 = Head (wandering wander across the head, current
        // behavior), 1 = Eyes (straight lock on the eye line), 2 = Neck
        // (straight, ~1.44 blocks up — the classic PvP aim line), 3 = Chest
        // (straight, ~1.13 blocks up). Straight zones hold a CONSTANT height —
        // no vertical wander at all. The hitbox clamp (never above the head /
        // below the shins) still applies to every zone.
        public int aimZone = 0;

        private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

        public static Path dir() {
                return FabricLoader.getInstance().getConfigDir().resolve("pvpbot");
        }

        public static BotConfig load() {
                Path p = dir().resolve("config.json");
                try {
                        if (Files.exists(p)) {
                                BotConfig c = GSON.fromJson(Files.readString(p), BotConfig.class);
                                if (c != null) {
                                        migrate(c);
                                        return c;
                                }
                        }
                } catch (Exception ignored) {
                }
                BotConfig c = new BotConfig();
                c.save();
                return c;
        }

        /** One-time upgrade of configs saved by older versions (they keep their
         *  old on-disk values otherwise). v9 = v2.0 phase 1: threaded aim,
         *  practice-bot targeting, focus mode. */
        private static void migrate(BotConfig c) {
                if (c.configVersion >= 14) return;
                if (c.configVersion < 14) {
                        c.comboBreaker = true;
                        c.critDenial = true;
                        if (c.clickMaxDist > 3.2f) c.clickMaxDist = 3.2f;
                }
                // v12 -> v13 (v2.3): reach parity + pretrained-brain learning rates
                if (c.configVersion < 13) {
                        c.clickMaxDist = 3.2f;
                        c.v2LrRapid = 1.0e-4f;
                        c.v2LrStable = 5.0e-5f;
                }
                // v11 -> v12 (v2.2.1): sprint-gate patience + aggression floor
                // + tightened retreat limit. Gson leaves fields missing from an
                // old JSON at their JAVA defaults — new knobs MUST get their
                // intended default here or saved configs silently run broken.
                if (c.configVersion < 12) {
                        c.sprintGatePatiencePure = 10;
                        c.pureCloseLimit = 20;
                        // user asked AGAIN to calm the backing-off habit
                        if (c.pureRetreatLimit <= 0 || c.pureRetreatLimit > 4) c.pureRetreatLimit = 4;
                }
                // v10 -> v11 (v2.2.0): aim calm + pure attack law + retreat
                // governor + IL autoload. Gson leaves fields missing from an
                // old JSON at their JAVA defaults (0.0 / false) — every new
                // knob MUST get its intended default here or saved configs
                // silently run with broken values.
                if (c.configVersion < 11) {
                        if (c.antiWobble <= 0f) c.antiWobble = 0.65f;
                        c.pureImmediateAttack = true;
                        if (c.pureRetreatLimit <= 0) c.pureRetreatLimit = 6;
                        c.ilAutoLoad = true;
                }
                if (c.configVersion < 9) {
                        c.threadedAim = true;
                        c.aimThreadHz = 120;
                        c.aimThreadInterpolate = true;
                        c.targetNonPlayers = false;
                        c.focusMute = true;
                        c.focusRenderDistance = 2;
                        c.focusFrameCap = 15;
                }
                if (c.configVersion < 8) {
                        // v7 -> v8
                        c.memorySeconds = 240;
                        c.chasePredictEnabled = true;
                        c.chaseSprintJumpMinDist = 10f;
                        c.chasePredictMinDist = 4.5f;
                        c.aimHeadPriority = true;
                }
                if (c.configVersion < 7) {
                        // v6 -> v7: new v1.0.8 switches default ON
                        c.modelTechniques = true;
                        c.comboStrafe = true;
                        c.adaptiveStyle = true;
                }
                if (c.configVersion < 6) {
                        // v5 -> v6
                        c.aimSmoothMin = 0.62f;
                        c.aimSmoothMax = 0.82f;
                        c.aimMaxTurnDeg = 55f;
                        c.tooCloseDist = 1.35f;
                        c.backoffReleaseDist = 2.1f;
                }
                // v9 -> v10 (v2.1.0 training wheels): the aim head and the sneak
                // muscle BOTH drop back to learner benches — the user's report was
                // that an immature head still drove the camera (offset squares) and
                // latched shift. Their weights keep training; execution is earned.
                if (c.configVersion < 10) {
                        c.pureAimHead = false;
                        c.pureSneak = false;
                        c.pureShaping = true;
                        c.visionRecord = false;
                }
                c.configVersion = 14;
                c.save();
        }

        public void save() {
                try {
                        Files.createDirectories(dir());
                        Files.writeString(dir().resolve("config.json"), GSON.toJson(this));
                } catch (IOException ignored) {
                }
        }
}
