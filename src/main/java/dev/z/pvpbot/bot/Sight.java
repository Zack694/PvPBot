package dev.z.pvpbot.bot;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * v2.0 PHASE 1 — SIGHT VECTOR (indices 64..83 of the 84-dim observation).
 *
 * These are the four "sight" feature families the reference bot emphasized,
 * computed from data the client already holds (microseconds per tick):
 *
 *   1. CROSSHAIR-RELATIVE GEOMETRY — angular error from the crosshair to the
 *      opponent (deg), the projected bounding-box angular width and the
 *      top/bottom edge elevations. The policy learns WHERE it is pointing,
 *      not just where the enemy is; the click head reads bbox size to
 *      predict hit probability.
 *   2. LINE OF SIGHT — a cheap raycast eye->target, so walls/cover become a
 *      learnable feature instead of a surprise.
 *   3. TIME SIGNALS — time-since-last-hit (mine and theirs) at fine scales.
 *   4. ACTION HISTORY BUFFERS — the last 8 own decisions (attack/jump/sprint/
 *      move bias) and the last 20 ticks of the opponent's swing/airborne/
 *      sneak state, so the policy feels RHYTHM instead of only the
 *      instantaneous frame.
 *
 * Everything is a measurement. No strategy lives here.
 */
public final class Sight {

        public static final int DIM = 20; // appended after Perception's 64

        // ---- own action history (last 8 decisions, ticked by BotController) ----
        private final boolean[] hAtk = new boolean[8];
        private final boolean[] hJump = new boolean[8];
        private final boolean[] hSpr = new boolean[8];
        private final int[] hMove = new int[8];
        private int hIdx = 0;
        private int hFilled = 0;

        // ---- opponent state history (last 20 ticks, ticked by BotController) ----
        private final boolean[] tSwing = new boolean[20];
        private final boolean[] tAir = new boolean[20];
        private final boolean[] tSneak = new boolean[20];
        private int tIdx = 0;
        private int tFilled = 0;
        private float theirPitchPrev = 0f;
        private float theirPitchDeltaEma = 0f;
        private float theirHealthPrev = -1f;
        private float theirHealthDeltaEma = 0f;

        // ---- v2.1.0 ADVANCED-DATA WINDOWS (training wheels, indices 84..103) ----
        // longer rhythm windows + their-aim-skill tracking. All buffers tick in
        // noteTarget(self, target, tick) so every feature is measurable against
        // MY frame exactly like the rest of the observation.
        private final float[] tStrafe = new float[40];   // lateral vel in MY frame, 40 ticks
        private final float[] tFwd = new float[40];      // forward vel in MY frame, 40 ticks
        private final float[] tAimErr = new float[40];   // THEIR angular error to my eye, 40 ticks
        private int aIdx = 0;
        private int aFilled = 0;
        private float theirYawPrev = 0f;
        private float theirYawDeltaEma = 0f;             // their flick speed
        private long theirLastJumpTick = -1000;
        private boolean theirAirPrev = false;
        private long theirLastSneakTick = -1000;
        private float theirSwingDistEma = -1f;           // how far they swing from (reach usage)
        private float theirAimErrEma = -1f;

        /**
         * Per-tick opponent bookkeeping (call once per game tick with a live target).
         * v2.1.0: also takes SELF so the advanced rhythm windows can measure the
         * opponent's motion and aim error in MY frame.
         */
        public void noteTarget(ClientPlayerEntity self, LivingEntity target, long tick) {
                tSwing[tIdx] = target.handSwinging;
                tAir[tIdx] = !target.isOnGround();
                tSneak[tIdx] = target.isSneaking();
                if (target.isSneaking()) theirLastSneakTick = tick;
                // jump detection: ground -> air edge
                if (!target.isOnGround() && theirAirPrev && Math.abs(TargetMotion.of(target).y) < 0.5f) {
                        theirLastJumpTick = tick;
                }
                theirAirPrev = !target.isOnGround();
                tIdx = (tIdx + 1) % tSwing.length;
                if (tFilled < tSwing.length) tFilled++;
                float pitch = target.getPitch();
                theirPitchDeltaEma += 0.15f * (MathHelper.wrapDegrees(pitch - theirPitchPrev) - theirPitchDeltaEma);
                theirPitchPrev = pitch;
                float yaw = target.getYaw();
                theirYawDeltaEma += 0.15f * (Math.abs(MathHelper.wrapDegrees(yaw - theirYawPrev)) - theirYawDeltaEma);
                theirYawPrev = yaw;
                float hp = target.getHealth();
                if (theirHealthPrev >= 0f) {
                        theirHealthDeltaEma += 0.2f * ((hp - theirHealthPrev) - theirHealthDeltaEma);
                }
                theirHealthPrev = hp;

                // ---- v2.1.0 advanced windows ----
                if (self != null) {
                        float yawRad = (float) Math.toRadians(self.getYaw());
                        float fx = -MathHelper.sin(yawRad), fz = MathHelper.cos(yawRad);
                        float rx = -fz, rz = fx;
                        Vec3d tv = TargetMotion.of(target);
                        float fwd = (float) (tv.x * fx + tv.z * fz);
                        float str = (float) (tv.x * rx + tv.z * rz);
                        tFwd[aIdx] = fwd;
                        tStrafe[aIdx] = str;
                        // THEIR aim error on me: angle between their look vector and
                        // the line to my eye — how tightly this opponent tracks me
                        Vec3d toMe = self.getEyePos().subtract(target.getEyePos());
                        double len = Math.max(1e-4, toMe.length());
                        Vec3d look = target.getRotationVec(1.0f);
                        float dot = MathHelper.clamp((float) ((look.x * toMe.x + look.y * toMe.y + look.z * toMe.z) / len), -1f, 1f);
                        float aerr = (float) Math.toDegrees(Math.acos(dot));
                        tAimErr[aIdx] = aerr;
                        theirAimErrEma = theirAimErrEma < 0 ? aerr : theirAimErrEma + 0.1f * (aerr - theirAimErrEma);
                        // reach usage: how far from me they were when they swung
                        if (target.handSwinging) {
                                float sd = (float) Math.sqrt(self.squaredDistanceTo(target));
                                theirSwingDistEma = theirSwingDistEma < 0 ? sd : theirSwingDistEma + 0.1f * (sd - theirSwingDistEma);
                        }
                        aIdx = (aIdx + 1) % tFwd.length;
                        if (aFilled < tFwd.length) aFilled++;
                }
        }

        /** Per-decision own-action bookkeeping (call with the EXECUTED action). */
        public void noteOwnAction(int action) {
                hAtk[hIdx] = ActionSpace.attackOf(action);
                hJump[hIdx] = ActionSpace.jumpOf(action);
                hSpr[hIdx] = ActionSpace.sprintOf(action);
                hMove[hIdx] = ActionSpace.moveOf(action);
                hIdx = (hIdx + 1) % hAtk.length;
                if (hFilled < hAtk.length) hFilled++;
        }

        public void resetFight() {
                hFilled = 0;
                tFilled = 0;
                aFilled = 0;
                aIdx = 0;
                theirHealthPrev = -1f;
                theirHealthDeltaEma = 0f;
                theirPitchDeltaEma = 0f;
                theirYawDeltaEma = 0f;
                theirLastJumpTick = -1000;
                theirLastSneakTick = -1000;
                theirAirPrev = false;
                theirSwingDistEma = -1f;
                theirAimErrEma = -1f;
        }

        /** Fraction of set booleans over the ring buffer. */
        private static float frac(boolean[] buf, int filled) {
                if (filled <= 0) return 0f;
                int n = 0;
                for (int i = 0; i < filled; i++) if (buf[i]) n++;
                return (float) n / filled;
        }

        /**
         * Build the 20 sight features. Order is FROZEN (v2 brain input layout):
         * 0  crosshair angular error to target center / 180
         * 1  vertical angular error (signed, target-above-crosshair +) / 90
         * 2  bbox angular width / 45deg
         * 3  bbox top edge elevation / 90
         * 4  bbox bottom edge elevation / 90
         * 5  line of sight (1 = clear)
         * 6  my time since last landed hit / 40
         * 7  their swing frac over last 20 ticks
         * 8  their airborne frac over last 20 ticks
         * 9  my attack frac over last 8 decisions
         * 10 my jump frac over last 8 decisions
         * 11 my sprint frac over last 8 decisions
         * 12 my forward bias over last 8 decisions
         * 13 my strafe bias over last 8 decisions
         * 14 their closing speed on me / 0.6 (signed)
         * 15 their time since last swing / 40
         * 16 their health delta EMA (damage/regen trend)
         * 17 their pitch delta EMA (them looking around)
         * 18 their sneak frac over last 20 ticks
         * 19 bias = 1
         */
        public float[] build(ClientPlayerEntity self, LivingEntity target, HitWatcher hits, long tick) {
                float[] s = new float[DIM];

                double dx = target.getX() - self.getX();
                double dy = target.getY() + target.getHeight() * 0.5 - self.getEyePos().y;
                double dz = target.getZ() - self.getZ();
                double dist = Math.max(1e-4, Math.sqrt(dx * dx + dy * dy + dz * dz));

                // 0/1: crosshair angular error — the angle between the look vector
                // and the direction to the target's chest, decomposed yaw/pitch
                Vec3d look = self.getRotationVec(1.0f);
                double tx = dx / dist, ty = dy / dist, tz = dz / dist;
                float angErr = (float) Math.toDegrees(Math.acos(MathHelper.clamp(
                                (float) (look.x * tx + look.y * ty + look.z * tz), -1f, 1f)));
                s[0] = MathHelper.clamp(angErr / 180f, 0f, 1f);
                float pitchToTarget = (float) Math.toDegrees(Math.atan2(-ty, Math.sqrt(tx * tx + tz * tz)));
                s[1] = MathHelper.clamp(MathHelper.wrapDegrees(pitchToTarget - self.getPitch()) / 90f, -1f, 1f);

                // 2/3/4: projected hitbox size and edges (angles from the eye ray)
                float halfW = target.getWidth() * 0.5f;
                float angW = (float) Math.toDegrees(Math.atan2(halfW, dist));
                s[2] = MathHelper.clamp(angW / 45f, 0f, 1.5f);
                float dyTop = (float) (target.getY() + target.getHeight() - self.getEyePos().y);
                float dyBot = (float) (target.getY() - self.getEyePos().y);
                float topAng = (float) Math.toDegrees(Math.atan2(dyTop, Math.sqrt(dx * dx + dz * dz)));
                float botAng = (float) Math.toDegrees(Math.atan2(dyBot, Math.sqrt(dx * dx + dz * dz)));
                s[3] = MathHelper.clamp(topAng / 90f, -1f, 1f);
                s[4] = MathHelper.clamp(botAng / 90f, -1f, 1f);

                // 5: line of sight — vanilla raycast (blocks only), chest->eye
                float los = 0f;
                if (self.getEntityWorld() != null) {
                        Vec3d from = self.getEyePos();
                        Vec3d to = new Vec3d(target.getX(), target.getY() + target.getHeight() * 0.6, target.getZ());
                        var hit = self.getEntityWorld().raycast(new net.minecraft.world.RaycastContext(
                                        from, to,
                                        net.minecraft.world.RaycastContext.ShapeType.COLLIDER,
                                        net.minecraft.world.RaycastContext.FluidHandling.NONE, self));
                        los = hit != null && hit.getType() != net.minecraft.util.hit.HitResult.Type.MISS ? 0f : 1f;
                }
                s[5] = los;

                // 6: time since MY last landed hit (fine scale — 2 seconds)
                s[6] = MathHelper.clamp((tick - hits.lastMyHitTick) / 40f, 0f, 2f);

                // 7/8: opponent rhythm buffers
                s[7] = frac(tSwing, tFilled);
                s[8] = frac(tAir, tFilled);

                // 9..13: my own action history (rhythm of my play)
                s[9] = frac(hAtk, hFilled);
                s[10] = frac(hJump, hFilled);
                s[11] = frac(hSpr, hFilled);
                if (hFilled > 0) {
                        float fwd = 0f, str = 0f;
                        for (int i = 0; i < hFilled; i++) {
                                int m = hMove[i];
                                fwd += (m == ActionSpace.M_W || m == ActionSpace.M_WA || m == ActionSpace.M_WD) ? 1f
                                                : (m == ActionSpace.M_S || m == ActionSpace.M_SA || m == ActionSpace.M_SD) ? -1f
                                                : 0f;
                                str += (m == ActionSpace.M_D || m == ActionSpace.M_WD || m == ActionSpace.M_SD) ? 1f
                                                : (m == ActionSpace.M_A || m == ActionSpace.M_WA || m == ActionSpace.M_SA) ? -1f
                                                : 0f;
                        }
                        s[12] = MathHelper.clamp(fwd / hFilled, -1f, 1f);
                        s[13] = MathHelper.clamp(str / hFilled, -1f, 1f);
                }

                // 14: their closing speed (relative velocity along the line to me)
                // vector target->me = (-dx,-dz); towardMe = tv . unit(target->me)
                Vec3d tv = TargetMotion.of(target);
                double inv = 1.0 / Math.max(1e-4, Math.sqrt(dx * dx + dz * dz));
                float towardMe = (float) ((-dx) * tv.x * inv + (-dz) * tv.z * inv); // + = closing
                s[14] = MathHelper.clamp(towardMe / 0.6f, -1.5f, 1.5f);

                // 15: their time since last observed swing
                s[15] = MathHelper.clamp((tick - hits.theirLastAttackTick) / 40f, 0f, 2f);

                // 16/17: their health trend + look-around activity
                s[16] = MathHelper.clamp(theirHealthDeltaEma / 2f, -1f, 1f);
                s[17] = MathHelper.clamp(theirPitchDeltaEma / 45f, -1f, 1f);

                // 18/19
                s[18] = frac(tSneak, tFilled);
                s[19] = 1f;

                return s;
        }

        /**
         * v2.1.0 ADVANCED-DATA BLOCK (indices 84..103 of the 104-dim v3
         * observation) — the "training wheels" the user asked for: the same
         * depth of opponent reading the v1 stack's tactical layer enjoys,
         * delivered as pure DATA to the four-head brain (never as execution).
         *
         * 84  their aim error on me, EMA /180       — how tight THIS opponent tracks
         * 85  their aim error on me, current /180
         * 86  their yaw-rate EMA (flick speed) /90
         * 87  their swings last 5 ticks /3          — burst click rate
         * 88  their swings last 60 ticks /15        — sustained click rate
         * 89  their forward streak /20              — consecutive ticks closing
         * 90  their strafe sign (current)
         * 91  their strafe streak /20               — how long they held this side
         * 92  their direction changes, last 40 /8   — strafe predictability
         * 93  their W-tap just fired 0/1            — forward streak broke <6 ticks ago
         * 94  time since their last jump /40
         * 95  time since their last sneak /40
         * 96  their swing-distance EMA - 2.9        — reach overuse signal
         * 97  am I inside their swing arc 0/1       — dist <= 3.4 && their aim err < 20deg
         * 98  their absorption /20
         * 99  their fall distance /3                — knockback flight state
         * 100 their using-item 0/1                  — shield / eating / blocking
         * 101 my angular error to their chest /45   — MY aim quality, fine scale
         * 102 my error VERTICAL only /45            — pitch is the axis that separates head/feet aimers
         * 103 bias = 1
         */
        public float[] buildAdvanced(ClientPlayerEntity self, LivingEntity target, long tick) {
                float[] s = new float[20];

                s[0] = MathHelper.clamp((theirAimErrEma < 0 ? 0f : theirAimErrEma) / 180f, 0f, 1f);
                float curErr = aFilled > 0 ? tAimErr[(aIdx + tAimErr.length - 1) % tAimErr.length] : 0f;
                s[1] = MathHelper.clamp(curErr / 180f, 0f, 1f);
                s[2] = MathHelper.clamp(theirYawDeltaEma / 90f, 0f, 1.5f);

                // swing rates over both windows
                int sw5 = 0, sw60 = 0;
                for (int i = 0; i < tSwing.length; i++) {
                        if (i < tFilled && tSwing[i]) {
                                sw60++;
                                int age = (tIdx - 1 - i + tSwing.length) % tSwing.length;
                                if (age < 5) sw5++;
                        }
                }
                s[3] = MathHelper.clamp(sw5 / 3f, 0f, 1.5f);
                s[4] = MathHelper.clamp(sw60 / 15f, 0f, 1.5f);

                // movement rhythm: streaks + direction changes over 40 ticks
                int fwdStreak = 0, strStreak = 0, dirChanges = 0;
                float lastSign = 0f;
                for (int k = 0; k < tFwd.length && k < aFilled; k++) {
                        int i = (aIdx - 1 - k + tFwd.length) % tFwd.length;
                        if (tFwd[i] > 0.05f) fwdStreak = k + 1;
                        float sign = tStrafe[i] > 0.05f ? 1f : tStrafe[i] < -0.05f ? -1f : 0f;
                        if (sign != 0f) {
                                if (lastSign != 0f && sign != lastSign) dirChanges++;
                                if (strStreak == k) strStreak = k + 1;
                                lastSign = sign;
                        }
                }
                s[5] = MathHelper.clamp(fwdStreak / 20f, 0f, 1.5f);
                s[6] = lastSign;
                s[7] = MathHelper.clamp(strStreak / 20f, 0f, 1.5f);
                s[8] = MathHelper.clamp(dirChanges / 8f, 0f, 1.5f);
                // W-tap detector: they were closing and stopped within the last 6 ticks
                boolean wtap = aFilled > 1 && fwdStreak >= 0 && fwdStreak < 6
                                && tFwd[(aIdx + tFwd.length - Math.min(aFilled, 6)) % tFwd.length] > 0.15f;
                s[9] = wtap ? 1f : 0f;

                s[10] = MathHelper.clamp((tick - theirLastJumpTick) / 40f, 0f, 2f);
                s[11] = MathHelper.clamp((tick - theirLastSneakTick) / 40f, 0f, 2f);
                s[12] = MathHelper.clamp((theirSwingDistEma < 0 ? 0f : theirSwingDistEma - 2.9f) / 1.0f, -1f, 1.5f);
                float dist = (float) Math.sqrt(self.squaredDistanceTo(target));
                s[13] = (dist <= 3.4f && theirAimErrEma >= 0f && theirAimErrEma < 20f) ? 1f : 0f;
                s[14] = MathHelper.clamp(target.getAbsorptionAmount() / 20f, 0f, 1f);
                s[15] = MathHelper.clamp((float) target.fallDistance / 3f, 0f, 1f);
                s[16] = target.isUsingItem() ? 1f : 0f;

                // MY aim quality (fine scale — the /45 sharper than the sight block's /180)
                Vec3d chest = target.getEyePos().subtract(0, 0.7, 0); // ~chest: standing eye 1.62 - 0.7
                Vec3d to = chest.subtract(self.getEyePos());
                double dlen = Math.max(1e-4, to.length());
                Vec3d look = self.getRotationVec(1.0f);
                float dot = MathHelper.clamp((float) ((look.x * to.x + look.y * to.y + look.z * to.z) / dlen), -1f, 1f);
                float myErr = (float) Math.toDegrees(Math.acos(dot));
                s[17] = MathHelper.clamp(myErr / 45f, 0f, 2f);
                float myErrV = Math.abs((float) Math.toDegrees(Math.asin(MathHelper.clamp(to.y / dlen, -1f, 1f)))
                                + self.getPitch());
                s[18] = MathHelper.clamp(myErrV / 45f, 0f, 2f);
                s[19] = 1f;

                return s;
        }
}
