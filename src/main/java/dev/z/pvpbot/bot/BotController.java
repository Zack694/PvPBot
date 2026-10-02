package dev.z.pvpbot.bot;

import com.google.gson.JsonObject;
import dev.z.pvpbot.BotConfig;
import dev.z.pvpbot.PvpBot;
import dev.z.pvpbot.ml.Dqn;
import dev.z.pvpbot.ml.League;
import dev.z.pvpbot.ml.ModelStore;
import dev.z.pvpbot.ml.NeuralNet;
import dev.z.pvpbot.ml.PolicyNet;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.text.Text;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.entity.projectile.ProjectileUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Random;

/**
 * Orchestrates the self-learning loop:
 *
 *   perceive -> decide (DQN, epsilon-greedy) -> humanize -> actuate
 *      (virtual WASD / left click / mouse) -> observe outcome -> reward
 *
 * Every decision the bot makes comes from the policy network's weights.
 * The curriculum keeps learning rapid for the first N episodes, then
 * stabilizes into exploitation.
 */
public final class BotController {

        public enum State { IDLE, ENGAGED, PAUSED, DEAD_WAIT }

        private final BotConfig cfg;
        private final MinecraftClient mc;

        public final TargetSelector selector;
        public final HitWatcher hits;
        public final OpponentMemory memory;
        public final TerrainSense terrain;
        public final Perception perception;
        public final Humanizer humanizer;
        public final Actuator actuator;
        public final AimController aim;
        public final Dqn dqn;

        private State state = State.IDLE;
        private boolean started = false;
        private boolean userPaused = false;      // /pvpbot pause — survives chat screens
        private boolean trainingSession = false; // /pvpbot train — auto re-engage loop
        private boolean humanTraining = false;   // /pvpbot human-train — watch-only imitation learning
        private boolean controlling = false;     // true only while the bot owns the keys/camera

        // training-session stats (reset on /pvpbot train)
        public int sessionEpisodes, sessionWins, sessionLosses, sessionDraws;
        public long sessionStartTick;
        private LivingEntity prevTarget; // for opponent-death WIN detection

        // v2.0 PHASE 1 — sight vector (84-dim observation groundwork) + 120Hz aim thread
        public final Sight sight = new Sight();
        public final AimThread aimThread;

        // episode
        private boolean inEpisode = false;
        private long episodeStartTick;
        private float[] lastState;
        private int lastAction = -1;
        private long lastDecisionTick;
        private long lastTrainTick;
        private float rewardEma = 0f;
        private float matchTime;

        // curriculum + stats
        public int episodesDone = 0;
        public int wins, losses, draws;
        private long tickCounter = 0;
        public long lastAttackAttemptTick = -1000; // mirrored for Perception
        public String lastAnnouncement = "";
        private boolean lastTickCombat = false;

        // reflex / pacing state
        private long lastReflexJumpTick = -1000;  // jump-reset reflex cooldown
        private long lastSwingAttemptTick = -1000; // our own swing clock (server autodetect)
        private int fastRefillStreak = 0;
        private boolean noCooldownServer = false;  // classic 1.8-style pacing detected
        private boolean announcedNoCooldown = false; // announce the latch once per episode
        private volatile float lastTrainLoss = Float.NaN; // set by the background trainer
        private float lossEma = 0f;                // v1.0.11: responsive EMA of the REAL train loss
        // v1.0.4 — tactics + attack band + round text + frame aim
        public final CombatTactics tactics;
        // v1.0.6 — decision thinking (the deliberate "inner voice")
        public final DecisionMind mind;
        private long prevMyHitTick = -1000;        // to detect "we just landed a hit" (WTap trigger)
        private long prevTakenTick = -1000;        // to detect "we just got hit" (jump-reset trigger)
        private float nextBandThreshold = -1f;     // the randomized 82-96% cooldown threshold for the NEXT swing
        private long lastAnySwingTick = -1000;     // v1.0.10: absolute min-gap governor over EVERY swing
        private long lastClickTick = -1000;        // v1.0.11: absolute min-gap governor over EVERY CLICK

        /**
         * v1.0.10 PROGRESSIVE MISS COOLDOWN — a miss swing (crosshair slipped
         * off the hitbox between the last render and the click) never drains
         * the meter, so the band stays satisfied and the old flat 5-tick
         * cooldown let the bot re-swing every 5 ticks (4 CPS of swing
         * animation with no hits — the "it still spams abit in round 2"
         * look: round 2 STARTS with a chase, and the chase + combo strafe
         * keep the crosshair edge-flickering). The cooldown now escalates
         * 5 -> 9 -> 13 -> 17 ticks per consecutive miss, and the click gate
         * additionally demands a LIVE vanilla-identical raycast, so a miss
         * train is structurally dead.
         */
        private int missCooldownTicks() {
                return 5 + Math.min(3, missSwingStreak) * 4;
        }
        private long lastMissSwingTick = -1000;    // v1.0.9 anti machine-gun: last swing that did NOT consume the meter
        private int missSwingStreak = 0;
        private long lastResultMs = -100000L;      // last settled round result (5s debounce window)
        private long lastFrameNanos = 0L;          // frame-rate aim clock
        // v1.0.9 PERMA SPAM FIX — the TEMPO_SPIKE intent used to multiply the
        // band threshold by 0.88 EVERY TICK it was held; a spike held for 20+
        // ticks decayed the threshold toward 0 and the bot machine-gunned again
        // (worse the longer the mind had trained, which is why it “came back
        // after a few games”). Now: ONE reduction per swing, and a HARD FLOOR
        // no code path — not the mind, not the adaptive engine, not a lag
        // spike — can ever undercut.
        private boolean tempoAppliedThisSwing = false;

        // v1.0.7 sprint-hit gate health (bypass latch if sprint can never engage)
        private int noSprintTicks = 0;
        private boolean sprintGateBypass = false;

        // v1.0.8 — per-opponent adaptation engine (movement + timings profiles)
        public final AdaptiveEngine adapt = AdaptiveEngine.load();

        // crit moderation + honest win verification
        private long lastCritJumpTick = -1000;   // last policy-initiated melee crit jump
        private final Random rng = new Random();
        private boolean diedThisEpisode = false; // we died during this episode — a later
                                                 // opponent despawn can NEVER turn this into a WIN

        // ---- v2.0 PHASE 2-b: the four-head brain (pure mode) ------------------
        public final PolicyNet policy;
        private float[] lastV2State;                 // open v2 transition
        private int lastV2Move;
        private boolean lastV2Sprint, lastV2Jump, lastV2Sneak;
        private float lastV2AimYawN, lastV2AimPitN, lastV2ClickN; // supervised labels of the open transition
        private float pureClickDesire;               // click head's current desire (applyAction reads it)
        private boolean pureSneakIntent;             // sneak flag (ActionSpace has no sneak bit)
        private float lastV2ClickDue;                // v2.0.2: should-have-clicked oracle (immature click-head fallback)
        private int pureSneakHoldTicks;              // v2.0.2 sneak governor: consecutive sneak ticks
        private long pureSneakCooldownUntil;         // v2.0.2 sneak governor: forced-release cooldown
        private long lastPureJumpTick = -1000;       // pure-mode jump rate limiter
        private int pureBackStreak = 0;              // v2.2.0 retreat governor: consecutive backward ticks
        private int pureCloseStreak = 0;             // v2.2.1 aggression floor: consecutive too-far ticks
        private float pureAimYawBudget, pureAimPitBudget; // per-tick blended aim, distributed by the aim thread
        private final float[] pureAimFrame = new float[2];
        private volatile float lastV2TrainLoss = Float.NaN;
        private float lastPureFaceErr = -1f;         // v2.1.0: face-error shaping (previous tick's angular error)
        private boolean aimHeadPromotedAnnounced;    // v2.1.0: one-shot "aim head promoted" notice
        // human-train v2 label capture (labels lag one tick behind their state)
        private float[] lastV2Expert;
        private int lastV2LblMove = -1;
        private boolean lastV2LblSprint, lastV2LblJump, lastV2LblSneak;
        private float lastV2LblAimYaw, lastV2LblAimPit, lastV2LblClick;
        private float lastV2ExpertReward;
        private float prevHumanYaw, prevHumanPitch;

        // ---- v2.0 PHASE 3-b: eval scorecard (exploration OFF, learning OFF) ---
        private boolean evalMode = false;
        private int evalRemaining = 0;
        private final int[] evalWld = new int[3];
        private float evalDmgDealt, evalDmgTaken;
        private int evalHits, evalWhiffs;

        /**
         * PHASE 3-b EVAL: run N training-session episodes with exploration
         * and learning DISABLED — pure exploitation. Every episode settles
         * into the scorecard; at the end the evaluated brain is snapshotted
         * and ranked in the checkpoint league (models/league.json).
         */
        public void startEval(int episodes) {
                startTraining();
                evalMode = true;
                evalRemaining = Math.max(1, episodes);
                evalWld[0] = evalWld[1] = evalWld[2] = 0;
                evalDmgDealt = 0f;
                evalDmgTaken = 0f;
                evalHits = 0;
                evalWhiffs = 0;
                announce("EVAL started — " + evalRemaining
                                + " episodes, exploration OFF, no learning. Scorecard + league entry at the end.");
        }

        private void finishEval() {
                evalMode = false;
                int ep = evalWld[0] + evalWld[1] + evalWld[2];
                int w = evalWld[0], l = evalWld[1], d = evalWld[2];
                float dd = evalDmgDealt, dt = evalDmgTaken;
                int hitsN = evalHits, whiffsN = evalWhiffs;
                String name = "eval-ep" + episodesDone + "-"
                                + java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HHmmss"));
                final PvpBot botRef = PvpBot.get();
                final boolean v2active = cfg.pureMode;
                final String kind = v2active ? "v2" : "v1";
                PvpBot.worker().execute(() -> {
                        try {
                                String file = v2active ? ModelStore.saveSnapshotV2(name, botRef)
                                                : ModelStore.saveSnapshot(name, botRef);
                                League.record(name, file, kind, w, l, d, dd, dt, ep);
                        } catch (Exception e) {
                                dev.z.pvpbot.PvpBot.LOGGER.warn("[pvpbot] eval snapshot failed: {}", e.toString());
                        }
                });
                stop();
                float acc = (hitsN + whiffsN) > 0 ? 100f * hitsN / (hitsN + whiffsN) : 0f;
                announce(String.format(
                                "EVAL DONE — %d ep  W/L/D %d/%d/%d | dmg dealt %.1f taken %.1f | hits %d whiffs %d (%.0f%% on-hit). Snapshot '%s' saved + ranked.",
                                ep, w, l, d, dd, dt, hitsN, whiffsN, acc, name));
        }

        public boolean isEvalRunning() {
                return evalMode;
        }

        public BotController(BotConfig cfg, MinecraftClient mc, Dqn dqn, NeuralNet aimNet, PolicyNet policy) {
                this.cfg = cfg;
                this.mc = mc;
                this.dqn = dqn;
                this.policy = policy;
                this.selector = new TargetSelector(cfg);
                this.memory = new OpponentMemory();
                this.hits = new HitWatcher(memory, cfg);
                this.terrain = new TerrainSense();
                this.perception = new Perception();
                this.humanizer = new Humanizer(cfg);
                this.actuator = new Actuator(mc);
                this.aim = new AimController(aimNet);
                this.tactics = new CombatTactics(cfg);
                this.tactics.adapt = adapt;
                this.mind = dev.z.pvpbot.bot.DecisionMind.load();
                this.aimThread = new AimThread(this, mc);
                if (cfg.threadedAim) {
                        this.aimThread.ensureStarted();
                }
        }

        // ------------------------------------------------------------ lifecycle

        public void start() {
                started = true;
                userPaused = false;
                trainingSession = false;
                humanTraining = false;
                hits.attributeUnclaimedHits = false;
                state = State.ENGAGED;
                announce("PvPBot ENGAGED — target locked when a player is near.");
        }

        /** Continuous training session: after every episode (death, win, timeout)
         *  the bot automatically re-engages for the next round until paused/stopped. */
        public void startTraining() {
                started = true;
                userPaused = false;
                trainingSession = true;
                humanTraining = false;
                hits.attributeUnclaimedHits = false;
                state = State.ENGAGED;
                sessionEpisodes = 0;
                sessionWins = 0;
                sessionLosses = 0;
                sessionDraws = 0;
                sessionStartTick = tickCounter;
                lastResultMs = -100000L;
                tactics.resetFight();
                announce("TRAINING SESSION started — auto re-engage after every round. "
                                + "Pause with /pvpbot pause, end with /pvpbot stop.");
        }

        /** Imitation learning: the USER fights, the bot only watches — recording
         *  (state, your action) pairs into the same replay buffer and training the
         *  policy + aim net + opponent memory from your play. Zero inputs touched. */
        public void startHumanTrain() {
                started = true;
                userPaused = false;
                trainingSession = false;
                humanTraining = true;
                controlling = false;
                hits.attributeUnclaimedHits = true;
                state = State.ENGAGED;
                sessionEpisodes = 0;
                sessionWins = 0;
                sessionLosses = 0;
                sessionDraws = 0;
                sessionStartTick = tickCounter;
                actuator.releaseAll();
                announce("HUMAN-TRAIN: fight normally — I only watch and learn from you. "
                                + "Your movement, clicks, jumps and sprint are recorded as expert actions.");
        }

        /** Freeze mid-episode: keys released ONCE, duel + memory stay intact, resume continues. */
        public void pause() {
                if (!started || userPaused) return;
                userPaused = true;
                controlling = false;
                actuator.releaseAll();
                zeroPureAimBudget(); // v2.0.1
                humanizer.clearQueue();
                announce("PAUSED — keys released, your own movement works normally now. /pvpbot resume to continue.");
        }

        public void resume() {
                if (!userPaused) return;
                userPaused = false;
                announce("Resumed — back in the fight.");
        }

        public void stop() {
                started = false;
                userPaused = false;
                boolean wasTraining = trainingSession;
                boolean wasHuman = humanTraining;
                trainingSession = false;
                humanTraining = false;
                hits.attributeUnclaimedHits = false;
                controlling = false;
                state = State.IDLE;
                actuator.releaseAll();
                zeroPureAimBudget(); // v2.0.1
                humanizer.clearQueue();
                finishEpisode("ABORTED");
                if (wasTraining && sessionEpisodes > 0) {
                        announce(String.format(
                                        "TRAINING SESSION ended — %d episodes (W/L/D %d/%d/%d) in %s. Model saved automatically.",
                                        sessionEpisodes, sessionWins, sessionLosses, sessionDraws, fmtDuration(tickCounter - sessionStartTick)));
                } else if (wasHuman && sessionEpisodes > 0) {
                        announce(String.format(
                                        "HUMAN-TRAIN ended — learned from %d episodes of your play (W/L/D %d/%d/%d). Model saved.",
                                        sessionEpisodes, sessionWins, sessionLosses, sessionDraws));
                } else {
                        announce("PvPBot disengaged. All virtual keys released.");
                }
        }

        public void toggle() {
                if (userPaused) {
                        resume();
                } else if (started) {
                        stop();
                } else {
                        start();
                }
        }

        public boolean isStarted() {
                return started;
        }

        public boolean isPaused() {
                return userPaused;
        }

        public boolean isTrainingSession() {
                return trainingSession;
        }

        public boolean isHumanTraining() {
                return humanTraining;
        }

        public State state() {
                return state;
        }

        private String fmtDuration(long ticks) {
                long s = ticks / 20;
                if (s < 60) return s + "s";
                return String.format("%dm %ds", s / 60, s % 60);
        }

        // ------------------------------------------------------------ main tick

        public void tick(MinecraftClient mc) {
                // v1.0.11: fold the async trainer's latest loss into a RESPONSIVE
                // loss EMA (alpha 0.02, ~1s time constant) — the old code folded it
                // into the reward EMA at alpha 0.001 (50s time constant), so one
                // loss spike haunted the HUD for a minute of gameplay.
                float tl = lastTrainLoss;
                if (!Float.isNaN(tl)) {
                        lossEma += 0.02f * (tl - lossEma);
                }
                // user pause: full freeze — keys were already released ONCE by pause();
                // do NOT touch anything here so the user's own movement works while paused
                if (userPaused) {
                        return;
                }
                tickCounter++;
                if (!started) return;
                ClientPlayerEntity self = mc.player;
                if (self == null || mc.world == null) {
                        if (state == State.ENGAGED) {
                                actuator.releaseAll();
                                controlling = false;
                                state = State.IDLE;
                        }
                        return;
                }

                // death handling FIRST — the death screen must not be mistaken for a
                // user-opened screen, otherwise the LOSS episode is never settled.
                // Routed through the SAME 5s debounce as on-screen text results so a
                // 'ROUND LOST' title + our death settle can never double-count.
                if (self.isDead() || self.getHealth() <= 0f) {
                        if (state != State.DEAD_WAIT) {
                                diedThisEpisode = true;
                                if (inEpisode) settleRound("LOSS");
                                actuator.releaseAll();
                                controlling = false;
                                state = State.DEAD_WAIT;
                                announce(trainingSession
                                                ? "You died — episode settled as LOSS. Respawn for the next round (auto)."
                                                : "Episode ended: DEATH. Brain updated. /pvpbot start to re-engage.");
                        }
                        return;
                }
                if (state == State.DEAD_WAIT) {
                        // player respawned
                        if (trainingSession || humanTraining) {
                                // v1.0.10: round 2 starts CLEAN even before the 5s settle
                                // debounce reaches beginEpisode — pacing state (classic
                                // latch, miss streaks, band, governor) resets at respawn
                                resetPacingState();
                                state = State.ENGAGED;
                                if (trainingSession) {
                                        announce("Next round — re-engaged. Close the distance!");
                                }
                        } else {
                                return; // wait for manual re-engage after death
                        }
                }

                // safety: user opened a screen (chat/inventory) → pause and release
                if (mc.currentScreen != null) {
                        if (state == State.ENGAGED) {
                                actuator.releaseAll();
                                controlling = false;
                                state = State.PAUSED;
                        }
                        return;
                } else if (state == State.PAUSED) {
                        state = State.ENGAGED;
                }

                boolean hasTarget = selector.tick(mc, lastTickCombat);
                lastTickCombat = false;
                LivingEntity target = selector.target();

                // opponent died → settle the episode as a WIN — but only a
                // VERIFIED win (alive, never died this episode, recent damage).
                // Same 5s debounce as text results protects against double-settles.
                if (target == null && prevTarget != null && !prevTarget.isAlive() && inEpisode) {
                        boolean verified = !diedThisEpisode
                                        && hits.dmgDealt > 0f
                                        && tickCounter - hits.lastMyHitTick <= 60;
                        settleRound(verified ? "WIN" : "DRAW");
                        if (verified && trainingSession) {
                                announce("Opponent defeated — waiting for them to respawn…");
                        }
                        prevTarget = null;
                }

                if (target != null) {
                        prevTarget = target;
                        // memory bookkeeping
                        if (!memory.matches(target.getUuid())) {
                                memory.reset(target.getUuid(), target.getName().getString());
                                // v1.0.8: swap the adaptation profile to this opponent
                                adapt.switchOpponent(target.getUuid(), target.getName().getString());
                                if (inEpisode) finishEpisode("DRAW");
                        }
                        terrain.sense(mc.world, self, tickCounter);
                        hits.tick(self, target, tickCounter);
                        // v2.0 PHASE 1: reset-tolerant rounds — practice bots that
                        // REFILL health instead of dying still terminate the round
                        if (inEpisode && tickCounter - hits.lastMyHitTick <= 100
                                        && hits.dmgDealt > 0f && hits.consumeResetDetected()) {
                                settleRound(!diedThisEpisode ? "WIN" : "DRAW");
                                announce("Practice bot reset detected — round settled (rewards paid). Training continues.");
                        }
                        // v2.0 PHASE 1: feed the sight vector's opponent history buffers
                        // v2.1.0: also ticks the advanced rhythm/aim-skill windows
                        sight.noteTarget(self, target, tickCounter);
                        // v1.0.8: feed the adaptation engine (learns movement + timing
                        // profiles per opponent from the damage balance + their style)
                        if (cfg.adaptiveStyle) {
                                adapt.tick(hits, memory, true, tickCounter);
                        }
                        // v1.0.5 movement training: per-tick footwork reward shaping
                        // (shared by bot-controlled and human-train episodes so the
                        // expert demos carry the same signal)
                        movementShaping(self, target);
                        // any observed trade (our hit, their hit, their swing) counts
                        // as combat — keeps the target locked during practice-bot
                        // sessions even when nobody lands damage for a while
                        if (tickCounter - hits.lastMyHitTick <= 2
                                        || tickCounter - hits.lastTakenHitTick <= 2
                                        || tickCounter - hits.theirLastAttackTick <= 2) {
                                lastTickCombat = true;
                        }

                        if (humanTraining) {
                                // ---- imitation: watch the user fight, learn from their choices ----
                                observeStep(self, target);
                        } else {
                                // ---- takeover: the bot fights ----
                                controlling = true;
                                if (cfg.threadedAim) {
                                        // v2.0: the 120Hz aim thread owns the camera; the tick
                                        // loop only guarantees mouse deltas get drained even
                                        // when rendering stalls (frameTick drains them too)
                                } else if (!cfg.frameAim) {
                                        // legacy 20Hz aim (frame aim runs in frameTick())
                                        if (cfg.pureMode) {
                                                // v2.0 PHASE 2-b: the blended+shaped head/tracker budget IS the aim
                                                float[] bud = drainAllPureAimBudget();
                                                actuator.queueLook(bud[0], bud[1]);
                                        } else {
                                                float[] aimDelta = aim.aimStep(self, target, memory, tickCounter);
                                                float[] shaped = humanizer.shapeAim(self, aimDelta[0], aimDelta[1]);
                                                actuator.queueLook(shaped[0], shaped[1]);
                                        }
                                } // else: the per-frame loop below owns the camera

                                // decisions EVERY tick (20Hz) — fine-grained tactics timing
                                decisionStep(self, target);
                                trainPulse();
                                aim.learnStep(self, target, tickCounter);
                                // v1.0.6: keep the mind's learning segments fed
                                mind.noteDamage(hits.dmgDealt, hits.dmgTaken);
                        }
                } else {
                        prevTarget = null;
                        if (controlling) {
                                // hand control back to the user ONCE — never fight their real keys
                                actuator.releaseAll();
                                controlling = false;
                        }
                        zeroPureAimBudget(); // v2.0.1: stale aim never yanks at a gone target
                        // while idle (no target) the user's own WASD/mouse are untouched:
                        // walk up to your training partner freely, the bot only watches
                        if (inEpisode && tickCounter - episodeStartTick > cfg.disengageTicksNoCombat * 3) {
                                finishEpisode("DRAW");
                        }
                }

                // v1.0.9: feed the memory the FULL movement picture — their motion
                // decomposed along / across the line to me (the old call passed a
                // hardcoded 0 toward-speed) plus their sneak state — so the
                // 4-minute profile and the jump/sneak predictions have real data
                if (target != null) {
                        double mdx = target.getX() - self.getX(), mdz = target.getZ() - self.getZ();
                        double mlen = Math.max(1e-4, Math.sqrt(mdx * mdx + mdz * mdz));
                        double nx = mdx / mlen, nz = mdz / mlen;
                        float tvx = (float) target.getVelocity().x, tvz = (float) target.getVelocity().z;
                        float towardMe = (float) (tvx * nx + tvz * nz);   // + = closing on me
                        float lateral = (float) (tvx * -nz + tvz * nx);   // circling component
                        memory.tick(self.distanceTo(target),
                                        (float) Math.sqrt(tvx * tvx + tvz * tvz),
                                        towardMe, lateral,
                                        target.isOnGround() ? 0 : (target.getVelocity().y < 0 ? -1 : 1),
                                        target.isOnGround(),
                                        tickCounter,
                                        target.isSneaking());
                        memory.noteVelocity(tvx, tvz, tickCounter);
                } else {
                        memory.tick(99, 0f, 0f, 0f, 0, true, tickCounter, false);
                }
                oppSpeedFeed(target);

                if (controlling && !cfg.frameAim) {
                        // apply pending mouse movement (fractional, real event path)
                        actuator.applyMouse(cfg.sensitivityGridSnap);
                }
                if (controlling) {
                        actuator.tickPost();
                }
                // poll the big on-screen text for round results (VICTORY / ROUND LOST …)
                if (cfg.roundTextDetection) {
                        RoundWatcher.poll(mc, this);
                }
                hits.trimLogToTick(tickCounter);
        }

        /**
         * Frame-rate aim loop — runs EVERY RENDER FRAME (60Hz+, dt-compensated),
         * not at 20Hz. Recomputes the error to the wander point with wall-clock
         * time and feeds the same humanizer shaping, so tracking is visibly
         * smoother at any fps. Game-tick learning is untouched.
         *
         * v2.0: with threadedAim the COMPUTE moved to the dedicated 120Hz
         * thread — this loop now only injects the accumulated fractional deltas
         * once per frame (the exact GLFW path a real mouse uses).
         */
        public void frameTick(MinecraftClient mcFrame) {
                if (!controlling || userPaused) return;
                if (cfg.threadedAim) {
                        actuator.applyMouse(cfg.sensitivityGridSnap);
                        return;
                }
                if (!cfg.frameAim) return;
                ClientPlayerEntity self = mcFrame.player;
                LivingEntity target = selector.target();
                if (self == null || mcFrame.world == null || target == null || self.isDead()) return;

                long now = System.nanoTime();
                float dtMs = lastFrameNanos == 0L ? 16.6f : (now - lastFrameNanos) / 1_000_000f;
                lastFrameNanos = now;
                dtMs = Math.max(1f, Math.min(100f, dtMs));

                // v2.0 PHASE 2-b: pure mode — the four-head brain's blended aim
                // budget is distributed across frames instead of the tracker+shaper
                float[] pure = takePureAimFrame(dtMs);
                if (pure != null) {
                        actuator.queueLook(pure[0], pure[1]);
                        actuator.applyMouse(cfg.sensitivityGridSnap);
                        return;
                }

                float timeSec = (now / 1_000_000_000f) % 200000f;
                float[] aimDelta = aim.aimStepTime(self, target, memory, timeSec);
                float[] shaped = humanizer.shapeAimFrame(aimDelta[0], aimDelta[1], dtMs);
                actuator.queueLook(shaped[0], shaped[1]);
                actuator.applyMouse(cfg.sensitivityGridSnap);
        }

        /**
         * Settle a round result (WIN / LOSS / DRAW) through ONE 5-second
         * debounce. Whatever fires first — the big on-screen text (VICTORY,
         * ROUND LOST …), the opponent's death, or our own death — wins;
         * every duplicate inside the window is cancelled.
         */
        public void settleRound(String result) {
                long now = System.currentTimeMillis();
                if (now - lastResultMs < cfg.roundDebounceMs) {
                        return; // debounce: 1 result per 5s, spam cancelled
                }
                lastResultMs = now;
                finishEpisode(result);
        }

        /** Chat/system message hook from the mod entrypoint (round keywords). */
        public void maybeChatResult(net.minecraft.text.Text message) {
                if (cfg.roundTextDetection && started && !userPaused) {
                        RoundWatcher.onChat(message, this);
                }
        }

        /** Shared DQN training pulse — runs on the BACKGROUND worker thread.
         *  v1.0.4 ran batch-16 gradient steps through the 480x480 brain on the
         *  game thread (15-40ms each, every 4 ticks): that was the visible
         *  "frame gen" stutter during train / human-train. The main thread
         *  only reads back the loss. */
        private void trainPulse() {
                if (evalMode) {
                        return; // PHASE 3-b: eval measures the CURRENT brain — no learning
                }
                if (tickCounter % 2 == 1 && tickCounter - lastTrainTick >= Math.max(1, cfg.trainEveryTicks)) {
                        float lr = currentLr();
                        float ratio = cfg.imitationEnabled && dqn.expertSize() >= 64 ? cfg.imitationRatio : 0f;
                        final int batch = cfg.trainBatch;
                        PvpBot.worker().execute(() -> {
                                float loss = dqn.trainStep(batch, lr, ratio);
                                if (!Float.isNaN(loss)) {
                                        lastTrainLoss = loss;
                                }
                                if (dqn.getTrainSteps() % 1000 == 0) {
                                        dqn.syncTarget();
                                }
                                // v2.0 PHASE 2-b: the four-head brain trains on the SAME
                                // cadence — TD on its own replay + margin-cloned demos on
                                // the expert ring (Phase 2-c), all four heads at once
                                float v2loss = policy.trainStep(batch, lr, ratio, cfg.imitationMargin);
                                if (!Float.isNaN(v2loss)) {
                                        lastV2TrainLoss = v2loss;
                                }
                                if (policy.getTrainSteps() % 1000 == 0) {
                                        policy.syncTarget();
                                }
                        });
                        lastTrainTick = tickCounter;
                }
        }

        /**
         * v2.0 PHASE 2-b — PURE MODE decision step. The four-head brain
         * (PolicyNet) picks movement, sprint, jump, sneak, aim AND clicks
         * every tick; the v1 chance/technique layers never run. The v1.0.12
         * physics laws still gate every click (hard [min,max] band, 6.7 CPS
         * governor, swing gaps, on-target, reach) inside applyAction.
         */
        private void pureDecisionStep(ClientPlayerEntity self, LivingEntity target) {
                if (!inEpisode) beginEpisode();
                float matchTicks = tickCounter - episodeStartTick;
                float[] state = perception.buildV3(self, target, hits, memory, terrain,
                                tickCounter, matchTicks, hits.whiffRate(), sight);

                // close the previous v2 transition (reward accrued since last tick)
                if (lastV2State != null) {
                        float r = hits.pendingReward;
                        policy.remember(lastV2State, lastV2Move, lastV2Sprint, lastV2Jump, lastV2Sneak,
                                        lastV2AimYawN, lastV2AimPitN, lastV2ClickN, r, state, false);
                        hits.pendingReward = 0f;
                        rewardEma += 0.02f * (r - rewardEma);
                }

                policy.aimMaxDeg = cfg.pureAimMaxDeg;
                PolicyNet.Decision d = policy.act(state, epsilon());

                // v2.1.0 PURE AIM LAW — "make it like the first model".
                //
                // The first model learned better because its aim NEVER depended on
                // an immature network: the proven tracker + humanizer owned the
                // camera from tick one. v2.0.2's loss-based trust converged too
                // fast — the regression fits its labels in minutes while the head's
                // CLOSED-LOOP output is still noise, and 38-45% of a +/-40 deg/tick
                // noise source swamps a tracker correction of a few degrees. That
                // was the user's "VERY OFFSET square" + "doesn't aim at the
                // target". The aim head now sits on the BENCH by default: it keeps
                // TRAINING on every tick (distillation labels below), and its
                // output reaches the mouse only after it EARNS execution:
                // /pvpbot v2 aimhead on AND >= pureAimHeadMinSteps training steps
                // AND aimLossEma <= pureAimHeadMaxLoss.
                float[] tracker = aim.aimStep(self, target, memory, tickCounter);
                float maxDeg = Math.max(1f, cfg.pureAimMaxDeg);
                float trYaw = MathHelper.clamp(tracker[0], -maxDeg, maxDeg);
                float trPit = MathHelper.clamp(tracker[1], -maxDeg, maxDeg);
                float rawYaw = trYaw;
                float rawPit = trPit;
                if (cfg.pureAimHead && aimHeadPromoted()) {
                        // earned execution: the head's share is still capped by
                        // pureAimHeadWeight and scales with its proven loss (trust),
                        // and its output is clamped to the tracker's own degree scale.
                        float trust = Float.isNaN(policy.aimLossEma) ? 0f
                                        : (float) Math.exp(-Math.max(0f, policy.aimLossEma) * 8f);
                        float w = MathHelper.clamp(cfg.pureAimHeadWeight * trust, 0f, 0.5f);
                        rawYaw = trYaw * (1f - w) + MathHelper.clamp(d.aimYawDeg, -maxDeg, maxDeg) * w;
                        rawPit = trPit * (1f - w) + MathHelper.clamp(d.aimPitDeg, -maxDeg, maxDeg) * w;
                        if (!aimHeadPromotedAnnounced) {
                                aimHeadPromotedAnnounced = true;
                                announce(String.format("v2.1 aim head PROMOTED to the blend (steps %d, aimLoss %.4f, share %.0f%%). "
                                                + "If the aim ever regresses: /pvpbot v2 aimhead off.",
                                                policy.getTrainSteps(), policy.aimLossEma, w * 100f));
                        }
                }
                float[] shaped = humanizer.shapeAim(self, rawYaw, rawPit);
                addPureAimBudget(shaped[0], shaped[1]);

                // v2.1.0 TRAINING-WHEELS REWARD SHAPING (a LEARNING signal, not an
                // execution assist — the user asked for data/labels, never aim
                // assist). "It doesn't know where the target is" was also a
                // LEARNING problem: nothing in the reward told the brain that
                // FACING the target is good. Now:
                //   - improving the angular error to the target's chest pays,
                //   - losing the target (error > 25 deg) costs,
                //   - holding the target centered pays a small bonus.
                // All deltas ride the normal pendingReward -> replay pipeline.
                if (cfg.pureShaping) {
                        float ferr = faceErrorDeg(self, target);
                        if (lastPureFaceErr >= 0f) {
                                float improve = lastPureFaceErr - ferr;
                                hits.pendingReward += MathHelper.clamp(improve * 0.02f, -0.04f, 0.04f);
                        }
                        if (ferr < 6f) hits.pendingReward += 0.004f;   // on-target: keep this
                        if (ferr > 25f) hits.pendingReward -= 0.006f;  // lost the target: pay
                        lastPureFaceErr = ferr;
                }

                // supervised labels for the transition that just opened:
                //  click  — did the physics want a click this tick?
                //  aim    — distillation toward the RATE-LIMITED tracker
                //           correction. The old labels divided the RAW tracker
                //           (full error closure) by the degree scale and
                //           saturated at +/-0.98 for any error >= ~39 deg —
                //           they trained a bang-bang imitator that oscillates
                //           on its own the moment it takes over.
                float charge = self.getAttackCooldownProgress(0.0f);
                boolean bandOk = charge >= cfg.attackCooldownMin
                                && (charge <= cfg.attackCooldownMax || charge >= 0.999f);
                boolean gapsOk = (tickCounter - lastAnySwingTick >= 2)
                                && (tickCounter - lastClickTick >= 3)
                                && (tickCounter - lastMissSwingTick >= missCooldownTicks());
                boolean clickDue = bandOk && gapsOk && vanillaOnTarget(mc, target)
                                && self.distanceTo(target) <= 2.95;
                lastV2ClickN = clickDue ? 1f : 0f;
                lastV2ClickDue = clickDue ? 1f : 0f; // v2.0.2: oracle for the immature click-head fallback
                lastV2AimYawN = MathHelper.clamp(trYaw / maxDeg, -1f, 1f);
                lastV2AimPitN = MathHelper.clamp(trPit / maxDeg, -1f, 1f);

                // muscles — ActionSpace has no sneak bit, so it rides alongside.
                // v2.1.0 SNEAK LAW (mirror of the v1.0.12 ATTACK law): the sneak
                // muscle is HARD-DISABLED until the user opts in ("/pvpbot v2
                // sneak on") AND the head has pureSneakMinSteps of training —
                // and even then a decisive margin (0.25) is still required. An
                // immature sneak head can press shift ALL it wants in its action
                // space; the muscle simply never moves. This is the definitive
                // fix for "the Pure Model always shifts or holds shift".
                pureClickDesire = d.clickDesire;
                pureSneakIntent = cfg.pureSneak
                                && policy.getTrainSteps() >= cfg.pureSneakMinSteps
                                && d.sneak && d.sneakMargin >= 0.25f;
                int action = ActionSpace.encode(d.move, d.sprint, d.jump, false);
                int executed = humanizer.submit(action, lastAction >= 0 ? lastAction : action);
                applyAction(self, target, executed);
                sight.noteOwnAction(executed);
                lastAction = executed;
                lastV2State = state;
                lastV2Move = d.move;
                lastV2Sprint = d.sprint;
                lastV2Jump = d.jump;
                lastV2Sneak = d.sneak;
                lastDecisionTick = tickCounter;
                trainPulse();
        }

        /**
         * Called by the 120Hz aim thread / frame loop in pure mode: distributes
         * the tick's blended aim budget across frames (dt-corrected). Returns
         * null when pure mode is off — callers then use the v1 tracker path.
         */
        public float[] takePureAimFrame(float dtMs) {
                if (!cfg.pureMode || !controlling) {
                        return null;
                }
                float dy, dp;
                synchronized (this) {
                        // v2.0.2: drain at 2x the tick window — a geometric drain at
                        // dtMs/50 delivered only ~63% of each tick's shaped budget
                        // per 50ms and buffered ~1.6 ticks in the accumulator, so
                        // pure aim lagged the v1 tracker by 3+ frames. dtMs/25
                        // delivers ~90-95% per window with sub-frame latency.
                        float frac = MathHelper.clamp(dtMs / 25f, 0.05f, 1f);
                        dy = pureAimYawBudget * frac;
                        dp = pureAimPitBudget * frac;
                        pureAimYawBudget -= dy;
                        pureAimPitBudget -= dp;
                }
                pureAimFrame[0] = dy;
                pureAimFrame[1] = dp;
                return pureAimFrame;
        }

        /**
         * v2.0.1: the tick's SHAPED aim budget is ADDED to the accumulator
         * under a lock (the 120Hz aim thread drains it concurrently — the old
         * plain-field handoff was an unsynchronized cross-thread race) and
         * hard-capped so a runaway head can never stack slams across ticks.
         */
        private void addPureAimBudget(float yawDeg, float pitDeg) {
                synchronized (this) {
                        float cap = Math.max(2f, cfg.aimMaxTurnDeg);
                        pureAimYawBudget = MathHelper.clamp(pureAimYawBudget + yawDeg, -cap, cap);
                        pureAimPitBudget = MathHelper.clamp(pureAimPitBudget + pitDeg, -cap, cap);
                }
        }

        /** v2.0.1: take the WHOLE remaining budget (legacy 20Hz path), locked. */
        private float[] drainAllPureAimBudget() {
                synchronized (this) {
                        float[] out = {pureAimYawBudget, pureAimPitBudget};
                        pureAimYawBudget = 0f;
                        pureAimPitBudget = 0f;
                        return out;
                }
        }

        /** v2.0.1: kill any undistributed budget (target lost / pause / stop). */
        private void zeroPureAimBudget() {
                synchronized (this) {
                        pureAimYawBudget = 0f;
                        pureAimPitBudget = 0f;
                }
        }

        /** Toggle pure mode (command + ClickGUI). Never throws. */
        public void setPureMode(boolean on) {
                if (cfg.pureMode == on) {
                        return;
                }
                cfg.pureMode = on;
                cfg.save();
                zeroPureAimBudget(); // v2.0.1: never carry a budget across the toggle
                // v2.1.0: NEVER cross a mode boundary with shift held — the sneak
                // governor state dies here too.
                actuator.setSneak(false);
                pureSneakIntent = false;
                pureSneakHoldTicks = 0;
                if (on) {
                        announce("PURE MODE ON (v2.1) — proven tracker owns the aim (first-model law), the four-head "
                                        + "brain owns movement/clicks. Aim head + sneak muscle are ON THE BENCH until promoted "
                                        + "(/pvpbot v2 aimhead on | /pvpbot v2 sneak on). Physics gates stay.");
                } else {
                        announce("Pure mode OFF — v1 stack back in charge (TriggerBot + techniques).");
                }
        }

        /** v2.1.0: has the aim head EARNED execution? (opt-in + steps + proven loss) */
        private boolean aimHeadPromoted() {
                return policy.getTrainSteps() >= cfg.pureAimHeadMinSteps
                                && !Float.isNaN(policy.aimLossEma)
                                && policy.aimLossEma <= cfg.pureAimHeadMaxLoss;
        }

        /** v2.1.0: angular error in degrees from my crosshair to the target's chest. */
        private static float faceErrorDeg(ClientPlayerEntity self, LivingEntity target) {
                Vec3d chest = target.getEyePos().subtract(0, 0.7, 0); // ~chest: standing eye 1.62 - 0.7
                Vec3d to = chest.subtract(self.getEyePos());
                double len = Math.max(1e-4, to.length());
                Vec3d look = self.getRotationVec(1.0f);
                float dot = MathHelper.clamp((float) ((look.x * to.x + look.y * to.y + look.z * to.z) / len), -1f, 1f);
                return (float) Math.toDegrees(Math.acos(dot));
        }

        /**
         * v2.0.2: called after every model hot-swap. A v1 brain can only drive
         * the v1 stack — pure mode (which is PERSISTED in the config) must not
         * stay latched over it, or the bot fights with the WRONG brain while
         * the user believes their loaded model is in charge (reported as
         * "both models bug in aim"). Hops to the client thread — loadInto
         * runs on the background worker.
         */
        public void onModelSwapped(String descriptor) {
                if (descriptor != null && !descriptor.startsWith("[v2]") && cfg.pureMode) {
                        mc.execute(() -> {
                                setPureMode(false);
                                announce("v1 brain loaded — pure mode OFF (a v1 model can only drive the v1 stack).");
                        });
                }
        }

        /** One-line v2 brain status for the HUD / /pvpbot v2 status. */
        public String v2StatusLine() {
                String aim = Float.isNaN(policy.aimLossEma) ? "-"
                                : String.format("%.4f", policy.aimLossEma);
                String loss = Float.isNaN(lastV2TrainLoss) ? "-" : String.format("%.4f", lastV2TrainLoss);
                // v2.1.0: show the training-wheels gates
                String aimG = cfg.pureAimHead
                                ? (aimHeadPromoted() ? "PROMOTED" : "bench (opted-in, " + policy.getTrainSteps() + "/" + cfg.pureAimHeadMinSteps + " steps)")
                                : "bench";
                String sneakG = cfg.pureSneak
                                ? (policy.getTrainSteps() >= cfg.pureSneakMinSteps ? "armed" : "bench (" + policy.getTrainSteps() + "/" + cfg.pureSneakMinSteps + " steps)")
                                : "off";
                return String.format("v2 %s | buf %d | expert %d | steps %d | loss %s | aimLoss %s | aimHead %s | sneak %s",
                                cfg.pureMode ? "PURE" : "watch", policy.bufferSize(), policy.expertSize(),
                                policy.getTrainSteps(), loss, aim, aimG, sneakG);
        }

        /** Human-train observation: build the same state the policy would see,
         *  decode the user's REAL inputs into an action index, store as expert
         *  transition, and train. Nothing is ever actuated in this mode. */
        private void observeStep(ClientPlayerEntity self, LivingEntity target) {
                if (!inEpisode) beginEpisode();
                float matchTicks = tickCounter - episodeStartTick;
                float[] state = perception.build(self, target, hits, memory, terrain, tickCounter, matchTicks, hits.whiffRate());
                int userAction = decodeUserAction(self);
                float stepReward = hits.pendingReward; // shared by both brains
                if (lastState != null && lastAction >= 0) {
                        float r = stepReward;
                        dqn.remember(lastState, lastAction, r, state, false);
                        // DQfD: the same transition is a permanent expert demonstration —
                        // its margin-cloned loss keeps steering the policy during ALL
                        // later training (normal play + self-train), not just this mode.
                        if (cfg.imitationEnabled) {
                                dqn.rememberExpert(lastState, lastAction, r, state, false);
                        }
                        hits.pendingReward = 0f;
                        rewardEma += 0.02f * (r - rewardEma); // v1.0.11 honest reward EMA
                }
                lastState = state;
                lastAction = userAction;
                // v2.0 PHASE 2-c: the four-head brain watches too — move/sprint/
                // jump/sneak/aim/click ALL get human labels (BC + DQfD for every head)
                if (cfg.v2Imitation) {
                        // v2.1.0: expert transitions use the same 104-dim layout the
                        // brain acts on (old 84-dim demos would be rejected by the net)
                        float[] v2 = perception.buildV3(self, target, hits, memory, terrain,
                                        tickCounter, matchTicks, hits.whiffRate(), sight);
                        if (lastV2Expert != null && lastV2LblMove >= 0) {
                                policy.rememberExpert(lastV2Expert, lastV2LblMove, lastV2LblSprint,
                                                lastV2LblJump, lastV2LblSneak, lastV2LblAimYaw, lastV2LblAimPit,
                                                lastV2LblClick, lastV2ExpertReward, v2, false);
                        }
                        float aimMax = Math.max(1f, cfg.pureAimMaxDeg);
                        lastV2LblMove = ActionSpace.moveOf(userAction);
                        lastV2LblSprint = ActionSpace.sprintOf(userAction);
                        lastV2LblJump = ActionSpace.jumpOf(userAction);
                        lastV2LblSneak = mc.options.sneakKey.isPressed();
                        lastV2LblAimYaw = MathHelper.clamp(
                                        MathHelper.wrapDegrees(self.getYaw() - prevHumanYaw) / aimMax, -1f, 1f);
                        lastV2LblAimPit = MathHelper.clamp((self.getPitch() - prevHumanPitch) / aimMax, -1f, 1f);
                        lastV2LblClick = mc.options.attackKey.isPressed() ? 1f : 0f;
                        lastV2ExpertReward = stepReward;
                        prevHumanYaw = self.getYaw();
                        prevHumanPitch = self.getPitch();
                        lastV2Expert = v2;
                }
                trainPulse();
                // aim net learns the opponent's trajectory from YOUR fights too
                aim.learnStep(self, target, tickCounter);
        }

        /** Decode the player's real keyboard/mouse state into the policy's action space. */
        private int decodeUserAction(ClientPlayerEntity self) {
                boolean f = mc.options.forwardKey.isPressed();
                boolean b = mc.options.backKey.isPressed();
                boolean l = mc.options.leftKey.isPressed();
                boolean r = mc.options.rightKey.isPressed();
                int move;
                if (f && !b) move = l ? ActionSpace.M_WA : (r ? ActionSpace.M_WD : ActionSpace.M_W);
                else if (b && !f) move = l ? ActionSpace.M_SA : (r ? ActionSpace.M_SD : ActionSpace.M_S);
                else if (l && !r) move = ActionSpace.M_A;
                else if (r && !l) move = ActionSpace.M_D;
                else move = ActionSpace.M_NONE;
                boolean jump = mc.options.jumpKey.isPressed();
                boolean attack = mc.options.attackKey.isPressed();
                return ActionSpace.encode(move, self.isSprinting(), jump, attack);
        }

        private void oppSpeedFeed(LivingEntity target) {
                if (target != null) {
                        float sp = (float) Math.sqrt(target.getVelocity().x * target.getVelocity().x + target.getVelocity().z * target.getVelocity().z);
                        memory.noteSpeed(sp, 0f);
                }
        }

        // ------------------------------------------------------------ decision

        private void decisionStep(ClientPlayerEntity self, LivingEntity target) {
                // v2.0 PHASE 2-b PURE MODE: the four-head brain is the only
                // authority (movement, flags, aim, clicks) — the v1 stack
                // (mind, tactics chances, humanizer techniques, TriggerBot) is
                // bypassed. Physics laws stay: hard band gate, click governor,
                // on-target check, grounded/range jump sanity.
                if (cfg.pureMode) {
                        pureDecisionStep(self, target);
                        return;
                }
                if (!inEpisode) beginEpisode();

                float matchTicks = tickCounter - episodeStartTick;
                float[] state = perception.build(self, target, hits, memory, terrain, tickCounter, matchTicks, hits.whiffRate());

                // finish previous transition
                if (lastState != null && lastAction >= 0) {
                        boolean done = false; // transitions are step-wise; episode end handled at finish
                        float r = hits.pendingReward;
                        dqn.remember(lastState, lastAction, r, state, done);
                        hits.pendingReward = 0f;
                        // v1.0.11: the reward EMA now tracks REAL rewards again
                        rewardEma += 0.02f * (r - rewardEma);
                }

                // ---- v1.0.6 DECISION THINKING -------------------------------------
                // one forward pass feeds BOTH the action choice and the mind's
                // q-agreement vote — "should I sneak hit since he's comboing me?"
                // is answered by learned intent weights + the policy's own values.
                float[] qs = dqn.qValues(state);
                mind.think(self, target, hits, memory, terrain, tickCounter, qs,
                                Math.max(2, cfg.mindDeliberateTicks));
                tactics.aggressionHint = cfg.decisionMindEnabled ? mind.aggressionHint() : 0;
                tactics.sneakIntentBoost = cfg.decisionMindEnabled && mind.wantsSneak();
                // v1.0.8 model-decides votes: the mind (learned situation weights +
                // the DQN's Q-vote) now shapes WHEN techniques fire, inside the
                // config chance ceilings — the model decides, the config caps.
                tactics.wtapSuppressed = cfg.decisionMindEnabled && cfg.modelTechniques && mind.wantsComboExtend();
                tactics.airDenialBoost = cfg.decisionMindEnabled && cfg.modelTechniques && mind.wantsAirDenial();
                // v1.0.9: memory-driven prediction — when the opponent's profile
                // says they JUMP right after being hit, prime the air-denial vote
                // even before the mind's situation features catch it
                if (!tactics.airDenialBoost && target.isOnGround()
                                && memory.predictJumpNext() > 0.6f
                                && self.distanceTo(target) <= 3.6f) {
                        tactics.airDenialBoost = true;
                }
                // v1.0.9: the chase override stands down when the mind is
                // deliberately opening space (heal / open-field intents)
                tactics.mindWantsOpenSpace = cfg.decisionMindEnabled && mind.wantsOpenSpace();
                // v1.0.9 PERMA FIX: TEMPO_SPIKE ("click earlier NOW") used to
                // multiply the band threshold by 0.88 EVERY TICK it was held — a
                // long spike decayed the threshold toward 0 and the bot
                // machine-gunned again (worse the longer the mind trained, which
                // is why the spam returned after a few games). The reduction now
                // applies ONCE per swing and can NEVER push below hardBandFloor().
                if (cfg.decisionMindEnabled && cfg.modelTechniques && mind.wantsTempoSpike()
                                && !tempoAppliedThisSwing && nextBandThreshold > 0f) {
                        tempoAppliedThisSwing = true;
                        nextBandThreshold = Math.max(nextBandThreshold * 0.90f, hardBandFloor());
                }

                // ---- tactic event hooks ------------------------------------------------
                // WE landed a hit → roll the S-tap wtap (sprint + hit + S ~0.6s).
                // v1.0.8: passes the hit distance — no taps for far hits.
                if (hits.lastMyHitTick != prevMyHitTick) {
                        prevMyHitTick = hits.lastMyHitTick;
                        if (tickCounter == hits.lastMyHitTick) {
                                tactics.onMyHit(tickCounter, self.isSprinting(), self.distanceTo(target));
                        }
                }
                // WE got hit → schedule the strict 100-150ms jump reset AND check
                // whether the knockback shoved us into a wall (sides / behind) —
                // if so the wall escape takes over the movement next tick.
                if (hits.lastTakenHitTick != prevTakenTick) {
                        prevTakenTick = hits.lastTakenHitTick;
                        if (tickCounter == hits.lastTakenHitTick) {
                                tactics.onHurt(tickCounter);
                                tactics.noteTerrain(terrain);
                                tactics.onKnockedBack(tickCounter);
                        }
                }
                // occasional sneak hits now roll AT CLICK TIME inside the attack
                // section below (v1.0.7) — a roll on a random tick almost never
                // coincided with the triggerbot's click, which is why the sneak
                // chance config looked dead.
                int action = dqn.actFromQ(qs, epsilon());
                // the mind may swap a mismatched pick for its intent's own best-Q
                // action (soft bias — the DQN stays the muscles, the mind steers)
                if (cfg.decisionMindEnabled) {
                        action = mind.applyBias(action, cfg.decisionBias);
                }
                int executed = humanizer.submit(action, lastAction >= 0 ? lastAction : action);
                applyAction(self, target, executed);
                // v2.0 PHASE 1: the sight vector's own-action rhythm buffers read
                // the EXECUTED action (what the muscles did, not what was queued)
                sight.noteOwnAction(executed);
                lastState = state;
                lastAction = executed;
                lastDecisionTick = tickCounter;
        }

        private void applyAction(ClientPlayerEntity self, LivingEntity target, int action) {
                double dist = self.distanceTo(target);
                double distH = Math.sqrt(Math.pow(target.getX() - self.getX(), 2) + Math.pow(target.getZ() - self.getZ(), 2));
                float charge = self.getAttackCooldownProgress(0.0f);

                // v1.0.9 ACTIVE TRADE: a real duel trades hits BOTH ways —
                // comboDealt alone resets the instant we get hit, which kept the
                // combo orbit off for most of a real fight. An exchange stays
                // "live" for 2s after any hit in either direction.
                boolean activeTrade = hits.comboDealt >= 1
                                || tickCounter - Math.max(hits.lastMyHitTick, hits.lastTakenHitTick) <= 40;

                // ---- movement: tactics layer has veto/override rights ------------------
                // (wall escape > backoff > wtap S-window > over-retreat > combo strafe
                //  > anti-freeze floor > DQN move)
                // v2.0 PHASE 2-b: in PURE mode the four-head brain's pick runs raw —
                // no tactics veto, no sprint forcing, no wtap suppression. Physics
                // sanity only (vanilla handles backward-sprint).
                tactics.noteTerrain(terrain);
                int move = cfg.pureMode ? ActionSpace.moveOf(action)
                                : tactics.movePolicy(ActionSpace.moveOf(action), self, target, tickCounter, distH, hits.comboDealt, activeTrade);
                // v2.2.0 RETREAT GOVERNOR (user: "reduce the Pure Model backing off
                // tooooo much"). The pure brain discovered that backing up dodges
                // hits short-term and leaned on it. After pureRetreatLimit
                // consecutive backward ticks inside combat range — and while NOT
                // losing badly (their HP >= mine + 4) — the retreat is converted
                // into an orbit strafe toward the side the target sits on, plus a
                // small reward penalty that teaches the habit away. Outside combat
                // range retreating stays legal (chasing is the tactics layer's job
                // in v1; the head may close freely in pure).
                if (cfg.pureMode && cfg.pureRetreatLimit > 0) {
                        boolean back = move == ActionSpace.M_S || move == ActionSpace.M_SA
                                        || move == ActionSpace.M_SD;
                        if (!back) {
                                pureBackStreak = 0;
                        } else {
                                pureBackStreak++;
                                boolean losingBadly = self.getHealth() < target.getHealth() - 4f;
                                if (pureBackStreak > cfg.pureRetreatLimit && distH < 4.5f && !losingBadly) {
                                        // strafe toward the side the target is on (keeps them centered)
                                        float yawRad = (float) Math.toRadians(self.getYaw());
                                        float fx = -MathHelper.sin(yawRad), fz = MathHelper.cos(yawRad);
                                        double tdx = target.getX() - self.getX(), tdz = target.getZ() - self.getZ();
                                        float cross = (float) (fx * tdz - fz * tdx); // >0 = target on my left
                                        move = cross > 0 ? ActionSpace.M_WA : ActionSpace.M_WD;
                                        hits.pendingReward -= 0.004f; // teaching signal: retreating in range costs
                                }
                        }
                }
                // v2.2.1 AGGRESSION FLOOR (user: "reduce the Pure Model backing
                // off tooooo much" — the retreat governor only catches BACKWARD
                // moves; the brain also learned to idle-strafe (A/D/NONE) just
                // outside reach where nothing ever happens). After
                // pureCloseLimit consecutive ticks beyond 3.0m — and while not
                // losing badly — the movement is overridden with a CLOSING move
                // toward the target until the bot is back inside the pocket.
                if (cfg.pureMode && cfg.pureCloseLimit > 0) {
                        boolean losingBadly = self.getHealth() < target.getHealth() - 4f;
                        if (distH > 3.0f && !losingBadly && !self.isSneaking()) {
                                pureCloseStreak++;
                                if (pureCloseStreak > cfg.pureCloseLimit) {
                                        float yawRad = (float) Math.toRadians(self.getYaw());
                                        float fx = -MathHelper.sin(yawRad), fz = MathHelper.cos(yawRad);
                                        double tdx = target.getX() - self.getX(), tdz = target.getZ() - self.getZ();
                                        float cross = (float) (fx * tdz - fz * tdx); // >0 = target on my left
                                        move = Math.abs(cross) < 0.25f ? ActionSpace.M_W
                                                        : (cross > 0 ? ActionSpace.M_WA : ActionSpace.M_WD);
                                        // teaching signal: hovering out of range pays too
                                        hits.pendingReward -= 0.002f;
                                }
                        } else {
                                pureCloseStreak = 0;
                        }
                }
                actuator.setMove(move);
                boolean wtapActive = wtapIsTapMove(move);
                boolean backMove = move == ActionSpace.M_S || move == ActionSpace.M_SA
                                || move == ActionSpace.M_SD;
                // v1.0.7 SPRINT-HIT RULE: when sprint hits are required, sprint is
                // FORCED on for every non-retreating stance — the DQN's sprint bit
                // no longer decides it. A grounded, charged click while walking is
                // a vanilla SWEEP attack; keeping sprint on makes every grounded
                // hit a sprint hit by construction.
                boolean sprintIntent = cfg.pureMode ? ActionSpace.sprintOf(action)
                                : ActionSpace.sprintOf(action) && !wtapActive && !backMove;
                if (!cfg.pureMode && cfg.sprintHitOnly && !wtapActive && !backMove && !tactics.sneakWindowOpen()) {
                        sprintIntent = true;
                }
                // v2.2.0 PURE SPRINT-HIT LAW — the v1.0.12 physics rule ("a grounded
                // charged click while walking is a vanilla SWEEP attack") now holds
                // in pure mode too: sprint is forced on for every non-retreating,
                // non-sneaking stance. Without this an immature sprint head kept
                // the bot walking, the sprint-hit gate then blocked every click
                // ("pure model should attack immediately when its crosshair can
                // hit the hitbox") AND produced sweep hits when one slipped through.
                if (cfg.pureMode && cfg.sprintHitOnly && !wtapActive && !backMove && !pureSneakIntent) {
                        sprintIntent = true;
                }
                actuator.setSprint(sprintIntent);
                if (cfg.pureMode) {
                        // v2.0.2 SNEAK GOVERNOR (user: "the Pure Model always shifts
                        // or holds shift"). The sneak head has no strong self-play
                        // signal separating on/off, so an immature pick + hysteresis
                        // could LATCH shift for a whole round. Sneak now needs a
                        // decisive margin (set in pureDecisionStep), fires only
                        // grounded and in range, and can NEVER hold longer than 8
                        // consecutive ticks (then a 10-tick forced cooldown) — a
                        // human sneak window, never a crouch-lock.
                        boolean wantSneak = pureSneakIntent && self.isOnGround() && dist <= 3.2;
                        if (!wantSneak) {
                                pureSneakHoldTicks = 0;
                                actuator.setSneak(false);
                        } else if (pureSneakHoldTicks < 8 && tickCounter >= pureSneakCooldownUntil) {
                                pureSneakHoldTicks++;
                                actuator.setSneak(true);
                        } else {
                                if (pureSneakHoldTicks >= 8) {
                                        pureSneakCooldownUntil = tickCounter + 10;
                                }
                                pureSneakHoldTicks = 0;
                                actuator.setSneak(false);
                        }
                } else {
                        actuator.setSneak(tactics.sneakActive(tickCounter));
                }

                // ---- jumps: STRICTLY rationed -----------------------------------------
                if (cfg.pureMode) {
                        // v2.0 PHASE 2-b: the JUMP flag head decides — grounded, in
                        // reach, and rate-limited to one hop per 4 ticks (physics
                        // sanity: no bunny-spam, no air jumps, no far hops).
                        if (ActionSpace.jumpOf(action) && self.isOnGround()
                                        && dist <= 3.4 && dist > 0.5
                                        && tickCounter - lastPureJumpTick >= 4) {
                                actuator.setJump(true);
                                lastPureJumpTick = tickCounter;
                        }
                }
                // 1) jump reset — the ONLY reflex jump: exactly 100-150ms after being
                //    hit (scheduled by tactics.onHurt), pressed for a single tap.
                //    v1.0.8 RANGE GATE: only while the opponent is still close —
                //    jumping at a far opponent does nothing ("jump when he's far" bug).
                else if (tactics.pollJumpReset(tickCounter) && self.isOnGround() && dist <= 3.5) {
                        actuator.setJump(true);
                        lastReflexJumpTick = tickCounter;
                }
                // 1b) wall escape hop — only when cornered hard (wall behind AND
                //     both sides), rate-limited inside the tactics layer.
                else if (tactics.escapeActive() && self.isOnGround() && tactics.wantEscapeJump(tickCounter)) {
                        actuator.setJump(true);
                }
                // 2) technique jumps from the policy, each behind its own gate:
                //    crit attempt (occasionally) / midair knockback hit (occasionally).
                //    NONE of these can fire inside the wtap S-window anymore (v1.0.5:
                //    "the wtap must NOT jump") — the gates live in the tactics layer.
                //    v1.0.6: NONE fire while BACKING OFF either — "fix it when it's
                //    backing off it jumps". Jump reset + wall-escape hop remain.
                else if (ActionSpace.jumpOf(action)) {
                        boolean retreating = tactics.backoffActive()
                                        || move == ActionSpace.M_S || move == ActionSpace.M_SA
                                        || move == ActionSpace.M_SD;
                        if (retreating) {
                                // suppressed — retreating legs never jump (except the reflexes above)
                        } else if (self.isOnGround() && dist <= 3.4 && dist > 1.2) {
                                if (tactics.wantCritJump(tickCounter)) {
                                        actuator.setJump(true);
                                } else if (tactics.wantMidAir(tickCounter)) {
                                        actuator.setJump(true);
                                }
                                // else: no jump. Neutral grounded jumps were the
                                // "jumps too much" bug — they are gone.
                        }
                        // v1.0.9: the old 4-7m "chase jump" is GONE — the user is
                        // explicit that NO movement technique (least of all hopping)
                        // fires at a far opponent. The only long-range jump is the
                        // 10+ blocks sprint-jump intercept chase below, which is a
                        // chase hop, not a combat jump.
                }
                // 2b) v1.0.9 sprint-jump INTERCEPT CHASE — the 10+ blocks band ONLY
                //     (cfg.chaseSprintJumpMinDist): bunny-hop toward the predicted
                //     path while closing a fleeing/far opponent. Rate-limited
                //     (4-tick rhythm) inside the tactics layer; never fires inside
                //     10 blocks.
                else if (tactics.chaseJumpWanted(tickCounter, self.isOnGround())) {
                        actuator.setJump(true);
                }

                // ---- server pacing autodetect ------------------------------------
                // v1.0.9 HARDENED: a genuine no-cooldown (attack-speed attribute)
                // server refills the meter instantly, so the streak builds within a
                // couple of swings anyway; requiring 6 verified cycles instead of 3
                // makes a false latch on a normal 1.9 server practically impossible
                // (a false latch was the original "spams like 1.8" bug).
                long sinceSwing = tickCounter - lastSwingAttemptTick;
                if (!noCooldownServer && lastSwingAttemptTick >= 0) {
                        if (sinceSwing >= 1 && sinceSwing <= 2 && charge >= 0.999f) {
                                if (++fastRefillStreak >= 6) {
                                        noCooldownServer = true;
                                        fastRefillStreak = 0;
                                        if (!announcedNoCooldown) {
                                                announcedNoCooldown = true;
                                                announce("Classic server detected (no attack cooldown) — clicks paced to a human band.");
                                        }
                                }
                        } else if (sinceSwing > 12) {
                                fastRefillStreak = 0;
                        }
                }
                // v1.0.9 un-latch safety: if we THOUGHT this server has no attack
                // cooldown but a REAL attack's meter is still draining 1-2 ticks
                // later, the server does have one (hybrid servers boost only some
                // kits) — back to charge-band pacing.
                if (noCooldownServer && lastSwingAttemptTick >= 0
                                && sinceSwing >= 1 && sinceSwing <= 2 && charge <= 0.90f) {
                        noCooldownServer = false;
                        nextBandThreshold = -1f;
                        fastRefillStreak = 0;
                        announce("Attack cooldown present after all — back to charge-band pacing.");
                }

                // ---- attack: TriggerBot click inside a randomized 82%-96% band ------
                // v1.0.9 SPAM FIX: a click is only allowed when VANILLA's own
                // crosshair target is the opponent — exactly what a physical left
                // click acts on. The old lenient ray/cone gate also passed clicks
                // that vanilla registered as pure MISS swings; a miss swing never
                // drains the attack meter, the band stayed satisfied, and the bot
                // machine-gunned swing after swing (and latched "classic server"
                // off the never-draining meter). Now every click IS an attack, so
                // the meter always drains and pacing behaves.
                // v1.0.12 "DON'T LET THE MODEL HIT" (user instruction) — the DQN's
                // ATTACK bit no longer opens the click path AT ALL. The TriggerBot
                // is now the ONLY thing in the entire mod that may left-click: one
                // hit path, one pacing authority. The bit stays in the action space
                // (trained weights and encodings stay valid) — it just executes
                // nothing, like a vote the TriggerBot never counts. Damage rewards
                // still flow to the policy for TriggerBot hits, so the model keeps
                // learning movement/positioning that earns hits.
                // v2.0 PHASE 2-b EXCEPTION: in PURE mode the CLICK HEAD is the hit
                // authority instead of the TriggerBot (desire >= 0.5). The same
                // hard band gate + governors + on-target check still apply below —
                // the v1.0.12 physics laws are untouched.
                // v2.0.2 CLICK MATURITY FALLBACK: an untrained click head outputs
                // ~0 (linear), so pure mode never clicked until ~512 transitions
                // had accumulated AND training had converged. Until the head's
                // supervised error proves it (clickLossEma < 0.12), the intent
                // authority is the same deterministic should-have-clicked oracle
                // the head is being trained on — every physics gate below is
                // unchanged, and the head takes over the moment it earns it.
                boolean clickMature = !Float.isNaN(policy.clickLossEma) && policy.clickLossEma < 0.12f;
                // v2.2.0 PURE ATTACK LAW (user: "90% of the time the Pure Model should
                // attack immediately when its crosshair can hit the hitbox — without
                // triggerbot"). The trained click head can no longer VETO a valid
                // hit: when pureImmediateAttack is on, the deterministic should-have-
                // clicked oracle (crosshair on the hitbox + hard band gate + click
                // governors + range) opens the click path DIRECTLY, the same
                // reactivity the TriggerBot gives v1. The mature head's desire can
                // still add clicks on top of the oracle — never subtract.
                boolean attackIntent = cfg.pureMode
                                ? ((cfg.pureImmediateAttack && lastV2ClickDue >= 0.5f)
                                        || (clickMature && pureClickDesire >= 0.55f))
                                : cfg.triggerBot;
                // v1.0.10: progressive miss cooldown + absolute 2-tick swing gap
                boolean missCooldownOver = tickCounter - lastMissSwingTick >= missCooldownTicks();
                boolean swingGapOver = tickCounter - lastAnySwingTick >= 2;
                // v1.0.11 ABSOLUTE CLICK GOVERNOR — no two clicks, from ANY path
                // (model ATTACK, TriggerBot, classic-server burst, band, whatever)
                // can ever be closer than 3 ticks (6.7 CPS hard ceiling). The old
                // governors were per-path: the model's ATTACK clicks skipped
                // attackGateAllowed entirely, and the pre-latch window on a
                // no-cooldown server clicked every 2 ticks. This one cannot be
                // bypassed by any state, learned weight or server behavior.
                boolean clickGapOver = tickCounter - lastClickTick >= 3;
                // v1.0.12 HARD LEFT-CLICK BLOCK (user instruction: "not hit via
                // blocking LeftClick and try again instantly when it doesn't get
                // the current min and max percentage") — the TriggerBot may ONLY
                // click while the CURRENT vanilla cooldown percentage is inside
                // the configured [min, max] window. Below min = an undercharged
                // 1.8-style spam click — BLOCKED. Above max = the window was
                // missed — BLOCKED too, with one exception: a meter that has
                // already reached 100% can never climb back into the window, so
                // a FULL meter may click (that is exactly where a human's attack
                // indicator sits when they swing). This gate is universal — the
                // classic-server CPS path must pass it too, so a wrong or stale
                // latch can no longer pace clicks without the meter. Blocked
                // clicks are not queued or throttled: "try again instantly" —
                // the very next tick re-evaluates with the fresh percentage.
                boolean bandOk = charge >= cfg.attackCooldownMin
                                && (charge <= cfg.attackCooldownMax || charge >= 0.999f);
                if (attackIntent && bandOk && missCooldownOver && swingGapOver && clickGapOver && dist <= 2.95) {
                        boolean paced;
                        if (noCooldownServer) {
                                paced = humanizer.clickPaceAllowed((int) (tickCounter - hits.lastAttackAttemptTick), activeTrade);
                        } else {
                                if (nextBandThreshold < 0f) {
                                        // v1.0.8: the rolled band adapts per opponent (tempo/aggression
                                        // profile) — losing to a faster swinger clicks earlier.
                                        // v1.0.9 PERMA FIX: the roll is floored at hardBandFloor()
                                        // (was 0.70) — the adaptive multiplier and every other
                                        // mechanism are structurally unable to pace the bot faster
                                        // than a charged-sword rhythm, no matter what the models
                                        // have learned or how weird the server behaves.
                                        float band = cfg.attackCooldownMin
                                                        + rng.nextFloat() * Math.max(0.01f, cfg.attackCooldownMax - cfg.attackCooldownMin);
                                        if (cfg.adaptiveStyle) {
                                                band *= adapt.bandMult();
                                        }
                                        // v1.0.12: the roll now caps at the configured MAX (was a
                                        // flat 0.99) so a high roll can never produce a window
                                        // [threshold, max] that is empty — the click would only
                                        // ever fall through to the full-meter exception.
                                        float rollHi = Math.max(hardBandFloor(), cfg.attackCooldownMax);
                                        nextBandThreshold = MathHelper.clamp(band, hardBandFloor(), rollHi);
                                        tempoAppliedThisSwing = false; // fresh swing — a pending spike may apply once
                                }
                                // v1.0.12: the model's ATTACK bit no longer bypasses the
                                // humanizer gate — the old `|| attackOf(action)` let learned
                                // aggression skip the anti-double-click guard on every click
                                // it opened. The TriggerBot's own gate applies to every click.
                                paced = charge >= nextBandThreshold
                                                && humanizer.attackGateAllowed((int) (tickCounter - hits.lastAttackAttemptTick));
                        }
                        if (paced) {
                                // ---- v1.0.7 SPRINT-HIT GATE + SNEAK-AT-CLICK -------------
                                // A grounded click while NOT sprinting is a vanilla SWEEP
                                // attack — exactly the "sweep hits" the user sees. With
                                // sprintHitOnly, grounded clicks only fire on a REAL sprint
                                // (sprint is force-enabled above, so this costs 1-2 ticks
                                // after each S-tap — the natural S-tap rhythm). Midair
                                // clicks are exempt (sweep needs solid ground, and crit
                                // descent hits must stay possible). Sneak windows are the
                                // deliberate exception: a shift-click is the sneak-hit
                                // technique the config asks for.
                                boolean sneakClick = cfg.pureMode ? pureSneakIntent : tactics.sneakWindowOpen();
                                if (cfg.sprintHitOnly && self.isOnGround() && !self.isSprinting()
                                                && !sneakClick && !sprintGateBypass) {
                                        // sprint is forced on above — it engages within a tick
                                        // or two; hold fire until then (never sweep).
                                        // v2.2.1 PATIENCE (user: "90% of the time the Pure
                                        // Model should attack immediately when its crosshair
                                        // can hit the hitbox"): vanilla only sprints while
                                        // moving FORWARD — strafing/backing never engages it,
                                        // so the old 60-tick (3 s!) hold blocked every valid
                                        // hit while the brain circled. Pure mode holds for
                                        // sprintGatePatiencePure (10 ticks) then lets the
                                        // click through; v1 keeps the 60-tick tradeoff.
                                        int patience = cfg.pureMode
                                                        ? Math.max(2, cfg.sprintGatePatiencePure)
                                                        : 60;
                                        if (++noSprintTicks > patience) {
                                                sprintGateBypass = true;
                                                if (!cfg.pureMode) {
                                                        announce("Sprint unavailable (hunger/server?) — sprint-hit gate bypassed so attacks keep working.");
                                                }
                                        }
                                } else {
                                        noSprintTicks = 0;
                                        // v1.0.8: the sneak chance rolls ONLY inside the
                                        // on-target check below — rolling before it opened
                                        // sneak windows whose click never happened (the bot
                                        // was seen sneaking around while not attacking).
                                        if (vanillaOnTarget(mc, target)) {
                                                // v1.0.10: CRIT DESCENT GATE — during a jump-crit the
                                                // click is held until we are FALLING (vanilla crits land
                                                // on the descent; an ascent hit is a plain hit and wastes
                                                // the jump). MidAir (rising) hits are a separate window
                                                // and unaffected — midAirChance keeps working.
                                                if (tactics.critWindowActive() && self.getVelocity().y >= 0) {
                                                        // airborne, still rising — wait for the fall
                                                } else {
                                                        // v1.0.7: the sneak chance rolls HERE, at click time —
                                                        // deterministic at the extremes (0.0 never, 1.0 every hit)
                                                        if (!cfg.pureMode && !sneakClick && tactics.trySneakForClick(tickCounter)) {
                                                                sneakClick = true;
                                                        }
                                                        if (sneakClick) {
                                                                actuator.setSneak(true);
                                                                if (!cfg.pureMode && tactics.consumeSneakJump()) {
                                                                        actuator.setJump(true);
                                                                }
                                                        }
                                                        // v1.0.12: the actuator re-checks the window itself at
                                                        // the physical click — the left click is BLOCKED unless
                                                        // the meter is inside [min, max] (or full), no matter
                                                        // what any caller believes.
                                                        boolean swung = actuator.attack(cfg.attackCooldownMin, cfg.attackCooldownMax);
                                                        lastAnySwingTick = tickCounter; // absolute governor counts EVERY swing
                                                        lastClickTick = tickCounter;     // v1.0.11: ...and EVERY click
                                                        if (swung) {
                                                                hits.markAttackAttempt(tickCounter, !self.isOnGround() && self.getVelocity().y < 0, self.isSprinting());
                                                                // v1.0.9: verify the meter actually drained — a
                                                                // real attack always does. If somehow not (frame
                                                                // race), treat as a miss swing: never re-roll the
                                                                // band, never count it for pacing, cooldown the
                                                                // click path so a machine-gun can never start.
                                                                float chargeAfter = self.getAttackCooldownProgress(0.0f);
                                                                if (chargeAfter <= charge - 0.05f) {
                                                                        lastSwingAttemptTick = tickCounter;
                                                                        nextBandThreshold = -1f; // re-roll for the next swing
                                                                        missSwingStreak = 0;
                                                                        // v1.0.10: INSTANT W-TAP — the S-press starts THIS
                                                                        // tick for deterministic chance, same as a human
                                                                        // tapping with the click (no damage-packet wait)
                                                                        // v2.0 PURE: wtap is a technique chance — skipped.
                                                                        if (!cfg.pureMode) {
                                                                                tactics.onMySwing(tickCounter, self.distanceTo(target));
                                                                        }
                                                                } else {
                                                                        lastMissSwingTick = tickCounter;
                                                                        if (++missSwingStreak >= 3) {
                                                                                missSwingStreak = 0;
                                                                                fastRefillStreak = 0;
                                                                        }
                                                                }
                                                        }
                                                }
                                        }
                                }
                        }
                }

                // ---- v1.0.7 self-driving techniques --------------------------------
                // chance >= 1.0 means the technique is DETERMINISTIC: it fires
                // whenever its cooldown is ready and we are grounded in reach —
                // the DQN's jump bit is no longer required (that gate is why 1.0
                // never felt like 100%). 0 < c < 1 keeps the old probabilistic
                // path (rolls when the policy asks for a jump); c <= 0 never fires.
                // v2.0 PURE: technique chances are bypassed entirely.
                if (!cfg.pureMode && self.isOnGround() && dist <= 3.4 && dist > 1.2
                                && !tactics.backoffActive() && !tactics.wtapActive() && !tactics.escapeActive()
                                && tactics.aggressionHint >= 0 && tickCounter != lastReflexJumpTick) {
                        boolean fired = false;
                        if (cfg.critAttemptChance >= 0.999f) {
                                fired = tactics.forceCritJump(tickCounter);
                        } else if (cfg.midAirChance >= 0.999f) {
                                fired = tactics.forceMidAir(tickCounter);
                        }
                        if (fired) {
                                actuator.setJump(true);
                                lastReflexJumpTick = tickCounter;
                        }
                }
        }

        private static boolean wtapIsTapMove(int move) {
                // during the S-tap window the bot is moving back (S / SA / SD)
                return move == ActionSpace.M_S || move == ActionSpace.M_SA || move == ActionSpace.M_SD;
        }

        /**
         * v1.0.9 PERMA SPAM FIX — the absolute lower bound of the attack band.
         * Every writer of {@code nextBandThreshold} (the roll, the adaptive
         * engine multiplier, the TEMPO_SPIKE intent) clamps against this floor,
         * so NO learned weight, lag spike or misdetection can ever again decay
         * the pacing into 1.8-style machine-gunning on a cooldown server. With
         * the default band (0.82-0.96) the floor keeps clicks inside the
         * natural charged-sword rhythm even at maximum aggression.
         */
        private float hardBandFloor() {
                return Math.max(0.74f, cfg.attackCooldownMin - 0.08f);
        }

        /**
         * v1.0.5 movement reward shaping — "train the model via movements".
         * Tiny per-tick signals layered on top of the damage rewards so the
         * policy's own gradient descent learns GOOD FOOTWORK, the #1 weakness:
         * hold the 2.2-3.3 pocket, close distance when far, keep moving inside
         * reach (circle-strafe), NEVER freeze in range, and never back into a
         * wall. Same magnitudes as the python sim (train.py) for parity.
         */
        private void movementShaping(ClientPlayerEntity self, LivingEntity target) {
                double dx = target.getX() - self.getX(), dz = target.getZ() - self.getZ();
                double distH = Math.sqrt(dx * dx + dz * dz);
                float mvx = (float) self.getVelocity().x, mvz = (float) self.getVelocity().z;
                float speed = (float) Math.sqrt(mvx * mvx + mvz * mvz);
                // toward = my velocity projected onto the direction to the target
                double inv = distH < 1e-4 ? 0 : 1.0 / distH;
                float toward = (float) (mvx * dx * inv + mvz * dz * inv);

                if (distH >= 2.2f && distH <= 3.3f) {
                        hits.pendingReward += 0.004f;            // holding the pocket
                }
                if (distH > 4.5f) {
                        hits.pendingReward += toward > 0.05f ? 0.003f : -0.003f; // close distance
                }
                if (distH <= 3.4f && self.isOnGround()
                                && speed < 0.06f
                                && !tactics.wtapActive() && !tactics.escapeActive()) {
                        hits.pendingReward -= 0.008f;            // frozen inside reach — worst habit
                }
                if (distH <= 3.4f && speed > 0.12f) {
                        hits.pendingReward += 0.002f;            // keep moving in range (circle)
                }
                // v1.0.6: standing INSIDE the opponent is as bad as freezing — the
                // "gets too close" habit pays for itself
                if (distH < 1.5f) {
                        hits.pendingReward -= 0.005f;
                }
                // v1.0.6: backing up when already far — the "backs up TOOOOO much"
                // habit. v2.2.0: STRONGER signal (user asked again for pure mode) —
                // retreating beyond 3.2m now costs 1.5x, and mid-range retreat
                // (inside the pocket) costs a little too, so sustained backing-up
                // is never the cheap option the gradient can settle into.
                int myMove = Actuator.currentMoveCombo();
                boolean iBack = myMove == ActionSpace.M_S || myMove == ActionSpace.M_SA || myMove == ActionSpace.M_SD;
                if (iBack && distH > 3.2f) {
                        hits.pendingReward -= 0.006f;
                }
                if (iBack && distH <= 3.2f && distH > 1.6f) {
                        hits.pendingReward -= 0.002f;
                }
                // v1.0.6: jumping while retreating — wasted energy + telegraphs fear
                if (iBack && !self.isOnGround()) {
                        hits.pendingReward -= 0.003f;
                }
                boolean wallBehind = terrain.blocked[3] > 0.5f || terrain.blocked[4] > 0.5f || terrain.blocked[5] > 0.5f;
                if (wallBehind && toward < -0.08f) {
                        hits.pendingReward -= 0.004f;            // backing into a wall
                }
        }

        /**
         * v1.0.9: AUTHORITATIVE click gate — vanilla's own crosshair target,
         * i.e. the exact thing a physical left click acts on (refreshed by
         * GameRenderer every render frame; vanilla's entity raycast, real
         * 3.0 reach and block occlusion are all already inside it).
         *
         * This replaces the old custom ray/cone gate: that one also passed
         * clicks vanilla saw as MISS swings, a miss swing never drains the
         * attack meter, and the satisfied band then machine-gunned clicks
         * every tick (the "spams left click" bug). With this gate a click
         * that vanilla classifies as ENTITY always lands — the meter always
         * drains — so the pacing band can never run away.
         *
         * BLOCK → never click (mining mid-duel would be worse than a whiff);
         * MISS → never click (a human whiffs at most once, not 20x a second).
         */
        private static boolean vanillaOnTarget(MinecraftClient mc, LivingEntity target) {
                net.minecraft.util.hit.HitResult ct = mc.crosshairTarget;
                if (ct == null) return false;
                if (ct.getType() != net.minecraft.util.hit.HitResult.Type.ENTITY) return false;
                if (((net.minecraft.util.hit.EntityHitResult) ct).getEntity() != target) return false;
                // v1.0.10 LIVE RAYCAST — the cached crosshairTarget is from the last
                // render frame; a strafing/chased target can slip off the hitbox
                // between then and the click, and vanilla registers a MISS swing
                // (no meter drain -> the band stays satisfied -> swing spam).
                // Re-run vanilla's own entity pick NOW, this tick, before clicking.
                if (mc.player == null || mc.world == null) return false;
                Vec3d eye = mc.player.getEyePos();
                Vec3d end = eye.add(mc.player.getRotationVec(1.0f).multiply(3.0));
                EntityHitResult live = ProjectileUtil.raycast(mc.player, eye, end,
                                target.getBoundingBox(), e -> e == target, 3.0 * 3.0);
                return live != null && live.getEntity() == target;
        }

        private void beginEpisode() {
                inEpisode = true;
                diedThisEpisode = false;
                episodeStartTick = tickCounter;
                lastState = null;
                lastAction = -1;
                // v2.0: the four-head brain's open transition starts clean too
                lastV2State = null;
                lastV2Expert = null;
                lastV2LblMove = -1;
                lastPureJumpTick = -1000;
                pureBackStreak = 0;          // v2.2.2 retreat governor starts fresh
                pureCloseStreak = 0;         // v2.2.1 aggression floor starts fresh
                pureSneakHoldTicks = 0;      // v2.0.2
                pureSneakCooldownUntil = 0;  // v2.0.2
                pureSneakIntent = false;     // v2.1.0: a new round never starts mid-sneak
                actuator.setSneak(false);    // v2.1.0: release shift across round boundaries
                lastPureFaceErr = -1f;       // v2.1.0: face-error shaping starts fresh
                zeroPureAimBudget(); // v2.0.1: locked reset (aim thread may be draining)
                hits.resetEpisode(mc.player, selector.target());
                aim.resetFight();
                tactics.resetFight();
                mind.resetEpisode();
                sight.resetFight(); // v2.0: action/opponent rhythm buffers start clean
                humanizer.resetPacing(); // v1.0.10: burst state never crosses rounds
                prevMyHitTick = -1000;
                prevTakenTick = -1000;
                nextBandThreshold = -1f;
                matchTime = 0f;
                noSprintTicks = 0;
                sprintGateBypass = false;
                resetPacingState();
        }

        /**
         * v1.0.10 — ONE shared pacing reset used by BOTH the episode start and
         * the death->respawn transition. The old code only reset at
         * beginEpisode, but the respawn path (training session: DEAD_WAIT ->
         * ENGAGED) re-enters combat BEFORE the 5s round-settle debounce fires
         * beginEpisode — so round 2 started with round 1's pacing state alive
         * (classic-server latch, miss streaks, band roll, tempo flag, the
         * absolute swing governor). Every round now starts provably clean.
         *
         * v1.0.11 ROOT-CAUSE FIX for "every death / second round it still
         * spams abit": the classic-server latch (noCooldownServer) is a
         * SERVER property, not a round property — the server does not change
         * between rounds. Wiping it at every round boundary forced a fresh
         * detection window each round, and inside that window the charge band
         * is ALWAYS satisfied (on a no-cooldown server the meter never
         * leaves 100%), so the model's ATTACK clicks ran at the governor cap
         * until the latch rebuilt — a visible burst at the start of EVERY
         * round. The latch (its detection progress and its announce flag
         * with it) now persists across rounds AND across restarts via
         * saveStateTo; a false latch still self-corrects within ONE real
         * swing through the un-latch check above.
         */
        private void resetPacingState() {
                lastMissSwingTick = -1000;
                missSwingStreak = 0;
                lastSwingAttemptTick = -1000;
                lastAnySwingTick = -1000;
                tempoAppliedThisSwing = false;
        }

        public void finishEpisode(String result) {
                if (!inEpisode) return;
                inEpisode = false;
                // terminal transition — kills pay BIG (user: 5-10x the old reward)
                if (lastState != null && lastAction >= 0) {
                        float terminal = result.equals("WIN") ? cfg.winReward
                                        : result.equals("LOSS") ? cfg.lossReward : 0f;
                        float r = hits.pendingReward + terminal;
                        dqn.remember(lastState, lastAction, r, lastState, true);
                        // v2.0 PHASE 2-b: the four-head brain gets the same terminal
                        // signal on ITS open transition (whichever head was driving)
                        if (lastV2State != null) {
                                policy.remember(lastV2State, lastV2Move, lastV2Sprint, lastV2Jump, lastV2Sneak,
                                                lastV2AimYawN, lastV2AimPitN, lastV2ClickN, r, lastV2State, true);
                        }
                        hits.pendingReward = 0f;
                        rewardEma += 0.02f * (r - rewardEma); // v1.0.11 terminal counts too
                }
                lastV2State = null;
                lastV2Expert = null;
                lastV2LblMove = -1;
                // v1.0.5: the 16-step training burst + the ~6.4MB model save used
                // to run ON the game thread here (100-500ms freeze at every
                // episode end — the "lag when I start / train / human-train").
                // Both are queued on the background worker now; the single worker
                // thread guarantees the save lands AFTER the burst.
                final int burstBatch = Math.max(16, cfg.trainBatch);
                final float burstLr = currentLr();
                PvpBot.worker().execute(() -> {
                        for (int i = 0; i < 16; i++) {
                                dqn.trainStep(burstBatch, burstLr);
                        }
                        dqn.syncTarget();
                });
                PvpBot.get().saveModelAsync();
                // v1.0.6: persist the decision mind's learned intent weights too
                PvpBot.worker().execute(mind::persist);
                // v1.0.8: persist the per-opponent adaptation profiles
                PvpBot.worker().execute(adapt::persist);
                // v2.0 PHASE 3-b: auto-checkpoint the active brain during
                // training sessions (every N episodes) — a crash or a bad
                // opponent never costs more than N rounds of progress
                if (trainingSession && cfg.checkpointEveryEpisodes > 0
                                && episodesDone % cfg.checkpointEveryEpisodes == 0) {
                        final String cname = "checkpoint-ep" + episodesDone;
                        final boolean v2active = cfg.pureMode;
                        final PvpBot botRef = PvpBot.get();
                        PvpBot.worker().execute(() -> {
                                try {
                                        if (v2active) {
                                                ModelStore.saveSnapshotV2(cname, botRef);
                                        } else {
                                                ModelStore.saveSnapshot(cname, botRef);
                                        }
                                } catch (Exception e) {
                                        dev.z.pvpbot.PvpBot.LOGGER.warn("[pvpbot] checkpoint failed: {}", e.toString());
                                }
                        });
                        announce("Checkpoint saved: " + cname);
                }
                // v2.0 PHASE 3-b: eval scorecard bookkeeping
                if (evalMode) {
                        evalWld[result.equals("WIN") ? 0 : result.equals("LOSS") ? 1 : 2]++;
                        evalDmgDealt += hits.dmgDealt;
                        evalDmgTaken += hits.dmgTaken;
                        evalHits += hits.hitsLanded;
                        evalWhiffs += hits.whiffs;
                        evalRemaining--;
                        if (evalRemaining <= 0) {
                                finishEval();
                                return;
                        }
                }
                episodesDone++;
                if (result.equals("WIN")) wins++;
                else if (result.equals("LOSS")) losses++;
                else draws++;
                if ((trainingSession || humanTraining) && !result.equals("ABORTED")) {
                        sessionEpisodes++;
                        if (result.equals("WIN")) sessionWins++;
                        else if (result.equals("LOSS")) sessionLosses++;
                        else sessionDraws++;
                }
                appendCsv(result);
                lastAnnouncement = String.format(
                                "EP %d: %s — dealt %.1f / taken %.1f (hits %d, whiffs %d, crits %d, wtaps %d, jresets %d, sneaks %d, escapes %d)",
                                episodesDone, result, hits.dmgDealt, hits.dmgTaken, hits.hitsLanded, hits.whiffs, hits.critsLanded,
                                tactics.wtapCount, tactics.jumpResetCount, tactics.sneakHitCount, tactics.escapeCount);
                announce(lastAnnouncement);
                lastState = null;
                lastAction = -1;
        }

        private void appendCsv(String result) {
                try {
                        Path p = BotConfig.dir().resolve("training_log.csv");
                        boolean exists = Files.exists(p);
                        StringBuilder sb = new StringBuilder();
                        if (!exists) {
                                sb.append("timestamp,episode,result,dmgDealt,dmgTaken,hits,whiffs,crits,epsilon,durationTicks,opponent\n");
                        }
                        sb.append(Instant.now().toString()).append(',')
                                        .append(episodesDone).append(',')
                                        .append(result).append(',')
                                        .append(String.format("%.2f,%.2f,%d,%d,%d,%.3f,%d,%s",
                                                        hits.dmgDealt, hits.dmgTaken, hits.hitsLanded, hits.whiffs, hits.critsLanded,
                                                        epsilon(), tickCounter - episodeStartTick,
                                                        memory.opponentName.replace(',', ' '))).append('\n');
                        Files.createDirectories(p.getParent());
                        Files.writeString(p, sb.toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException ignored) {
                }
        }

        // ------------------------------------------------------------ curriculum

        public float epsilon() {
                if (evalMode) return 0f; // PHASE 3-b: pure exploitation during eval
                float eps;
                if (cfg.epsilonOverride != null) {
                        eps = MathHelper.clamp(cfg.epsilonOverride, 0f, 1f);
                } else if (episodesDone < cfg.curriculumEpisodes) {
                        float t = episodesDone / (float) cfg.curriculumEpisodes;
                        eps = MathHelper.lerp(t, cfg.epsilonRapidStart, cfg.epsilonRapidEnd);
                } else {
                        eps = cfg.epsilonStable;
                }
                // v2.0 PHASE 3-a: ENTROPY-STYLE DECAY — the exploration SURPLUS
                // above the stable floor shrinks exponentially with training
                // volume (either brain's steps count), so a heavily-trained brain
                // explores strictly less than the episode curriculum alone says.
                long steps = Math.max(dqn.getTrainSteps(), policy.getTrainSteps());
                float decay = (float) Math.exp(-steps / Math.max(1f, cfg.entropyTau));
                return Math.max(cfg.epsilonStable, cfg.epsilonStable + (eps - cfg.epsilonStable) * decay);
        }

        public float currentLr() {
                return episodesDone < cfg.curriculumEpisodes ? cfg.lrRapid : cfg.lrStable;
        }

        public String curriculumPhase() {
                return episodesDone < cfg.curriculumEpisodes
                                ? String.format("RAPID (%d/%d)", episodesDone, cfg.curriculumEpisodes)
                                : "STABLE";
        }

        // ------------------------------------------------------------ hud getters

        public float rewardEma() {
                return rewardEma;
        }

        /** v1.0.11: responsive EMA of the actual DQN training loss (Huber). */
        public float lossEma() {
                return lossEma;
        }

        public long tick() {
                return tickCounter;
        }

        public boolean inEpisode() {
                return inEpisode;
        }

        public TargetSelector selector() {
                return selector;
        }

        /** v2.0: public read access to the live config (UI + commands). */
        public BotConfig config() {
                return cfg;
        }

        private void announce(String msg) {
                if (mc.player != null) {
                        mc.player.sendMessage(Text.literal("[PvPBot] " + msg), false);
                }
        }

        /** v2.1.0: chat hook for subsystems outside the controller (vision recorder). */
        public void announcePublic(String msg) {
                announce(msg);
        }

        public void saveStateTo(JsonObject o) {
                o.addProperty("episodesDone", episodesDone);
                o.addProperty("wins", wins);
                o.addProperty("losses", losses);
                o.addProperty("draws", draws);
                // v1.0.11: the classic-server latch is a SERVER property — it
                // persists across rounds AND restarts, so no session ever starts
                // inside an un-paced detection window.
                o.addProperty("noCooldownServer", noCooldownServer);
                o.addProperty("fastRefillStreak", fastRefillStreak);
        }

        public String sessionSummary() {
                return String.format("session: %d eps (W/L/D %d/%d/%d) — %s",
                                sessionEpisodes, sessionWins, sessionLosses, sessionDraws,
                                fmtDuration(tickCounter - sessionStartTick));
        }

        public void loadStateFrom(JsonObject o) {
                episodesDone = o.has("episodesDone") ? o.get("episodesDone").getAsInt() : 0;
                wins = o.has("wins") ? o.get("wins").getAsInt() : 0;
                losses = o.has("losses") ? o.get("losses").getAsInt() : 0;
                draws = o.has("draws") ? o.get("draws").getAsInt() : 0;
                if (o.has("noCooldownServer")) {
                        noCooldownServer = o.get("noCooldownServer").getAsBoolean();
                        announcedNoCooldown = noCooldownServer; // do not re-announce the known latch
                }
                if (o.has("fastRefillStreak")) {
                        fastRefillStreak = o.get("fastRefillStreak").getAsInt();
                }
        }
}
