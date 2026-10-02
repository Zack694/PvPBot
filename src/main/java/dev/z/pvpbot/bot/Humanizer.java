package dev.z.pvpbot.bot;

import dev.z.pvpbot.BotConfig;
import net.minecraft.client.network.ClientPlayerEntity;

import java.util.Random;

/**
 * Max-stealth humanization layer: reaction latency with jitter, smoothed aim
 * curves with micro-noise, a hard turn-rate cap, CPS pacing and whole-count
 * sensitivity-grid rotations. Every value is randomized per decision so no
 * robotic periodicity exists in the input stream.
 *
 * v1.0.6: EASE-IN-OUT aim shaping — every correction now accelerates over its
 * first ~3 ticks (smoothstep ease-in), rides at full speed through the middle
 * and settles proportionally (ease-out is the natural proportional settle),
 * which removes the robotic constant-velocity glide AND the end-of-flick
 * stutter. Flicks are also faster: boost maxes at 2.0x and the v1.0.6 config
 * migration raises the smoothing band + turn cap.
 */
public final class Humanizer {

        private final BotConfig cfg;
        private final Random rng = new Random();
        // pending action queue: (action, ticksLeft)
        private int queuedAction = -1;
        private int queuedTicks = -1;
        private float curSmooth = 0.35f;

        public Humanizer(BotConfig cfg) {
                this.cfg = cfg;
                clickBurstLeft = 8 + rng.nextInt(9);
        }

        /**
         * Submit a policy decision. Returns the action to execute NOW (usually the
         * previous one while the new one "travels" through human reaction time).
         */
        public int submit(int action, int currentAction) {
                int delay = cfg.reactionMinTicks + rng.nextInt(Math.max(1, cfg.reactionMaxTicks - cfg.reactionMinTicks + 1));
                if (queuedAction < 0) {
                        queuedAction = action;
                        queuedTicks = delay;
                }
                int execute = currentAction;
                if (queuedTicks <= 0) {
                        execute = queuedAction;
                        queuedAction = -1;
                        queuedTicks = -1;
                } else {
                        queuedTicks--;
                }
                return execute;
        }

        public void clearQueue() {
                queuedAction = -1;
                queuedTicks = -1;
        }

        /**
         * v1.0.6 EaseInOut: tracks the current flick's progress. A NEW flick
         * (error jumps above 8°) resets the ease clock; the applied smoothing
         * fraction then ramps 35% -> 100% over ~3 ticks (smoothstep). Small
         * continuous tracking sits at full fraction. The ease-out half is the
         * proportional settle itself (fraction x error shrinks near target).
         */
        private float flickMs = 0f;
        private float lastErrMag = 0f;

        /** cfg.antiWobble, never throws. */
        private static float aw() {
                try {
                        return net.minecraft.util.math.MathHelper.clamp(dev.z.pvpbot.PvpBot.get().config().antiWobble, 0f, 1f);
                } catch (Throwable ignored) {
                        return 0.65f;
                }
        }

        /** v2.3: max fraction of the error closed per tick — never >= 1 (no overshoot),
         *  lower with anti-wobble. smooth (0.62-0.82) x flick boost (up to 2x) used to
         *  reach 1.64x: every big flick overshot and swung back. */
        private static float maxFrac() {
                return 0.92f - 0.27f * aw();
        }

        private float easeFraction(float errMag) {
                return easeFraction(errMag, 50f);
        }

        /** v2.3: the ease clock advances in MILLISECONDS — the 120 Hz aim thread
         *  used to finish the "3 tick" ramp in 3 calls (25 ms). */
        private float easeFraction(float errMag, float dtMs) {
                if (errMag > 8f && lastErrMag <= 8f) {
                        flickMs = 0f; // new flick started
                } else {
                        flickMs += dtMs;
                }
                lastErrMag = errMag;
                float t = MathHelper_clamp(flickMs / 150f, 0f, 1f);
                float easeIn = t * t * (3f - 2f * t); // smoothstep
                return 0.35f + 0.65f * easeIn;
        }

        /**
         * v1.0.5 flick boost, v1.0.6 faster: errors >12° close up to 2.0x
         * faster so a full 180° flick lands in ~120ms; small tracking is
         * untouched.
         */
        private float flickBoost(float desiredYawDelta, float desiredPitchDelta) {
                float mag = Math.max(Math.abs(desiredYawDelta), Math.abs(desiredPitchDelta));
                return 1f + 1.0f * MathHelper_clamp((mag - 12f) / 20f, 0f, 1f);
        }

        /** Smoothed, ease-in-out shaped aim step in degrees (per game tick). */
        public float[] shapeAim(ClientPlayerEntity player, float desiredYawDelta, float desiredPitchDelta) {
                if (desiredYawDelta == 0f && desiredPitchDelta == 0f) {
                        return new float[]{0f, 0f}; // v2.3.5: settled stays settled (no noise on zero)
                }
                float errMag = (float) Math.sqrt(desiredYawDelta * desiredYawDelta + desiredPitchDelta * desiredPitchDelta);
                curSmooth = cfg.aimSmoothMin + rng.nextFloat() * (cfg.aimSmoothMax - cfg.aimSmoothMin);
                float boost = flickBoost(desiredYawDelta, desiredPitchDelta);
                float ease = easeFraction(errMag);
                float k = Math.min(maxFrac(), curSmooth * boost * ease);
                float yaw = desiredYawDelta * k;
                float pitch = desiredPitchDelta * k;
                float cap = cfg.aimMaxTurnDeg;
                if (yaw > cap) yaw = cap;
                if (yaw < -cap) yaw = -cap;
                if (pitch > cap) pitch = cap;
                if (pitch < -cap) pitch = -cap;
                yaw += (rng.nextFloat() - 0.5f) * 2f * cfg.aimNoiseDeg;
                pitch += (rng.nextFloat() - 0.5f) * 2f * cfg.aimNoiseDeg;
                return new float[]{yaw, pitch};
        }

        /**
         * Frame-rate aim shaping (the 60Hz+ aim loop). Frame-rate independent:
         * the smoothing fraction and the turn cap are scaled by dt relative to
         * one game tick, so 144fps tracking is just a finer version of 60fps
         * tracking — never faster overall, only smoother.
         *
         * v2.2.0 AIM CALM (user: "still a bit wobbly even if Anti Wobble is set
         * to max") — two additions:
         *  1. The random smoothing fraction is re-rolled every few FRAMES (a
         *     60-130ms human rhythm) instead of EVERY frame — the old per-frame
         *     re-roll made the correction speed jitter frame to frame, which is
         *     itself a wobble source the anti-wobble dial now removes.
         *  2. A SIGN-FLIP OSCILLATION DAMPER: when the yaw correction flips
         *     direction 3+ times inside 250ms the correction is scaled down
         *     for 200ms — a direct damper for ANY residual oscillation loop,
         *     regardless of where it comes from.
         *
         * v2.2.1 SETTLED-ZERO PASS-THROUGH (user: "still Abit wobbly even if
         * Anti Wobble is set to max"): when the tracker above has already
         * deadzoned BOTH axes to zero the humanizer used to STILL inject the
         * random micro-noise every frame — a ±0.12 deg/frame random walk on a
         * settled crosshair, visible as permanent tiny wobble no dial could
         * remove (the noise was added AFTER the tracker's deadzone). Now a
         * zero input returns a zero output (nothing to correct = nothing to
         * jitter), and the noise amplitude itself scales down to 15% at max
         * calm. The same pass-through kills sub-0.02 deg dither on the way
         * out so the mouse never receives sub-pixel noise.
         */
        public float[] shapeAimFrame(float desiredYawDelta, float desiredPitchDelta, float dtMs) {
                float aw = 0.65f;
                try {
                        aw = net.minecraft.util.math.MathHelper.clamp(
                                        dev.z.pvpbot.PvpBot.get().config().antiWobble, 0f, 1f);
                } catch (Throwable ignored) {
                }
                // v2.2.1: the tracker already deadzoned this frame — do NOT add
                // noise on top of "nothing to correct" (the settled crosshair
                // must stay settled; this was the max-calm wobble source).
                if (desiredYawDelta == 0f && desiredPitchDelta == 0f) {
                        return new float[]{0f, 0f};
                }
                float tickFrac = MathHelper_clamp(dtMs / 50f, 0.05f, 2f);
                float errMag = (float) Math.sqrt(desiredYawDelta * desiredYawDelta + desiredPitchDelta * desiredPitchDelta);
                // v2.2.0: re-roll the smoothing fraction on a human rhythm, not
                // every frame (window grows with the anti-wobble dial)
                smoothRerollMs -= dtMs;
                if (smoothRerollMs <= 0f) {
                        curSmooth = cfg.aimSmoothMin + rng.nextFloat() * (cfg.aimSmoothMax - cfg.aimSmoothMin);
                        smoothRerollMs = 60f + 90f * (1f - aw) + rng.nextFloat() * 40f;
                }
                float boost = flickBoost(desiredYawDelta, desiredPitchDelta);
                float ease = easeFraction(errMag, dtMs);
                // v2.3: ONE smoothing application. The old code multiplied the
                // per-tick fraction by the per-frame fraction (smooth^2 per tick),
                // with boost/ease outside the exponent. Now the per-tick fraction
                // k (capped below 1: no overshoot) is converted to this frame's dt.
                float k = MathHelper_clamp(curSmooth * boost * ease, 0f, maxFrac());
                float f = 1f - (float) Math.pow(1f - k, tickFrac);
                float yaw = desiredYawDelta * f;
                float pitch = desiredPitchDelta * f;
                // v2.2.0 SIGN-FLIP OSCILLATION DAMPER — 3+ direction flips of the
                // yaw correction inside 250ms = an oscillation loop; damp the
                // correction for the next ~200ms so it settles instead of buzzing
                long nowMs = System.currentTimeMillis();
                float yDir = Math.signum(yaw);
                if (yDir != 0f) {
                        if (lastYawDir != 0f && yDir != lastYawDir) {
                                if (nowMs - lastFlipMs < 250) {
                                        flipCount++;
                                } else {
                                        flipCount = 1;
                                }
                                lastFlipMs = nowMs;
                                lastYawDir = yDir;
                                if (flipCount >= 3) {
                                        damperUntilMs = nowMs + 200;
                                }
                        } else {
                                lastYawDir = yDir;
                                if (nowMs - lastFlipMs > 400) flipCount = 0;
                        }
                }
                if (nowMs < damperUntilMs) {
                        yaw *= 0.55f;
                        pitch *= 0.55f;
                }
                float cap = cfg.aimMaxTurnDeg * tickFrac;
                if (yaw > cap) yaw = cap;
                if (yaw < -cap) yaw = -cap;
                if (pitch > cap) pitch = cap;
                if (pitch < -cap) pitch = -cap;
                // v2.2.1: noise amplitude shrinks with the anti-wobble dial
                // (15% survives at max calm so the motion never looks locked)
                float noiseScale = (1f - 0.85f * aw) * tickFrac;
                yaw += (rng.nextFloat() - 0.5f) * 2f * cfg.aimNoiseDeg * noiseScale;
                pitch += (rng.nextFloat() - 0.5f) * 2f * cfg.aimNoiseDeg * noiseScale;
                // v2.2.1: sub-0.02 deg output dither is below any mouse pixel —
                // drop it instead of feeding the cursor pipeline micro-jitter
                if (Math.abs(yaw) < 0.02f) yaw = 0f;
                if (Math.abs(pitch) < 0.02f) pitch = 0f;
                return new float[]{yaw, pitch};
        }

        // v2.2.0 aim-calm state
        private float smoothRerollMs = 0f;
        private float lastYawDir = 0f;
        private long lastFlipMs = 0L;
        private int flipCount = 0;
        private long damperUntilMs = 0L;

        private static float MathHelper_clamp(float v, float min, float max) {
                return v < min ? min : Math.min(v, max);
        }

        public boolean attackGateAllowed(int ticksSinceLastAttack) {
                // v1.0.9: min gap raised 1 -> 2 ticks (+jitter). The vanilla
                // charge band (checked by the controller) is the real pacing;
                // this is only the anti-double-click guard, but a 1-tick gap let
                // a low band threshold produce audible machine-gun double clicks.
                return ticksSinceLastAttack >= Math.max(2, cfg.attackMinIntervalTicks) + rng.nextInt(2);
        }

        private int clickGapTicks = 2;
        private int clickBurstLeft = 12;

        /** v1.0.10: called at every episode/respawn boundary — burst pacing
         * state never bleeds across rounds. */
        public void resetPacing() {
                clickGapTicks = 2;
                clickBurstLeft = 12;
        }

        /**
         * Classic (1.8-style) server pacing — v1.0.9 rework: HUMAN CLICK
         * BURSTS, not a constant machine-gun. The old fixed 2-4 tick gap
         * clicked at one relentless rate from the first second to the last —
         * exactly the "spams left click" look. Now the rhythm is situational:
         * an active trade rides a fast jitter band (2-3 ticks ≈ 7-10 CPS),
         * neutral probing is calmer (3-5 ticks ≈ 4-7 CPS), and every 8-16
         * clicks the pace takes a short 250-400ms "breath" — the cadence real
         * players naturally fall into, with nothing a statistician or an
         * anticheat can latch onto as a fixed rate.
         */
        public boolean clickPaceAllowed(int ticksSinceLastAttack, boolean activeTrade) {
                if (ticksSinceLastAttack < clickGapTicks) return false;
                if (--clickBurstLeft <= 0) {
                        // burst over — a short pause resets the rhythm
                        clickBurstLeft = 8 + rng.nextInt(9);
                        clickGapTicks = 5 + rng.nextInt(4); // 250-400ms breath
                        return true;
                }
                clickGapTicks = activeTrade ? 2 + rng.nextInt(2) : 3 + rng.nextInt(3);
                return true;
        }

        public float nextSmooth() {
                return cfg.aimSmoothMin + rng.nextFloat() * (cfg.aimSmoothMax - cfg.aimSmoothMin);
        }
}
