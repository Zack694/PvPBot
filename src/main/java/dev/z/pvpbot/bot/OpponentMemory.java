package dev.z.pvpbot.bot;

import java.util.Random;
import java.util.UUID;

/**
 * The 4-MINUTE opponent memory (v1.0.9, user spec: "make the Memory 4 mins
 * and please make the Memory Advanced like more movement Knowledge of it").
 *
 * A ring buffer of behavioral snapshots (4 Hz x 960 = 240 s) plus
 * incrementally-updated style statistics. v1.0.9 adds a real movement model:
 *
 *   - speedEma        — how fast they generally move (runner vs walker)
 *   - strafeRatio     — share of their motion that is LATERAL (circle-strafers)
 *   - fleeRatio       — share of time they actively open distance
 *   - sneakRatio      — share of time on shift (sneak-hit playstyle)
 *   - airRatio        — share of time airborne (jump-heavy playstyle)
 *   - dirChanges      — direction flips while moving (jitter/ stutter players)
 *   - recent trend windows (last 8 s) so the CURRENT phase of the fight
 *     dominates the short-term predictions
 *
 * PREDICTION API (user: "make it Predict from the Opponents Memory (and
 * normal prediction) if he is like going to jump or Not or Sneak Hit"):
 *   predictJumpNext()  — P(they jump within the next ~1 s), from their jump
 *                        habit + post-hit jump rate + the recent air trend.
 *   predictSneakNext() — P(they are about to shift-hit), from sneak habit
 *                        + whether they are sneaking RIGHT NOW (persistence).
 *   fleeBias()         — how hard they are opening distance right now.
 *
 * Nothing here decides what to DO — it measures and predicts; the tactics
 * layer and the mind consume the probabilities.
 */
public final class OpponentMemory {

        public static final int SNAP_INTERVAL = 5;      // ticks (4Hz)
        public static final int CAPACITY = 960;         // 240s at 4Hz — 4 minutes
        private static final int RECENT = 32;           // last 8 s trend window

        public UUID opponentId;
        public String opponentName = "";
        private final float[] distSnap = new float[CAPACITY];
        private final float[] speedSnap = new float[CAPACITY];
        private final float[] airSnap = new float[CAPACITY];
        private final float[] towardSnap = new float[CAPACITY];
        private final float[] sneakSnap = new float[CAPACITY];
        private int head = 0, size = 0;
        private int sinceSnap = 0;
        private long lastUpdateTick = -1;

        // counters
        private float wtapEvents, stapEvents, jumpEvents, critEvents;
        private float theirHits, myHits;
        private float snapshots;
        private float avgDist = 3f;
        private float aggression = 0.1f; // their hits per 10s
        // post-my-hit reaction probing
        private int postHitWatchTicks = -1;
        private float postHitJumpTrials, postHitJumpHits;
        private float postHitRetreatTrials, postHitRetreatHits;
        private float distAtPostHitStart;
        private int theirAirBefore = 0;

        // --- v1.0.9 advanced movement knowledge ---
        private float speedEma = 0.2f;         // their horizontal speed EMA
        private float lateralAccum, speedAccum; // per-snapshot lateral/total motion
        private float sneakTicks, airTicks, fleeTicks; // lifetime tick tallies
        private float observedTicks;
        private float prevVx, prevVz;          // for direction-change detection
        private float dirChangeRate;           // EMA: flips per second while moving
        private long lastFlipTick = -1000;
        private boolean sneakingNow = false;
        private long sneakStartTick = -1;
        private float recentSneakTrend;        // EMA of last-8s sneak share

        public void reset(UUID id, String name) {
                opponentId = id;
                opponentName = name != null ? name : "";
                head = size = 0;
                sinceSnap = 0;
                wtapEvents = stapEvents = jumpEvents = critEvents = 0f;
                theirHits = myHits = snapshots = 0f;
                avgDist = 3f;
                postHitWatchTicks = -1;
                postHitJumpTrials = postHitJumpHits = 0f;
                postHitRetreatTrials = postHitRetreatHits = 0f;
                speedEma = 0.2f;
                lateralAccum = speedAccum = 0f;
                sneakTicks = airTicks = fleeTicks = 0f;
                observedTicks = 0f;
                prevVx = prevVz = 0f;
                dirChangeRate = 0f;
                lastFlipTick = -1000;
                sneakingNow = false;
                sneakStartTick = -1;
                recentSneakTrend = 0f;
        }

        public boolean matches(UUID id) {
                return id != null && id.equals(opponentId);
        }

        /**
         * Called every tick while a target is tracked.
         * v1.0.9: real velocity components (their motion decomposed along /
         * across the line to me) + sneak state feed the movement model —
         * the old signature passed a hardcoded 0 toward-me speed.
         */
        public void tick(double dist, float theirSpeed, float theirVelTowardMe, float theirVelLateral,
                         int theirAir, boolean onGround, long tick, boolean sneaking) {
                if (lastUpdateTick > 0 && tick - lastUpdateTick > 240L * 20) {
                        // memory expired — opponent vanished for 4+ minutes, start fresh
                        reset(opponentId, opponentName);
                }
                lastUpdateTick = tick;
                avgDist += 0.02f * ((float) dist - avgDist);
                observedTicks++;

                // jump detection: ground -> air transition
                if (theirAirBefore == 0 && theirAir > 0 && snapshots > 20) {
                        jumpEvents += 1f;
                }
                theirAirBefore = theirAir;

                // --- movement tallies ---
                speedEma += 0.05f * (theirSpeed - speedEma);
                speedAccum += theirSpeed;
                lateralAccum += Math.abs(theirVelLateral);
                if (theirAir != 0) airTicks++;
                if (theirVelTowardMe < -0.10f) fleeTicks++;   // actively opening distance
                boolean wasSneaking = sneakingNow;
                sneakingNow = sneaking;
                if (sneaking) {
                        sneakTicks++;
                        if (!wasSneaking) sneakStartTick = tick;
                }

                // direction flips are measured in noteVelocity() (raw components)

                // periodic snapshot
                if (++sinceSnap >= SNAP_INTERVAL) {
                        sinceSnap = 0;
                        distSnap[head] = (float) dist;
                        speedSnap[head] = theirSpeed;
                        airSnap[head] = theirAir;
                        towardSnap[head] = theirVelTowardMe;
                        sneakSnap[head] = sneaking ? 1f : 0f;
                        head = (head + 1) % CAPACITY;
                        if (size < CAPACITY) size++;
                        snapshots += 1f;
                }

                // sprint-reset signature: shortly after hitting us, a fast forward rusher's speed dips hard (sprint released)
                if (postHitWatchTicks > 0) {
                        postHitWatchTicks--;
                }
        }

        /** v1.0.9: raw velocity components from the controller for flip detection. */
        private float lastRawVx, lastRawVz;
        private boolean hasRawVel = false;

        public void noteVelocity(float vx, float vz, long tick) {
                float sp = (float) Math.sqrt(vx * vx + vz * vz);
                if (sp > 0.12f && hasRawVel) {
                        float prevSp = (float) Math.sqrt(lastRawVx * lastRawVx + lastRawVz * lastRawVz);
                        if (prevSp > 0.12f) {
                                float dot = (lastRawVx * vx + lastRawVz * vz) / (prevSp * sp);
                                if (dot < 0.5f) { // rotated > 60 degrees
                                        if (lastFlipTick > 0 && tick - lastFlipTick <= 20) {
                                                float flipsPerSec = 1f / Math.max(1f, tick - lastFlipTick);
                                                dirChangeRate += 0.2f * (flipsPerSec - dirChangeRate);
                                        }
                                        lastFlipTick = tick;
                                }
                        }
                }
                lastRawVx = vx;
                lastRawVz = vz;
                hasRawVel = true;
                // sneak trend: recent 8s share EMA
                recentSneakTrend += 0.02f * ((sneakingNow ? 1f : 0f) - recentSneakTrend);
        }

        /** Called when THEY hit US. crit = they were falling. */
        public void onTheirHitMe(boolean crit, float theirSpeedNow, float theirVelTowardMeNow) {
                theirHits += 1f;
                // v2.3: the aggression feature was never updated (constant 0.1 in
                // every fight). The v1 brain was pretrained with their hits / 20,
                // so that is what it gets now.
                aggression = Math.min(1f, theirHits / 20f);
                if (crit) critEvents += 1f;
                // sprint reset signatures: speed collapse right after a hit while they were rushing
                if (theirSpeedNow > 0.24f && theirVelTowardMeNow > 0.1f) {
                        // mark: watch next ticks for speed dip (w-tap) or reversed velocity (s-tap)
                        wtapWatch = 6;
                }
        }

        private int wtapWatch = -1;

        /** Called when WE hit THEM — begins the post-hit reaction probe. */
        public void onMyHitThem(double dist) {
                myHits += 1f;
                postHitWatchTicks = 10;
                distAtPostHitStart = (float) dist;
                postHitJumpTrials += 1f;
                postHitRetreatTrials += 1f;
        }

        /** Continuation of post-hit probe: returns true if probe finished this tick. */
        public void probePostHit(boolean theyJumpedThisTick, double distNow) {
                if (postHitWatchTicks > 0) {
                        if (theyJumpedThisTick) postHitJumpHits += 1f;
                        if (distNow > distAtPostHitStart + 0.9) postHitRetreatHits += 1f;
                        if (--postHitWatchTicks == 0) {
                                postHitJumpTrials = Math.max(1, Math.min(postHitJumpTrials, 120));
                                postHitRetreatTrials = Math.max(1, Math.min(postHitRetreatTrials, 120));
                        }
                }
                if (wtapWatch > 0) {
                        wtapWatch--;
                }
        }

        public void noteSpeed(float theirSpeed, float theirVelTowardMe) {
                if (wtapWatch > 0) {
                        if (theirSpeed < 0.16f) wtapEvents += 1f; // sprint dropped right after their hit = sprint reset (w-tap/s-tap family)
                        wtapWatch = -1;
                }
        }

        /** Lifetime tallies normalized into 0..1 shares. */
        private float share(float tally) {
                return observedTicks < 60f ? 0f : Math.min(1f, tally / observedTicks);
        }

        /** Mean over the most recent n snapshots of a ring buffer. */
        private float recentMean(float[] ring, int n) {
                if (size == 0) return 0f;
                int count = Math.min(Math.min(n, size), ring.length);
                float sum = 0f;
                for (int i = 1; i <= count; i++) {
                        int idx = (head - i + CAPACITY * 2) % CAPACITY;
                        sum += ring[idx];
                }
                return sum / count;
        }

        public float[] features() {
                float denom = Math.max(1f, theirHits);
                float[] f = new float[12];
                f[0] = Math.min(1f, wtapEvents / Math.max(4f, denom));
                f[1] = Math.min(1f, stapEvents / Math.max(4f, denom));
                f[2] = Math.min(1f, jumpEvents / Math.max(4f, snapshots / 8f));
                f[3] = Math.min(1f, critEvents / denom);
                f[4] = Math.min(1f, aggression);
                f[5] = Math.min(2f, avgDist / 3f) / 2f;
                f[6] = postHitJumpTrials >= 3 ? postHitJumpHits / postHitJumpTrials : 0.5f;
                f[7] = postHitRetreatTrials >= 3 ? postHitRetreatHits / postHitRetreatTrials : 0.5f;
                // v1.0.9 advanced movement knowledge (callers indexing 0..7 unaffected)
                float total = Math.max(0.01f, speedAccum);
                f[8] = Math.min(1f, lateralAccum / total);            // strafeRatio
                f[9] = share(fleeTicks);                              // fleeRatio
                f[10] = share(sneakTicks);                            // sneakRatio
                f[11] = share(airTicks);                              // airRatio
                return f;
        }

        // ------------------------------------------------------------ v1.0.9 prediction API

        /**
         * P(they jump within the next ~1 s), 0..1. Blends their lifetime jump
         * habit, their measured post-hit jump rate (dominant right after WE
         * hit them), and whether they are already hopping (persistence).
         */
        public float predictJumpNext() {
                if (snapshots < 20f) return 0.15f; // not enough evidence — mild default
                float jumpHabit = Math.min(1f, jumpEvents / Math.max(4f, snapshots / 8f));
                float postHit = postHitJumpTrials >= 3 ? postHitJumpHits / postHitJumpTrials : 0.35f;
                boolean recentlyHitByMe = postHitWatchTicks > 0;
                float airNow = theirAirBefore != 0 ? 1f : 0f;
                float p = 0.35f * jumpHabit + 0.45f * postHit * (recentlyHitByMe ? 1.4f : 0.5f) + 0.2f * airNow;
                return Math.max(0f, Math.min(1f, p));
        }

        /**
         * P(they are committing to a sneak-hit right now / within the next
         * second), 0..1. Sneaking persists a few ticks — a player who is on
         * shift is almost certainly shift-hitting.
         */
        public float predictSneakNext() {
                if (sneakingNow) return 1f;
                if (snapshots < 20f) return 0.1f;
                float habit = share(sneakTicks);
                float recent = Math.min(1f, recentSneakTrend * 1.5f);
                return Math.max(0f, Math.min(1f, 0.4f * habit + 0.6f * recent));
        }

        /** How hard they are opening distance RIGHT NOW (-1 closing .. +1 fleeing). */
        public float fleeBias() {
                if (size == 0) return 0f;
                return Math.max(-1f, Math.min(1f, recentMean(towardSnap, RECENT) * -4f));
        }

        /** True when the recent trend says they are actively running away. */
        public boolean isFleeing() {
                return fleeBias() > 0.35f;
        }

        /** Their smoothed horizontal speed (blocks/tick) — runners read high. */
        public float speedEstimate() {
                return speedEma;
        }

        /** Direction flips per second while moving (jitter players read high). */
        public float directionChangeRate() {
                return dirChangeRate;
        }

        /**
         * Ticks since this opponent was last observed (v1.0.9 fix — the old
         * getter returned the raw tick counter, so the "memory age" feature the
         * policy saw was always pegged at its clamp instead of measuring how
         * stale the profile is).
         */
        public long memoryAgeTicks(long nowTick) {
                return lastUpdateTick < 0 ? 0 : Math.max(0, nowTick - lastUpdateTick);
        }

        public int hitsObserved() {
                return (int) (theirHits + myHits);
        }

        public String summary() {
                float[] f = features();
                return String.format(
                                "%s — wtap %.0f%% | jump %.0f%% | crit %.0f%% | aggression %.2f h/s | avgDist %.1f | postHitJump %.0f%% | postHitRetreat %.0f%% | strafe %.0f%% | flee %.0f%% | sneak %.0f%% | air %.0f%% | flips/s %.1f",
                                opponentName.isEmpty() ? "opponent" : opponentName,
                                f[0] * 100, f[2] * 100, f[3] * 100, f[4], avgDist, f[6] * 100, f[7] * 100,
                                f[8] * 100, f[9] * 100, f[10] * 100, f[11] * 100, dirChangeRate);
        }
}
