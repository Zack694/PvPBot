package dev.z.pvpbot.bot;

import dev.z.pvpbot.BotConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;

import java.util.UUID;

/**
 * Picks and locks the duel target. Nearest hostile-capable entity wins.
 *
 * v2.0 PHASE 1 — PRACTICE-BOT TARGETING (the TheoBald/HerosBot fix): many
 * practice-bot mods spawn dummies that are NOT PlayerEntity subclasses but
 * plain LivingEntities, so the old player-only scan never locked onto them
 * and no training happened at all. With cfg.targetNonPlayers the scan
 * widens to every living entity in range (players always included, the
 * bot's owner always excluded). Default OFF so the bot never attacks random
 * mobs/livestock on a server.
 */
public final class TargetSelector {

        private final BotConfig cfg;
        private LivingEntity target;
        private UUID targetId;
        private int ticksWithoutCombat = 0;

        public TargetSelector(BotConfig cfg) {
                this.cfg = cfg;
        }

        public LivingEntity target() {
                return target;
        }

        public UUID targetId() {
                return targetId;
        }

        public boolean hasTarget() {
                return target != null && target.isAlive();
        }

        public void forceRetarget(LivingEntity p) {
                target = p;
                targetId = p != null ? p.getUuid() : null;
                ticksWithoutCombat = 0;
        }

        public void forget() {
                target = null;
                targetId = null;
                ticksWithoutCombat = 0;
        }

        /** @return true if a valid target is currently tracked. */
        private java.util.UUID cooldownId = null;   // v2.3: just-disengaged opponent
        private long cooldownUntil = 0L;

        public boolean tick(MinecraftClient mc, boolean combatHappenedThisTick) {
                ClientWorld world = mc.world;
                ClientPlayerEntity self = mc.player;
                if (world == null || self == null) {
                        forget();
                        return false;
                }

                if (combatHappenedThisTick) {
                        ticksWithoutCombat = 0;
                } else {
                        ticksWithoutCombat++;
                }

                // validate current
                if (target != null) {
                        boolean invalid = !target.isAlive()
                                        || target.isRemoved()
                                        || self.squaredDistanceTo(target) > 48.0 * 48.0
                                        || ticksWithoutCombat > cfg.disengageTicksNoCombat;
                        if (invalid) {
                                // v2.3: a disengage for "no combat" must not re-lock the SAME
                                // player in the same tick (the episode timeout never fired)
                                if (target.isAlive() && ticksWithoutCombat > cfg.disengageTicksNoCombat) {
                                        cooldownId = target.getUuid();
                                        cooldownUntil = System.currentTimeMillis() + 3000L;
                                }
                                forget();
                                return false;
                        }
                }

                // acquire new
                if (target == null) {
                        LivingEntity best = null;
                        double bestD = Double.MAX_VALUE;
                        // players are always eligible targets (real duels + fake
                        // players); non-player LivingEntities opt-in for practice bots
                        for (PlayerEntity p : world.getPlayers()) {
                                if (p == self || !p.isAlive() || p.isSpectator()) continue;
                                if (p.getUuid().equals(cooldownId) && System.currentTimeMillis() < cooldownUntil) continue;
                                double d = self.squaredDistanceTo(p);
                                if (d < bestD && d <= cfg.engageRadius * cfg.engageRadius) {
                                        bestD = d;
                                        best = p;
                                }
                        }
                        if (best == null && cfg.targetNonPlayers) {
                                Box area = self.getBoundingBox().expand(cfg.engageRadius);
                                for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, area,
                                                e2 -> e2 != self && e2.isAlive() && !e2.isSpectator()
                                                                && !(e2 instanceof PlayerEntity))) {
                                        double d = self.squaredDistanceTo(e);
                                        if (d < bestD && d <= cfg.engageRadius * cfg.engageRadius) {
                                                bestD = d;
                                                best = e;
                                        }
                                }
                        }
                        if (best != null) {
                                target = best;
                                targetId = best.getUuid();
                                ticksWithoutCombat = 0;
                        }
                }
                return target != null;
        }
}
