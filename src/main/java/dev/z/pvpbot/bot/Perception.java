package dev.z.pvpbot.bot;

import dev.z.pvpbot.PvpBot;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Builds the 64-dimensional normalized state vector the policy network sees.
 * Contains NO strategy — only measurements. Everything the bot will ever
 * "know" about fighting emerges from the network learning over this state.
 *
 * v1.0.4 additions (56..63) — the richer observation set the user asked for:
 * enemy position + velocity PREDICTION, own movement keys, opponent facing
 * (pitch/yaw), opponent sneak/swing state and aim-proximity measures.
 * These are also the features the imitation learner reads when watching the
 * user fight (human-train), so every recorded expert action is grounded in
 * the full picture.
 */
public final class Perception {

        public static final int DIM = 64;
        /** v2.0: 64 legacy + 20 sight features. */
        public static final int DIM_V2 = DIM + Sight.DIM;
        /** v2.1: + 20 advanced-opponent-data features (the training wheels). */
        public static final int DIM_ADV = 20;
        public static final int DIM_V3 = DIM_V2 + DIM_ADV;

        public float[] build(ClientPlayerEntity self, LivingEntity target, HitWatcher hits,
                             OpponentMemory opp, TerrainSense terrain, long tick, float matchTimeTicks,
                             float whiffRate) {
                float[] s = new float[DIM];

                float myYaw = self.getYaw();
                Vec3d myVel = self.getVelocity();
                // velocity decomposed into my facing frame
                float yawRad = (float) Math.toRadians(myYaw);
                float fx = -MathHelper.sin(yawRad), fz = MathHelper.cos(yawRad);
                float rx = -fz, rz = fx;
                float velFwd = (float) (myVel.x * fx + myVel.z * fz);
                float velStr = (float) (myVel.x * rx + myVel.z * rz);

                // --- self (0..15)
                s[0] = self.getHealth() / 20f;
                s[1] = MathHelper.clamp(self.getAbsorptionAmount() / 20f, 0f, 1f);
                s[2] = self.getHungerManager().getFoodLevel() / 20f;
                s[3] = self.getAttackCooldownProgress(0.0f);
                s[4] = self.isSprinting() ? 1f : 0f;
                s[5] = self.isOnGround() ? 1f : 0f;
                s[6] = self.isOnGround() ? 0f : (myVel.y < 0 ? -1f : 1f);
                s[7] = MathHelper.clamp((float) myVel.y, -1f, 1f);
                s[8] = MathHelper.clamp(velFwd / 0.35f, -1.5f, 1.5f);
                s[9] = MathHelper.clamp(velStr / 0.35f, -1.5f, 1.5f);
                s[10] = self.hurtTime / 10f;
                s[11] = MathHelper.clamp((tick - hits.lastMyHitTick) / 100f, 0f, 1f);
                s[12] = MathHelper.clamp((tick - hits.lastTakenHitTick) / 100f, 0f, 1f);
                s[13] = MathHelper.clamp(hits.comboDealt / 6f, 0f, 1.5f);
                s[14] = MathHelper.clamp(hits.comboTaken / 6f, 0f, 1.5f);
                s[15] = MathHelper.clamp((tick - PvpBot.get().controller().lastAttackAttemptTick) / 60f, 0f, 1f);

                // --- relative (16..18)
                double dx = target.getX() - self.getX(), dz = target.getZ() - self.getZ();
                double dist = Math.sqrt(dx * dx + dz * dz);
                s[16] = MathHelper.clamp((float) (dist / 6.0), 0f, 2f);
                float bearing = (float) Math.toDegrees(Math.atan2(-dx, dz)); // yaw that would face target
                float relYaw = MathHelper.wrapDegrees(bearing - myYaw);
                s[17] = relYaw / 180f;
                float dy = (float) ((target.getEyePos().y) - self.getEyePos().y);
                s[18] = MathHelper.clamp(dy / 4f, -1f, 1f);

                // --- target (19..31)
                Vec3d tv = TargetMotion.of(target);
                float tfx = fx, tfz = fz, trx = rx, trz = rz;
                s[19] = MathHelper.clamp((float) (tv.x * tfx + tv.z * tfz) / 0.35f, -1.5f, 1.5f); // their vel in MY frame
                s[20] = MathHelper.clamp((float) (tv.x * trx + tv.z * trz) / 0.35f, -1.5f, 1.5f);
                s[21] = MathHelper.clamp((float) tv.y, -1f, 1f);
                s[22] = target.isOnGround() ? 1f : 0f;
                s[23] = target.isOnGround() ? 0f : (tv.y < 0 ? -1f : 1f);
                s[24] = target.hurtTime / 10f;
                s[25] = target.getHealth() / 20f;
                float theirSpeed = (float) Math.sqrt(tv.x * tv.x + tv.z * tv.z);
                s[26] = theirSpeed > 0.24f ? 1f : 0f; // sprinting guess
                float theirCp = MathHelper.clamp((tick - hits.theirLastAttackTick) / 12.5f, 0f, 1f);
                s[27] = theirCp; // their cooldown estimate (updated when they hit us)
                s[28] = MathHelper.clamp((tick - hits.theirLastAttackTick) / 100f, 0f, 1f);
                s[29] = MathHelper.clamp(hits.distEma / 6f, 0f, 2f);
                s[30] = relYaw / 180f; // aim angle error duplicate kept for backward-compat of layout
                s[31] = MathHelper.clamp((float) (dist - hits.prevDist), -0.5f, 0.5f) * 2f; // closing speed proxy

                // --- opponent model (32..41)
                float[] om = opp.features();
                s[32] = om[0]; // wtap freq
                s[33] = om[1]; // stap freq
                s[34] = om[2]; // jump freq
                s[35] = om[3]; // crit freq
                s[36] = om[4]; // aggression
                s[37] = om[5]; // avg dist
                s[38] = om[6]; // post-hit jump P
                s[39] = om[7]; // post-hit retreat P
                s[40] = MathHelper.clamp(opp.memoryAgeTicks(tick) / (float) (PvpBot.get().config().memorySeconds * 20), 0f, 1f);
                s[41] = MathHelper.clamp(opp.hitsObserved() / 50f, 0f, 1f);

                // --- terrain (42..52)
                for (int i = 0; i < 8; i++) {
                        s[42 + i] = terrain.blocked[i];
                }
                s[50] = terrain.floorDropAhead;
                s[51] = terrain.ceilingLow;
                s[52] = terrain.wallTightness;

                // --- misc (53..55)
                s[53] = MathHelper.clamp(matchTimeTicks / 2400f, 0f, 1f);
                s[54] = whiffRate;
                s[55] = 1f; // bias

                // --- v1.0.4 observation upgrade (56..63) -------------------------
                // 56/57: where the enemy WILL be in 3 ticks (linear lead) relative
                //        to my facing — the "aim ahead" picture.
                float lead = 3f / 20f; // 150 ms
                double px = target.getX() + tv.x * lead, pz = target.getZ() + tv.z * lead;
                double py = target.getY() + target.getHeight() * 0.5 + tv.y * lead;
                double pdx = px - self.getX(), pdz = pz - self.getZ();
                s[56] = MathHelper.clamp((float) ((pdx * fx + pdz * fz) / 3.0), -2f, 2f);
                s[57] = MathHelper.clamp((float) ((pdx * rx + pdz * rz) / 3.0), -2f, 2f);
                // 58: their yaw relative to mine (are they facing me?), 59: their pitch
                float theirRelYaw = MathHelper.wrapDegrees(target.getYaw() - myYaw);
                s[58] = theirRelYaw / 180f;
                s[59] = target.getPitch() / 90f;
                // 60/61: their sneak + hand-swing state (hitbox height + attack telegraphs)
                s[60] = target.isSneaking() ? 1f : 0f;
                s[61] = target.handSwinging ? 1f : 0f;
                // 62/63: MY movement keys right now (W/S on 62, A/D on 63) — the
                //        imitation learner labels actions against exactly this.
                int move = Actuator.currentMoveCombo();
                s[62] = (move == ActionSpace.M_W || move == ActionSpace.M_WA || move == ActionSpace.M_WD) ? 1f
                                : (move == ActionSpace.M_S || move == ActionSpace.M_SA || move == ActionSpace.M_SD) ? -1f
                                : 0f;
                s[63] = (move == ActionSpace.M_D || move == ActionSpace.M_WD || move == ActionSpace.M_SD) ? 1f
                                : (move == ActionSpace.M_A || move == ActionSpace.M_WA || move == ActionSpace.M_SA) ? -1f
                                : 0f;

                return s;
        }

        /**
         * v2.0 PHASE 1 — the 84-dim observation: the proven 64-dim vector
         * followed by the 20-dim sight block (crosshair geometry, projected
         * hitbox, line of sight, time-since-hit and the action-history
         * rhythms). Used by the v2 four-head brain; the legacy 64-dim DQN
         * keeps reading build() so old weights stay valid.
         */
        public float[] buildV2(ClientPlayerEntity self, LivingEntity target, HitWatcher hits,
                               OpponentMemory opp, TerrainSense terrain, long tick, float matchTimeTicks,
                               float whiffRate, Sight sight) {
                float[] base = build(self, target, hits, opp, terrain, tick, matchTimeTicks, whiffRate);
                float[] out = new float[DIM_V2];
                System.arraycopy(base, 0, out, 0, DIM);
                float[] sightVec = sight.build(self, target, hits, tick);
                System.arraycopy(sightVec, 0, out, DIM, Sight.DIM);
                return out;
        }

        /**
         * v2.1.0 — the 104-dim observation: the frozen 84-dim v2 vector
         * followed by the 20-dim advanced-opponent-data block (their aim
         * skill on me, click rates over two windows, strafe/W-tap rhythm
         * streaks, jump/sneak recency, reach usage, my own fine aim error).
         * The four-head brain reads THIS in v2.1+; old v2 .pbm files (84-dim
         * input layer) are rejected by the arch check and the brain restarts
         * fresh — the new block is the training-wheels upgrade.
         */
        public float[] buildV3(ClientPlayerEntity self, LivingEntity target, HitWatcher hits,
                               OpponentMemory opp, TerrainSense terrain, long tick, float matchTimeTicks,
                               float whiffRate, Sight sight) {
                float[] v2 = buildV2(self, target, hits, opp, terrain, tick, matchTimeTicks, whiffRate, sight);
                float[] out = new float[DIM_V3];
                System.arraycopy(v2, 0, out, 0, DIM_V2);
                float[] adv = sight.buildAdvanced(self, target, tick);
                System.arraycopy(adv, 0, out, DIM_V2, DIM_ADV);
                return out;
        }
}
