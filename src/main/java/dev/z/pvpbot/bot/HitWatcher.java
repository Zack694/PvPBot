package dev.z.pvpbot.bot;

import dev.z.pvpbot.BotConfig;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Watches hurt-time and health transitions to classify every exchange:
 * HIT / CRIT / MISS (whiff) / TAKEN, tracks combo streaks, distance EMA and
 * produces the outcome-only reward signal. Rewards never reference
 * techniques — only damage, kills and deaths — so every technique the policy
 * discovers (w-taps, jump resets, crit chaining, hit selection) is genuinely
 * learned, not scripted.
 */
public final class HitWatcher {

        // --- events for HUD
        public static final class TradeEvent {
                public final long tick;
                public final String kind;   // HIT, CRIT, MISS, TAKEN
                public float amount;        // HP change: + = damage dealt, - = damage taken
                public float reward;        // v2.3.6: learning reward this event paid
                public boolean estimated;   // v2.3.6: damage estimated (server hides health)
                public TradeEvent(long tick, String kind, float amount) {
                        this.tick = tick; this.kind = kind; this.amount = amount;
                }
        }

        public final Deque<TradeEvent> log = new ArrayDeque<>(16);

        // --- timing bookkeeping (read by Perception)
        public long lastMyHitTick = -1000;
        public long lastTakenHitTick = -1000;
        public long lastAttackAttemptTick = -1000;
        public long theirLastAttackTick = -1000;
        public long prevAttackAttemptTick = -1000;
        // their attack rhythm, learned from OBSERVED hand swings (works even when
        // the swing whiffs or gets blocked — the animation always arrives)
        public float theirAttackIntervalTicks = 0f;
        public int theirSwingsObserved = 0;
        private boolean prevTheirSwing = false;
        public float distEma = 3f;
        public double prevDist = 3.0;

        // --- combo state
        public int comboDealt = 0;
        public int comboTaken = 0;

        // --- episode totals
        public float dmgDealt, dmgTaken;
        public int hitsLanded, whiffs, critsLanded, timesTaken;

        // --- reward accumulator for current transition
        public float pendingReward;

        /** Human-train mode: target health-drops the BOT didn't cause are the user's
         *  hits — still credit them so imitation learns from real damage dealt. */
        public boolean attributeUnclaimedHits = false;

        // detection state
        private float lastSelfHealth = -1f;
        private float lastTargetHealth = -1f;
        private int lastSelfHurtTime = 0;
        private int lastTargetHurtTime = 0;
        private boolean attackInFlight = false;
        private long attackInFlightTick = -1000;
        private boolean attackWasFalling;
        private boolean attackWasSprinting;
        private final OpponentMemory opp;
        private final BotConfig cfg;

        public HitWatcher(OpponentMemory opp, BotConfig cfg) {
                this.opp = opp;
                this.cfg = cfg;
        }

        public void resetEpisode(ClientPlayerEntity self, LivingEntity target) {
                dmgDealt = dmgTaken = 0;
                hitsLanded = whiffs = critsLanded = timesTaken = 0;
                comboDealt = comboTaken = 0;
                pendingReward = 0;
                lastSelfHealth = self.getHealth();
                lastTargetHealth = target != null ? target.getHealth() : -1f;
                lastMyHitTick = lastTakenHitTick = lastAttackAttemptTick = prevAttackAttemptTick = -1000;
                prevTheirSwing = false; // rhythm (theirAttackIntervalTicks) carries across rounds
                // v2.3: a refill seen BEFORE the round opened (new round at full HP,
                // first acquisition) must never settle the new round as a WIN
                resetDetected = false;
                attackInFlight = false;
        }

        public void markAttackAttempt(long tick, boolean falling, boolean sprinting) {
                prevAttackAttemptTick = lastAttackAttemptTick;
                lastAttackAttemptTick = tick;
                attackInFlight = true;
                attackInFlightTick = tick;
                attackWasFalling = falling;
                attackWasSprinting = sprinting;
        }

        /**
         * v2.0 RESET DETECTION — practice-bot rounds often never "die": mods
         * like TheoBald's or HerosBot-style dummies REFILL their health and/or
         * teleport-reset instead of playing a death animation, so a round never
         * terminates and no terminal reward ever lands. A big INSTANT health
         * gain (a refill can only be a reset — natural regen trickles) is that
         * signal; BotController polls consumeResetDetected() and settles the
         * round as a verified WIN when we dealt the damage.
         */
        private boolean resetDetected = false;

        public boolean consumeResetDetected() {
                boolean r = resetDetected;
                resetDetected = false;
                return r;
        }

        /** Feed per-tick combat observation. */
        public void tick(ClientPlayerEntity self, LivingEntity target, long tick) {
                float sh = self.getHealth();
                int sHurt = self.hurtTime;
                // v2.0 PHASE 1 — HEALTH-POLL AS PRIMARY SIGNAL (the practice-bot
                // reward fix): mods that damage through custom paths skip the
                // vanilla hurt animation (hurtTime stays 0) and their reward
                // stream stayed silent. A health DROP needs no animation — it is
                // synchronized for every LivingEntity, fake or real. hurtTime is
                // demoted to a secondary (contact-flash) signal.
                boolean iWasHit = sHurt > lastSelfHurtTime || sh < lastSelfHealth - 0.01f;
                float taken = 0f;
                // v2.3 REFRACTORY WINDOW — the hurt animation (damage packet) and
                // the health update often reach the client on DIFFERENT ticks; each
                // used to count as its own hit (combo x2, rewards x2). A second
                // signal within 4 ticks only adds its damage.
                if (iWasHit && tick - lastTakenHitTick <= 4 && lastTakenHitTick > 0) {
                        float extra = Math.max(0f, lastSelfHealth - sh);
                        dmgTaken += extra;
                        pendingReward -= 0.25f * extra;
                        iWasHit = false;
                        lastSelfHurtTime = sHurt;
                        lastSelfHealth = sh;
                        sh = -999f; // marker: already handled
                }
                if (iWasHit) {
                        taken = Math.max(0f, lastSelfHealth - sh);
                        lastTakenHitTick = tick;
                        theirLastAttackTick = tick;
                        comboTaken++;
                        comboDealt = 0;
                        timesTaken++;
                        dmgTaken += taken;
                        float rBeforeT = pendingReward;
                        pendingReward -= 0.25f * taken;
                        // combo punishment grows while being comboed
                        if (comboTaken > 2) pendingReward -= 0.04f;
                        float takenReward = pendingReward - rBeforeT;
                        boolean theirCrit = !target.isOnGround() && TargetMotion.of(target).y < 0;
                        float theirSpeed = (float) Math.sqrt(TargetMotion.of(target).x * TargetMotion.of(target).x + TargetMotion.of(target).z * TargetMotion.of(target).z);
                        Vec3dToSelf(self, target);
                        opp.onTheirHitMe(theirCrit, theirSpeed, velTowardMe);
                        TradeEvent tev = new TradeEvent(tick, "TAKEN", -taken);
                        tev.reward = takenReward;
                        log.addFirst(tev);
                } else {
                        // keep comboTaken alive only during active pressure
                        if (tick - lastTakenHitTick > 40) comboTaken = 0;
                }
                if (sh != -999f) {
                        lastSelfHurtTime = sHurt;
                        lastSelfHealth = sh;
                }

                if (target != null) {
                        // observed hand swing: the opponent clicked. Their attack clock
                        // and rhythm update here — earlier this only updated when their
                        // damage got through, blind spots and all
                        boolean swinging = target.handSwinging;
                        if (swinging && !prevTheirSwing && self.distanceTo(target) <= 4.5) {
                                if (theirLastAttackTick > 0) {
                                        long gap = tick - theirLastAttackTick;
                                        if (gap >= 3 && gap <= 60) {
                                                theirAttackIntervalTicks = theirAttackIntervalTicks <= 0f
                                                                ? (float) gap
                                                                : theirAttackIntervalTicks + 0.25f * ((float) gap - theirAttackIntervalTicks);
                                        }
                                }
                                theirLastAttackTick = tick;
                                theirSwingsObserved++;
                        }
                        prevTheirSwing = swinging;

                        float th = target.getHealth();
                        int tHurt = target.hurtTime;
                        // v2.0 PHASE 1 — the same health-poll-first fix for THEIR side:
                        // a health drop is a hit even with zero hurt animation.
                        boolean theyWereHit = tHurt > lastTargetHurtTime || th < lastTargetHealth - 0.01f;
                        if (theyWereHit) {
                                float dealt = Math.max(0f, lastTargetHealth - th);
                                boolean myHit = attackInFlight && tick - attackInFlightTick <= 10;
                                boolean sameHit = lastMyHitTick > 0 && tick - lastMyHitTick <= 4
                                                && lastMyHitTick >= attackInFlightTick;
                                if ((myHit || attributeUnclaimedHits) && sameHit) {
                                        // v2.3: the second half (health after hurt, or vice
                                        // versa) of a hit already counted — damage only.
                                        // v2.3.6: the real HP drop REPLACES the estimate.
                                        float corr = dealt - provisionalEst;
                                        if (dealt <= 0f) corr = 0f;
                                        dmgDealt += corr;
                                        pendingReward += 0.2f * corr;
                                        if (lastHitEvent != null && dealt > 0f) {
                                                lastHitEvent.amount = dealt;
                                                lastHitEvent.reward += 0.2f * corr;
                                                lastHitEvent.estimated = false;
                                        }
                                        if (dealt > 0f) provisionalEst = 0f;
                                } else if (myHit || attributeUnclaimedHits) {
                                        boolean crit = myHit
                                                        ? attackWasFalling && !attackWasSprinting
                                                        : !self.isOnGround() && self.getVelocity().y < 0;
                                        lastMyHitTick = tick;
                                        comboDealt++;
                                        comboTaken = 0;
                                        hitsLanded++;
                                        // v2.3.6 HIDDEN-HEALTH FIX — many PvP servers hide other
                                        // players' health (it never changes client-side), so every
                                        // hit read 0.0 damage and paid a flat +0.05. When the hurt
                                        // flash arrives without an HP drop the damage is ESTIMATED
                                        // from vanilla's formula (our attack damage x charge curve
                                        // x crit, minus their visible armor); a real HP drop within
                                        // 4 ticks replaces the estimate.
                                        float rBefore = pendingReward;
                                        boolean est = false;
                                        if (dealt <= 0f && myHit) {
                                                dealt = estimateDamage(self, target, attackWasFalling && !attackWasSprinting);
                                                provisionalEst = dealt;
                                                est = true;
                                        } else {
                                                provisionalEst = 0f;
                                        }
                                        dmgDealt += dealt;
                                        pendingReward += dealt > 0f ? 0.2f * dealt : 0.05f;
                                        if (crit) {
                                                critsLanded++;
                                                // small nudge only — the 1.5x crit damage is
                                                // already rewarded via 0.2*dmg; a big flat
                                                // bonus taught the policy to crit-spam
                                                pendingReward += 0.1f;
                                        }
                                        if (comboDealt > 2) pendingReward += 0.04f;
                                        opp.onMyHitThem(self.distanceTo(target));
                                        TradeEvent ev = new TradeEvent(tick, crit ? "CRIT" : "HIT", dealt);
                                        ev.reward = pendingReward - rBefore;
                                        ev.estimated = est;
                                        lastHitEvent = ev;
                                        log.addFirst(ev);
                                }
                        }
                        lastTargetHurtTime = tHurt;
                        float prevTh = lastTargetHealth;
                        lastTargetHealth = th;

                        // v2.0 RESET DETECTION — a big instant refill is a practice-bot
                        // round reset (regen heals ~1 HP/s; anything above +6 HP in a
                        // single tick is a scripted restore). Settled by BotController.
                        if (prevTh >= 0f && th - prevTh > 6f) {
                                resetDetected = true;
                        }

                        // whiff resolution
                        if (attackInFlight && tick - attackInFlightTick >= 10) {
                                if (tick - lastMyHitTick > 10 || lastMyHitTick < attackInFlightTick) {
                                        whiffs++;
                                        pendingReward -= 0.02f;
                                        TradeEvent mev = new TradeEvent(tick, "MISS", 0f);
                                        mev.reward = -0.02f;
                                        log.addFirst(mev);
                                }
                                attackInFlight = false;
                        }

                        double d = self.distanceTo(target);
                        distEma += 0.01f * ((float) d - distEma);
                        prevDist = d;
                }

                // survival shaping: +0.02 per second alive
                pendingReward += 0.001f;

                while (log.size() > 8) log.removeLast();
        }

        private float provisionalEst = 0f;
        private TradeEvent lastHitEvent = null;

        /** v2.3.6: vanilla melee damage estimate (used when the server hides health). */
        static float estimateDamage(ClientPlayerEntity self, LivingEntity target, boolean crit) {
                try {
                        double base = self.getAttributeValue(net.minecraft.entity.attribute.EntityAttributes.ATTACK_DAMAGE);
                        // the attack already reset the meter; the swing used ~full charge
                        float p = 1f;
                        double dmg = base * (0.2 + p * p * 0.8);
                        if (crit) dmg *= 1.5;
                        double armor = target.getArmor();
                        double tough = target.getAttributeValue(net.minecraft.entity.attribute.EntityAttributes.ARMOR_TOUGHNESS);
                        double f = 2.0 + tough / 4.0;
                        double g = Math.min(20.0, Math.max(armor * 0.2, armor - dmg / f));
                        dmg = dmg * (1.0 - g / 25.0);
                        return (float) Math.max(0.0, Math.min(20.0, dmg));
                } catch (Throwable t) {
                        return 1.5f;
                }
        }

        private float velTowardMe;

        private void Vec3dToSelf(ClientPlayerEntity self, LivingEntity target) {
                double dx = self.getX() - target.getX();
                double dz = self.getZ() - target.getZ();
                double len = Math.sqrt(dx * dx + dz * dz);
                if (len < 1e-4) { velTowardMe = 0f; return; }
                double nx = dx / len, nz = dz / len;
                velTowardMe = (float) (TargetMotion.of(target).x * nx + TargetMotion.of(target).z * nz);
        }

        public float whiffRate() {
                int att = hitsLanded + whiffs;
                return att == 0 ? 0f : (float) whiffs / att;
        }

        public void trimLogToTick(long tick) {
                log.removeIf(e -> tick - e.tick > 200);
        }
}
