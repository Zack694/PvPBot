package dev.z.pvpbot.bot;

import dev.z.pvpbot.ml.NeuralNet;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.Random;

/**
 * Learned aiming with an corrective assist layer (hybrid by design):
 *
 *  1. A "wander point" DRIFTS across the opponent's WHOLE hitbox — head,
 *     shoulders, chest, legs, anywhere — with per-fight random phases, like
 *     a human whose crosshair roams the body instead of locking one pixel.
 *  2. The 12->64->64->2 network predicts the yaw/pitch correction that puts
 *     the crosshair on that point ~3 ticks from now, from the opponent's
 *     motion features — it leads runners and adapts to each opponent's
 *     strafing style.
 *  3. The correction actually applied each tick is a BLEND: network
 *     prediction + a corrective assist that pulls toward the live measured
 *     error of the wander point (assist dominates, stronger at point-blank;
 *     the net always contributes and keeps training every tick toward the
 *     same labels, so its own skill keeps improving online).
 *  4. Between the blend and the mouse sits the humanizer (rate-limit +
 *     smoothing + micro-noise) — the same shaping every other action goes
 *     through. Nothing here snaps or teleports the camera.
 *
 * v2.2.0 AIM CALM (user: "still a bit wobbly even if Anti Wobble is set to
 * max" + "both models aim at nothing for a while while the target is either
 * in the Left or Right side") — three structural fixes:
 *
 *  A. TICK-GATED MOTION STATS: noteTargetMotion and the strafe history were
 *     built for 20Hz tick calls, but since the 120Hz aim thread they ran
 *     EVERY FRAME — the 6-sample acceleration estimator then spanned only
 *     ~50ms instead of 300ms, so every velocity quantization step became a
 *     fake acceleration spike and the lead point jumped sideways into empty
 *     air several times a second. That was the wobble AND the "aims at
 *     nothing next to the target". Both estimators now update once per game
 *     tick and the acceleration is EMA-smoothed on top.
 *
 *  B. ON-BODY CLAMP: the final aim point (lead + wander combined) is now
 *     clamped INSIDE the target's bounding box expanded by a small margin —
 *     the same structural approach that fixed "aims above the head" for the
 *     vertical axis. A point that can never leave the body means the
 *     crosshair can never orbit empty air next to the target, no matter
 *     what the lead, the wander or the net do. The horizontal lead is also
 *     gated OFF at large yaw errors (target on the left/right screen edge):
 *     first come ON the body, THEN lead runners.
 *
 *  C. ANTI-WOBBLE DIAL (cfg.antiWobble 0-1): scales the measured-error EMA
 *     time constant, the micro-deadzone, the wander sway amplitude and the
 *     net's yaw authority clamp. The measured error is exponentially
 *     smoothed in TIME (frame-rate independent) so the crosshair chases a
 *     calm estimate instead of every per-frame jitter of the wander point.
 *
 *  D. v2.2.1 LARGE-ERROR AUTHORITY FLOOR (user: "both models aim at nothing
 *     for a while while the target is either in the Left or Right side"):
 *     when the angular error passes 25 deg the MEASURED error owns the
 *     correction (assist >= 0.85) EVEN IF the user disabled the aim-assist
 *     blend — an untrained/net-only blend used to contribute only the
 *     clamped net guess (±12 deg yaw), so the crosshair sat beside the
 *     target "aiming at nothing". Fine tracking (errors < 10 deg) still
 *     honors the assist toggle exactly as configured; only big turns are
 *     guaranteed to converge onto the body. This is a structural guarantee,
 *     not an assist: no trained state can make the crosshair abandon a
 *     target it can see.
 */
public final class AimController {

        public static final int IN = 12;
        public static final int[] ARCH = {IN, 64, 64, 2};

        private final NeuralNet net;
        private final Random rng = new Random(4242);
        private final float[] lastFeatures = new float[IN];
        private boolean hasPending = false;
        private long pendingTick;

        // per-fight wander phases — every fight tracks differently
        private float phase1, phase2, phase3;

        // recent target motion history for features
        private final float[] strafeHist = new float[6];
        private int histIdx = 0;

        // v2.2.0: tick gating for the motion estimators (they must see GAME
        // ticks, not 120Hz frames) + EMA-smoothed acceleration
        private long lastMotionTick = Long.MIN_VALUE;
        private long lastStrafeTick = Long.MIN_VALUE;
        private float accEmaX = 0f, accEmaZ = 0f;

        // v2.2.0: time-domain error EMA (the anti-wobble core)
        private float errEmaYaw = 0f, errEmaPit = 0f;
        private boolean errEmaInit = false;
        private float lastTimeSec = -1f;

        // supervised sample queue: features -> label arrives 3 ticks later
        private static final class Sample {
                float[] x; float[] y;
        }

        private final ArrayDeque<Sample> samples = new ArrayDeque<>(256);
        private final ArrayDeque<PendingLabel> pending = new ArrayDeque<>(64);
        private int sinceTrain = 0;
        public float lastLoss = Float.NaN;

        private static final class PendingLabel {
                float[] x; long tick;
        }

        public AimController(NeuralNet net) {
                this.net = net;
                randomizeWander();
        }

        public NeuralNet net() {
                return net;
        }

        /** cfg.antiWobble, read defensively (never throws). */
        private static float antiWobble() {
                try {
                        return MathHelper.clamp(
                                        dev.z.pvpbot.PvpBot.get().config().antiWobble, 0f, 1f);
                } catch (Throwable ignored) {
                        return 0.65f;
                }
        }

        /**
         * The drifting aim point in world space.
         *
         * v1.0.9 HEAD PRIORITY (user: "It aims at the leg or feet like 70% of
         * the time... PRIORITIZE AIMING AT THE HEAD YAW OR PITCH"): the wander
         * is locked to the HEAD zone — 0.75..0.93 of the hitbox height (chin
         * to crown) instead of the old 0.11..0.91 full-body sweep that spent
         * most of its time below the chest. The drift is still a sum of two
         * smooth sines with per-fight random phases (never a locked pixel).
         *
         * KNOCKBACK RULE (user: "if the Opponent is Above or Like Getting Kbed
         * then Continue aiming Forward"): while they are in hurt/knockback
         * frames the vertical wander is FROZEN at mid-head so the crosshair
         * stops chasing the bobbing hitbox — yaw keeps tracking (forward).
         * When they are well ABOVE us, the point tightens onto the head.
         *
         * Horizontal wander shrinks with range (full body sway at point-blank,
         * near-center mass at 7+ blocks — the "aims at nothing" fix).
         *
         * v2.2.0: the horizontal sway amplitude scales with the anti-wobble
         * dial (wanderScale = 1 - 0.55*antiWobble) — the sway is the main
         * side-to-side wobble source at maximum calm.
         */
        private Vec3d wanderAimPoint(ClientPlayerEntity self, LivingEntity t, long tick, float wanderScale) {
                float time = (float) (tick % 200000L) / 20f;
                boolean headPriority = true;
                boolean knocked = t.hurtTime > 2;
                boolean aboveUs = t.getY() - self.getY() > 1.5;
                int zone = 0;
                try {
                        headPriority = dev.z.pvpbot.PvpBot.get().config().aimHeadPriority;
                        zone = dev.z.pvpbot.PvpBot.get().config().aimZone;
                } catch (Throwable ignored) {
                }
                float frac;
                if (zone >= 1) {
                        // v1.0.10 STRAIGHT ZONES (user: "Aim Straight like in the neck
                        // or chest") — a CONSTANT height fraction, no vertical wander:
                        // 1 = Eyes (0.90), 2 = Neck (0.80), 3 = Chest (0.63). A tiny
                        // 0.008 breathing keeps it from looking robotic.
                        frac = zone == 1 ? 0.90f : zone == 2 ? 0.80f : 0.63f;
                        frac += 0.008f * MathHelper.sin(time * 1.15f + phase1);
                } else if (!headPriority) {
                        // legacy full-body wander
                        frac = 0.51f + 0.30f * MathHelper.sin(time * 1.15f + phase1)
                                        + 0.10f * MathHelper.sin(time * 2.9f + phase2);
                } else if (knocked) {
                        frac = 0.85f; // KB: frozen mid-head, aim forward, no vertical chase
                } else if (aboveUs) {
                        frac = 0.90f; // above us: pinned to the head
                } else {
                        frac = 0.84f + 0.06f * MathHelper.sin(time * 1.15f + phase1)
                                        + 0.03f * MathHelper.sin(time * 2.9f + phase2);
                }
                // horizontal sway scales with range: full within ~4 blocks,
                // shrinking to a sliver past 7 (no more swinging at empty air)
                float distScale = distTo(self, t) <= 4f
                                ? (float) Math.max(0.35, distTo(self, t) / 4.0)
                                : Math.max(0.15f, 1f - (float) (distTo(self, t) - 4.0) / 4.0f);
                float ampX = (!headPriority ? 0.13f : 0.10f) * distScale * wanderScale;
                if (knocked) ampX *= 0.5f;
                float wanderX = ampX * MathHelper.sin(time * 1.05f + phase2)
                                + (0.05f * distScale * wanderScale) * MathHelper.sin(time * 2.6f + phase3);
                double ay = t.getY() + t.getHeight() * frac;
                float yawRad = (float) Math.toRadians(self.getYaw());
                float rx = -MathHelper.cos(yawRad), rz = -MathHelper.sin(yawRad); // my right
                double ax = t.getX() + rx * wanderX;
                double az = t.getZ() + rz * wanderX;
                return new Vec3d(ax, ay, az);
        }

        private static double distTo(ClientPlayerEntity self, LivingEntity t) {
                double dx = t.getX() - self.getX(), dz = t.getZ() - self.getZ();
                return Math.sqrt(dx * dx + dz * dz);
        }

        /**
         * v1.0.9c HARD INVARIANT (user: "It literally Aims ABOVE the Player"):
         * the final aim height is clamped INSIDE the target's CURRENT hitbox —
         * [feet + 0.25, feet + height - 0.15]. No combination of vertical
         * lead, jump pre-aim lift, wander frac or stale net output can ever
         * put the crosshair above their head (or at their feet) again — the
         * same structural approach that killed the click spam. The top margin
         * (0.15) keeps the point on the solid part of the skull; the bottom
         * margin (0.25) keeps it off the shins.
         */
        private static double clampAimY(LivingEntity t, double y) {
                double hi = t.getY() + t.getHeight() - 0.15;
                double lo = t.getY() + 0.25;
                return Math.max(lo, Math.min(hi, y));
        }

        /**
         * v1.0.6 AIM PREDICTION — quadratic lead. v1.0.5 led with velocity
         * only; a strafing/circling target accelerates SIDEWAYS, so a linear
         * lead still trails the arc. We now estimate the target's acceleration
         * from a 6-tick velocity history and lead with pos + v*t + ½at².
         * Toggle: cfg.aimPredict.
         */
        private final float[] vxHist = new float[6];
        private final float[] vzHist = new float[6];
        private int velHistIdx = 0;
        private boolean velHistFull = false;
        private float accX = 0f, accZ = 0f;

        /**
         * v2.2.0: velocity/acceleration sampling now happens ONCE PER GAME
         * TICK (the 120Hz aim thread used to call this every frame — a
         * 6-sample window then covered 50ms instead of 300ms and every
         * velocity quantization step looked like a huge acceleration). The
         * instantaneous acceleration is also EMA-smoothed (0.35 alpha) so a
         * single weird tick can no longer fling the lead point sideways.
         */
        private void noteTargetMotion(LivingEntity t, long tick) {
                if (tick == lastMotionTick) return; // same game tick — keep the estimate
                lastMotionTick = tick;
                vxHist[velHistIdx] = (float) TargetMotion.of(t).x;
                vzHist[velHistIdx] = (float) TargetMotion.of(t).z;
                velHistIdx = (velHistIdx + 1) % vxHist.length;
                if (velHistIdx == 0) velHistFull = true;
                if (velHistFull) {
                        // acceleration per tick between the oldest and newest sample
                        int oldest = velHistIdx; // just-overwritten = oldest
                        int newest = (velHistIdx + vxHist.length - 1) % vxHist.length;
                        float instX = (vxHist[newest] - vxHist[oldest]) / (vxHist.length - 1);
                        float instZ = (vzHist[newest] - vzHist[oldest]) / (vzHist.length - 1);
                        accEmaX += 0.35f * (instX - accEmaX);
                        accEmaZ += 0.35f * (instZ - accEmaZ);
                        accX = accEmaX;
                        accZ = accEmaZ;
                }
        }

        /**
         * v1.0.5 velocity lead, v1.0.6 quadratic, v1.0.9 VERTICAL lead + range
         * cap: the error is measured against where the target will BE in
         * aimLeadTicks — pos + v*t + ½at² — not where it is now. The lead now
         * covers Y too (a jumping target's head rises — aiming at its feet
         * after the jump was the "aims at the feet" complaint), and the whole
         * lead vector is capped at 0.35 * distance so point-blank fights never
         * aim behind a strafing target.
         *
         * v1.0.9c VERTICAL LEAD FIXED — this was the "aims ABOVE the player"
         * bug. A raw vy * lead ignores gravity: at the start of every jump
         * AND every knockback launch vy = 0.42, so vy * 2 pushed the aim
         * point +0.84 blocks above their feet ON TOP of the head offset
         * (1.51) — half a block over their skull, exactly when players are
         * airborne the most. Now the rise is gravity-aware
         * (vy*t - ½·0.08·t², MC gravity 0.08/tick²) and hard-capped at
         * +0.40 / -0.60 blocks; while they are in KB frames the vertical
         * lead is ZERO (user rule: "Getting Kbed -> continue aiming Forward"
         * — the old hurtTime freeze only stopped the wander, not the lead).
         */
        private Vec3d leadPoint(LivingEntity t, double myDist) {
                int lead = 0;
                boolean predict = true;
                try {
                        dev.z.pvpbot.BotConfig c = dev.z.pvpbot.PvpBot.get().config();
                        lead = c.aimLeadTicks;
                        predict = c.aimPredict;
                } catch (Throwable ignored) {
                        lead = 2;
                }
                float vx = (float) TargetMotion.of(t).x, vz = (float) TargetMotion.of(t).z;
                float vy = (float) TargetMotion.of(t).y;
                if (lead <= 0) return new Vec3d(t.getX(), t.getY(), t.getZ());
                float lf = lead;
                float px, pz;
                if (predict) {
                        px = (float) t.getX() + vx * lf + 0.5f * accX * lf * lf;
                        pz = (float) t.getZ() + vz * lf + 0.5f * accZ * lf * lf;
                } else {
                        px = (float) t.getX() + vx * lead;
                        pz = (float) t.getZ() + vz * lead;
                }
                // range cap on the horizontal lead (blocks): never aim further
                // ahead than a third of the gap — point-blank lead = aiming at air
                float leadX = px - (float) t.getX(), leadZ = pz - (float) t.getZ();
                float leadMag = (float) Math.sqrt(leadX * leadX + leadZ * leadZ);
                float cap = (float) (0.35 * Math.max(0.8, myDist));
                if (leadMag > cap && leadMag > 1e-4f) {
                        float k = cap / leadMag;
                        px = (float) t.getX() + leadX * k;
                        pz = (float) t.getZ() + leadZ * k;
                }
                // v1.0.9c gravity-aware, KB-zeroed, capped vertical lead
                float vlead;
                if (t.hurtTime > 2) {
                        vlead = 0f; // KB: aim forward, never chase the launch
                } else {
                        vlead = vy * lf - 0.5f * 0.08f * lf * lf;
                        if (vlead > 0.40f) vlead = 0.40f;
                        if (vlead < -0.60f) vlead = -0.60f;
                }
                float py = (float) t.getY() + vlead;
                return new Vec3d(px, py, pz);
        }

        /** @return desired yaw/pitch delta in degrees this tick (raw — humanizer shapes it) */
        public float[] aimStep(ClientPlayerEntity self, LivingEntity target, OpponentMemory opp, long tick) {
                return aimStepTime(self, target, opp, (float) (tick % 200000L) / 20f);
        }

        /**
         * v2.2.0: THE single aim-point pipeline, shared by the 120Hz frame
         * path, the 20Hz tick path AND the training labels — wander + lead +
         * sneak/jump shaping + lead gate + on-body clamp, so the camera and
         * the labels can never disagree about where the crosshair should go.
         *
         * @param opp nullable — when present, jump/sneak prediction shapes the point
         */
        private Vec3d finalAimPoint(ClientPlayerEntity self, LivingEntity target, long tick,
                                    float wanderScale, OpponentMemory opp) {
                float aw = antiWobble();
                Vec3d ap = wanderAimPoint(self, target, tick, wanderScale);
                Vec3d lp = leadPoint(target, distTo(self, target));

                // v2.2.0 LEAD GATE — when the target sits at a large yaw error
                // (left/right screen edge, knockback turn, hard flick), the lead
                // vector points even FURTHER off-body: the crosshair then orbits
                // empty air next to the target instead of coming onto it ("aims
                // at nothing for a while"). The lead fades out above 30° of
                // provisional yaw error and is fully gone past ~70°.
                double pdx = target.getX() - self.getX(), pdz = target.getZ() - self.getZ();
                float pBearing = (float) Math.toDegrees(Math.atan2(-pdx, pdz));
                float pYawErr = Math.abs(MathHelper.wrapDegrees(pBearing - self.getYaw()));
                float leadGate = 1f - MathHelper.clamp((pYawErr - 30f) / 40f, 0f, 1f);
                if (leadGate < 1f) {
                        lp = new Vec3d(target.getX() + (lp.x - target.getX()) * leadGate,
                                        lp.y, target.getZ() + (lp.z - target.getZ()) * leadGate);
                }

                double jumpLift = 0f;
                float dist = (float) distTo(self, target);
                if (opp != null && dist < 4.5f) {
                        float jumpP = opp.predictJumpNext();
                        if (target.isOnGround() && jumpP > 0.3f) {
                                // pre-aim above a likely jumper — capped against the TRUE
                                // clamped top (height - 0.15, not 0.97): the old cap assumed
                                // the vy-lead was 0 and the two stacked into above-the-head
                                double maxLift = Math.max(0.0,
                                                target.getY() + target.getHeight() - 0.15 - ap.y);
                                jumpLift = Math.min(0.30f * jumpP, maxLift);
                        }
                        float sneakP = opp.predictSneakNext();
                        if (sneakP > 0.2f) {
                                // v1.0.9 SNEAK-HIT PREDICTION: a target that is about to
                                // shift barely moves and its head sits lower — shrink the
                                // lead and pull the aim point down toward the sneak head
                                float k = 1f - 0.8f * Math.min(1f, sneakP);
                                lp = new Vec3d(target.getX() + (lp.x - target.getX()) * k,
                                                lp.y, target.getZ() + (lp.z - target.getZ()) * k);
                                jumpLift -= 0.22f * sneakP;
                        }
                }

                double aimY = clampAimY(target, lp.y + (ap.y - target.getY()) + jumpLift);
                double ax = lp.x + (ap.x - target.getX());
                double az = lp.z + (ap.z - target.getZ());

                // v2.2.0 ON-BODY CLAMP — the horizontal counterpart of clampAimY:
                // the combined point can never leave the target's bounding box
                // (expanded by a small margin that shrinks with anti-wobble).
                // Structurally impossible to aim at nothing.
                double margin = 0.35 - 0.20 * aw;
                Box bb = target.getBoundingBox();
                ax = Math.max(bb.minX - margin, Math.min(bb.maxX + margin, ax));
                az = Math.max(bb.minZ - margin, Math.min(bb.maxZ + margin, az));
                return new Vec3d(ax, aimY, az);
        }

        /**
         * Frame-rate variant: the wander point and error are evaluated against
         * continuous wall-clock time so the 60Hz+ aim loop tracks the SAME
         * drifting point the tick-rate loop would — just sampled more finely.
         */
        public float[] aimStepTime(ClientPlayerEntity self, LivingEntity target, OpponentMemory opp, float timeSeconds) {
                long tick = (long) (timeSeconds * 20f);
                float aw = antiWobble();
                // v2.2.1: the sway shrank to 45% at max calm and was STILL the
                // largest visible side-to-side wobble — now 0.85 of it is removed
                // (a ±0.02-block sliver remains, so it never looks locked).
                float wanderScale = 1f - 0.85f * aw;
                // features describe the opponent's motion (what a human reads)
                Vec3d tgtVel = TargetMotion.of(target);
                noteTargetMotion(target, tick); // v2.2.0: tick-gated + EMA'd accel
                float yawRad = (float) Math.toRadians(self.getYaw());
                float fx = -MathHelper.sin(yawRad), fz = MathHelper.cos(yawRad);
                float rx = -fz, rz = fx;
                double dx = target.getX() - self.getX(), dz = target.getZ() - self.getZ();
                double dist = Math.sqrt(dx * dx + dz * dz);
                float theirVelFwd = (float) (tgtVel.x * fx + tgtVel.z * fz);   // toward my facing
                float theirVelStr = (float) (tgtVel.x * rx + tgtVel.z * rz);

                // angular error to the WANDER point — the thing the crosshair chases.
                // v1.0.9: the point is evaluated at the LED position (X AND Y) and
                // gets a jump-probability pre-aim lift: when the memory says this
                // opponent is about to jump, the crosshair already drifts up to
                // where their head will be ("predict from the opponent's memory").
                // v2.2.0: one shared pipeline (wander + lead + sneak/jump + gates)
                Vec3d aimPoint = finalAimPoint(self, target, tick, wanderScale, opp);
                double adx = aimPoint.x - self.getX();
                double adz = aimPoint.z - self.getZ();
                float adist = (float) Math.sqrt(adx * adx + adz * adz);
                float bearing = (float) Math.toDegrees(Math.atan2(-adx, adz));
                float yawErr = MathHelper.wrapDegrees(bearing - self.getYaw());
                float dy = (float) (aimPoint.y - self.getEyePos().y);
                float targetPitch = (float) Math.toDegrees(Math.atan2(-dy, Math.max(0.1f, adist)));
                float pitchErr = MathHelper.wrapDegrees(targetPitch - self.getPitch());

                // v2.2.0 ANTI-WOBBLE ERROR EMA — the measured error is smoothed in
                // the TIME domain (frame-rate independent exponential), so the
                // crosshair chases a calm estimate of the error instead of every
                // per-frame jitter of the moving wander point / noisy accel. At
                // antiWobble=1 the time constant is ~50ms — still fast enough to
                // track real strafes (a strafe changes the error smoothly), but it
                // erases the per-frame zigzag completely.
                float dtSec = lastTimeSec < 0f ? 0.05f : MathHelper.clamp(timeSeconds - lastTimeSec, 0.001f, 0.25f);
                lastTimeSec = timeSeconds;
                float tau = 0.008f + 0.042f * aw; // 8ms (raw) .. 50ms (max calm)
                float alpha = 1f - (float) Math.exp(-dtSec / tau);
                if (!errEmaInit) {
                        errEmaYaw = yawErr;
                        errEmaPit = pitchErr;
                        errEmaInit = true;
                } else {
                        errEmaYaw += alpha * (yawErr - errEmaYaw);
                        errEmaPit += alpha * (pitchErr - errEmaPit);
                }
                float smYawErr = errEmaYaw, smPitchErr = errEmaPit;

                // v2.2.0: strafe history is ALSO tick-gated (it fed the net a
                // variance measured over 50ms of frames — meaningless noise)
                int theirAir = target.isOnGround() ? 0 : (tgtVel.y < 0 ? -1 : 1);
                if (tick != lastStrafeTick) {
                        lastStrafeTick = tick;
                        strafeHist[histIdx++ % strafeHist.length] = theirVelStr;
                }
                float mean = 0f;
                for (float v : strafeHist) mean += v;
                mean /= strafeHist.length;
                float var = 0f;
                for (float v : strafeHist) var += (v - mean) * (v - mean);
                var /= strafeHist.length;

                float[] f = lastFeatures;
                f[0] = MathHelper.clamp(theirVelFwd / 0.35f, -1.5f, 1.5f);
                f[1] = MathHelper.clamp(theirVelStr / 0.35f, -1.5f, 1.5f);
                f[2] = MathHelper.clamp((float) tgtVel.y, -1f, 1f);
                f[3] = theirAir;
                f[4] = MathHelper.clamp((float) dist / 6f, 0f, 2f);
                f[5] = smYawErr / 180f;
                f[6] = MathHelper.clamp(smPitchErr / 90f, -1f, 1f);
                f[7] = var;
                f[8] = mean;
                f[9] = opp.features()[0]; // their sprint-reset frequency (their movement style)
                f[10] = theirVelFwd > 0.2f ? 1f : 0f;
                f[11] = 1f; // bias

                // enqueue for future labeling (v2.0: the aim thread may be the
                // caller — the queue is shared with the game thread's learnStep)
                PendingLabel pl = new PendingLabel();
                pl.x = f.clone();
                pl.tick = tick;
                synchronized (pending) {
                        pending.addLast(pl);
                        while (pending.size() > 32) pending.removeFirst();
                }

                float[] out = net.forward(f, null);
                float predYawErr = MathHelper.clamp(out[0], -1f, 1f) * 180f;
                float predPitchErr = MathHelper.clamp(out[1], -1f, 1f) * 90f;

                // corrective assist (by design, not a bypass): blend the net's
                // prediction with the live measured error toward the wander point.
                // Strength is user-configurable (0 = pure net). Assist is a touch
                // stronger at point-blank where precision matters most; the net
                // still shapes every correction and keeps learning.
                float assistBase = 0f;
                try {
                        dev.z.pvpbot.BotConfig c = dev.z.pvpbot.PvpBot.get().config();
                        if (c.aimAssistEnabled) assistBase = c.aimAssistStrength;
                } catch (Throwable ignored) {
                        assistBase = 0.70f;
                }
                // v1.0.6: config limit widened to 0-3.0 — a blend above 1.0 would
                // overshoot past the point, so it clamps at 1.0 here
                assistBase = MathHelper.clamp(assistBase, 0f, 1f);
                float angErr = (float) Math.sqrt(yawErr * yawErr + pitchErr * pitchErr);
                // v1.0.9: small errors get the point-blank precision boost; LARGE
                // errors (>25°, e.g. the target just hard-flicked or got knocked)
                // trust the measured error, not the net's stale guess — that stale
                // guess is what made the bot sometimes "aim at nothing".
                // v2.2.1: the large-error floor is UNCONDITIONAL — with the assist
                // blend disabled the old code left assist at 0 and the clamped net
                // guess (±12 deg) was the ONLY yaw driver, so a side target sat
                // uncorrected beside the crosshair ("aims at nothing for a while",
                // BOTH models). Big turns always converge now; small-error tracking
                // still honors the user's assist setting exactly.
                float assist = assistBase;
                if (angErr < 10f) {
                        assist = Math.max(assist, Math.min(0.95f, assistBase + 0.13f));
                } else if (angErr > 25f) {
                        assist = Math.max(assist, 0.85f);
                }
                float outYaw = (1f - assist) * predYawErr + assist * smYawErr;
                // v1.0.9c: the net's PITCH influence is hard-bounded to ±8° —
                // it trained on the old above-the-head labels and learned an
                // upward bias; the measured error now owns the vertical axis
                // (yaw keeps full net authority for leading runners)
                float netPitch = MathHelper.clamp((1f - assist) * predPitchErr, -8f, 8f);
                // v2.2.0: the net's YAW influence is now bounded too (±12°) — a
                // stale/overtrained prediction pushed up to ±27° of wrong-side
                // authority at large errors (0.15 * ±180) and that pushed the
                // crosshair OFF the body exactly when the target crossed to the
                // left/right. The measured error stays the owner of large corrections.
                float netYaw = MathHelper.clamp((1f - assist) * predYawErr, -12f, 12f);
                float outPitch = netPitch + assist * smPitchErr;

                float desiredPitch = MathHelper.clamp(self.getPitch() + outPitch, -89f, 89f);
                float yawDelta = MathHelper.wrapDegrees(outYaw);
                float pitchDelta = MathHelper.wrapDegrees(desiredPitch - self.getPitch());
                // v2.0.2 ANTI-BUZZ DEADZONE, v2.2.0 per-axis + anti-wobble scaled:
                // sub-threshold residuals sit an order of magnitude inside the
                // hitbox's angular tolerance at combat range (a 0.6-wide bbox at
                // 2 blocks spans ~17 deg) — chasing them only adds visible
                // micro-jitter around the aim point. Each axis zeroes INDEPENDENTLY
                // now (the old version required BOTH axes to be tiny before ANY
                // zeroing, so a 0.2° pitch buzz survived a 5° yaw correction).
                float deadzone = 0.32f + 0.43f * aw; // 0.32 raw .. 0.75 max calm
                if (Math.abs(yawDelta) < deadzone) yawDelta = 0f;
                if (Math.abs(pitchDelta) < deadzone) pitchDelta = 0f;
                if (yawDelta == 0f && pitchDelta == 0f) {
                        return new float[]{0f, 0f};
                }
                return new float[]{yawDelta, pitchDelta};
        }

        /** Label pending samples and train once in a while. */
        public void learnStep(ClientPlayerEntity self, LivingEntity target, long tick) {
                if (target == null) return;
                // resolve labels: the correction that WOULD have been right 3 ticks ago,
                // measured against the SAME final aim point the crosshair was chasing
                // (v2.2.0: shared pipeline — labels and camera can never disagree)
                synchronized (pending) {
                        while (!pending.isEmpty() && tick - pending.peekFirst().tick >= 3) {
                                PendingLabel pl = pending.pollFirst();
                                Vec3d aimPoint = finalAimPoint(self, target, pl.tick, 1f - 0.85f * antiWobble(), null);
                                double adx = aimPoint.x - self.getX();
                                double adz = aimPoint.z - self.getZ();
                                float adist = (float) Math.sqrt(adx * adx + adz * adz);
                                float bearing = (float) Math.toDegrees(Math.atan2(-adx, adz));
                                float actualYawErr = MathHelper.wrapDegrees(bearing - self.getYaw()) / 180f;
                                float dy = (float) (aimPoint.y - self.getEyePos().y);
                                float targetPitch = (float) Math.toDegrees(Math.atan2(-dy, Math.max(0.1f, adist)));
                                float actualPitchErr = MathHelper.wrapDegrees(targetPitch - self.getPitch()) / 90f;
                                Sample s = new Sample();
                                s.x = pl.x;
                                s.y = new float[]{MathHelper.clamp(actualYawErr, -1f, 1f), MathHelper.clamp(actualPitchErr, -1f, 1f)};
                                if (samples.size() < 512) samples.addLast(s);
                        }
                }
                // v1.0.9: train MORE (user: "train model more to correct those
                // aimings") — every 6 ticks instead of 8, batch 10 instead of 8
                if (++sinceTrain >= 6 && samples.size() >= 32) {
                        sinceTrain = 0;
                        // minibatch
                        int n = 10;
                        float[][] xs = new float[n][], ys = new float[n][];
                        for (int i = 0; i < n; i++) {
                                Sample s = samples.stream().skip(rng.nextInt(samples.size())).findFirst().orElse(null);
                                if (s == null) break;
                                xs[i] = s.x;
                                ys[i] = s.y;
                        }
                        lastLoss = net.trainBatch(xs, ys, 0.001f);
                }
                hasPending = false;
        }

        public void resetFight() {
                pending.clear();
                histIdx = 0;
                hasPending = false;
                // v2.2.0: calm state starts fresh per fight
                errEmaInit = false;
                errEmaYaw = 0f;
                errEmaPit = 0f;
                lastTimeSec = -1f;
                lastMotionTick = Long.MIN_VALUE;
                lastStrafeTick = Long.MIN_VALUE;
                accEmaX = 0f;
                accEmaZ = 0f;
                velHistIdx = 0;
                velHistFull = false;
                accX = 0f;
                accZ = 0f;
                randomizeWander();
        }

        private void randomizeWander() {
                phase1 = rng.nextFloat() * (float) (Math.PI * 2.0);
                phase2 = rng.nextFloat() * (float) (Math.PI * 2.0);
                phase3 = rng.nextFloat() * (float) (Math.PI * 2.0);
        }
}
