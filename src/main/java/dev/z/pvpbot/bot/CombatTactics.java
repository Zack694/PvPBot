package dev.z.pvpbot.bot;

import dev.z.pvpbot.BotConfig;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Random;

/**
 * The micro-tech layer — the sword-PvP techniques a master player executes
 * around the policy's decisions. The DQN still owns every strategic choice;
 * these are the reflexes and reset mechanics layered on top:
 *
 *  - JUMP RESET: jump exactly 100-150ms (2-3 ticks) after WE get hit — never
 *    at any other time, never held. The only combat jump that is not an
 *    attack technique.
 *  - W-TAP (v1.0.5 user spec): SPRINTING + HIT + press S for ~0.6s + release.
 *    Pressing S drops sprint (sprint reset = bigger knockback next hit) and
 *    pulls us back a hair; releasing S re-engages W and fresh sprint. 80%
 *    pure S, 20% S+A / S+D diagonals for human variance. NO jump ever fires
 *    inside the window — the old crit/midair rolls leaking into taps looked
 *    like "w-tap jumps", that is fixed.
 *  - WALL ESCAPE (v1.0.5): when a knockback shoves us into a wall on our
 *    sides or behind (TerrainSense probes), we immediately pick the most
 *    OPEN direction — away from the wall AND away from the opponent — and
 *    sprint out of the corner. Cornered hard (wall behind AND both sides)
 *    adds a rate-limited escape hop.
 *  - ANTI-FREEZE FLOOR (v1.0.5): standing still inside reach is the worst
 *    thing a sword fighter can do. If the policy outputs idle for 8+ ticks
 *    while in reach and grounded, the floor forces a W chase until the
 *    policy moves again (the movement reward shaping retrains the habit out
 *    in parallel — this is the immediate safety net).
 *  - SNEAK HITS (v1.0.7 rework): the roll happens AT CLICK TIME — every
 *    attack about to fire rolls the sneak chance, so 1.0 really is "every
 *    hit is a shift-hit" and 0.0 is "never". Shift stays pressed ~3 ticks
 *    around the click; sometimes stacked with a jump. The SNEAK_TRAP
 *    intent from the DecisionMind boosts the roll. (v1.0.6 rolled the
 *    sneak on a random tick and the triggerbot click rarely coincided —
 *    the chance config looked dead.)
 *  - BACKOFF (v1.0.6 rework): if we end up INSIDE the opponent (~1.35
 *    blocks), create space — but ONLY until ~2.1 blocks, only for a hard
 *    capped 24 ticks, in a DIAGONAL ARC (SA/SD with a re-close step) that
 *    curves around walls instead of straight-backing into them. A rolling
 *    over-retreat governor also stops the DQN itself from S-drifting
 *    forever. Backing off NEVER jumps (v1.0.6 user rule) — jumps while
 *    retreating are suppressed in the controller.
 *  - STRAFE DISCIPLINE (optional, default OFF): when enabled it remaps
 *    strafe-only moves to FORWARD instead of standing still, and back-diagonals
 *    to plain S — it can never again freeze the bot or walk it backwards.
 */
public final class CombatTactics {

        // wtap variants (v1.0.5: S-tap family)
        public static final int WV_NONE = -1, WV_S = 0, WV_SA = 1, WV_SD = 2;

        private final BotConfig cfg;
        private final Random rng = new Random();

        // --- wtap state
        private int wtapTicksLeft = 0;
        private int wtapVariant = WV_NONE;
        private long lastWtapTick = -1000;

        // --- jump reset state
        private long jumpResetAtTick = -1;
        private long lastJumpResetTick = -1000;

        // --- sneak state
        private int sneakTicksLeft = 0;
        private long lastSneakHitTick = -1000;
        private boolean sneakJumpPending = false;

        // --- backoff state
        private boolean backoffActive = false;
        private long backoffStartTick = -1;
        private int backoffWobble = 0;
        // v1.0.6 arc-retreat pattern: mostly diagonal back, one re-close step
        // so the retreat is an ARC, not a straight line away from the fight
        private static final int[] BACKOFF_ARC = {
                        ActionSpace.M_SA, ActionSpace.M_SD, ActionSpace.M_S,
                        ActionSpace.M_SD, ActionSpace.M_SA, ActionSpace.M_SD,
                        ActionSpace.M_WD, ActionSpace.M_SA
        };

        // --- over-retreat governor (v1.0.6): stops the DQN's own S-drift
        private float retreatPressure = 0f;
        private int overRetreatTicks = 0;

        // --- wall escape state (v1.0.5)
        private int escapeTicksLeft = 0;
        private int escapeMove = ActionSpace.M_W;
        private long lastEscapeTick = -1000;
        private long lastEscapeJumpTick = -1000;
        private int escapeReeval = 0;

        // --- anti-freeze floor (v1.0.5)
        private int idleInReachTicks = 0;
        private boolean freezeFloorActive = false;

        // --- attack techniques
        private long lastCritJumpTick = -1000;
        private long lastMidAirJumpTick = -1000;
        private long midAirAttackUntilTick = -1;

        // --- inputs mirrored from BotController each tick (used by escape logic)
        private float[] blocked = new float[8];

        // --- v1.0.8 combo strafe state
        private int comboStrafeDir = 0;      // -1 = orbit left (A), +1 = orbit right (D), 0 = unset
        private int comboStrafeTicksLeft = 0;

        // --- v1.0.9 INTERCEPT CHASE state (user: "If the Opponent is Running away,
        // Make the Model Predict the Path Where It's Going and Sprint Jump there,
        // Only Sprint Jump chase when the Opponent is 10+ blocks far") ---
        private float chaseVxEma = 0f, chaseVzEma = 0f;  // their velocity EMA (blocks/tick)
        private boolean interceptChase = false;          // chase override active this tick
        private boolean sprintJumpChase = false;         // the 10+ blocks bunny-hop band
        private float chaseDist = 99f;                   // distance seen when the chase was decided
        private long lastChaseHopTick = -1000;

        // --- v1.0.8 adaptive engine (set by BotController; may be null in tests)
        public AdaptiveEngine adapt;

        // --- v1.0.8 model-decides votes (set by BotController each tick from the mind)
        /** COMBO_EXTEND intent driving — suppress the wtap so the combo stays locked. */
        public boolean wtapSuppressed = false;
        /** AIR_DENIAL intent driving — boost the midair roll when they're airborne. */
        public boolean airDenialBoost = false;
        /** DISENGAGE_HEAL / OPEN_FIELD intents driving — the chase override stands down. */
        public boolean mindWantsOpenSpace = false;

        // --- stats (HUD)
        public int wtapCount, jumpResetCount, sneakHitCount, backoffCount, midAirCount, escapeCount;

        public CombatTactics(BotConfig cfg) {
                this.cfg = cfg;
        }

        // ------------------------------------------------------------ event hooks (called by BotController)

        /** WE got hit — schedule the one true jump reset, 100-150ms out. */
        public void onHurt(long tick) {
                if (!cfg.jumpResetEnabled) return;
                int ms = cfg.jumpResetMinMs + rng.nextInt(Math.max(1, cfg.jumpResetMaxMs - cfg.jumpResetMinMs + 1));
                long at = tick + Math.max(1, Math.round(ms / 50.0)); // 100..150ms == 2..3 ticks
                // only the latest hit matters — never queue two resets
                jumpResetAtTick = at;
        }

        /**
         * WE landed a hit — roll the S-tap wtap. v1.0.5: requires we were
         * SPRINTING at the moment of the hit (sprint + hit + S + release),
         * and the tap holds S for the CONFIGURED window (wtapMinMs..wtapMaxMs,
         * default ~0.6s), then releases.
         *
         * v1.0.9 DETERMINISTIC FIX (user: "WTap when 1.0 should ALWAYS press S
         * with the set thingy in config cuz rn it ain't doing it"): at chance
         * >= 1.0 the roll is skipped AND the two soft votes that could veto it
         * (the mind's COMBO_EXTEND suppression, the adaptive engine's tap
         * multiplier) are ignored — 1.0 really is "every eligible hit taps S
         * for the configured duration". The sprint requirement is also relaxed
         * at 1.0: hits that land during a previous tap's S-window (sprint
         * momentarily down) still tap, so a combo of taps chains instead of
         * silently dying. Physical gates (range <= 3.2, rate) always apply.
         *
         * v1.0.10 (user: "It still doesn't instantly Press S When it Sprint
         * hits... the Fix for W Tapping is that when it Sprint hits, It stops
         * holding W and Holds/Presses S for the configured time and Goes back
         * Again and repeats"): at 1.0 the tap now starts THE SAME TICK as the
         * swing (onMySwing, below) instead of waiting for the server's damage
         * confirmation, and the variant is FORCED to straight S — no diagonals
         * eating the window. The loop is exactly: sprint hit -> W off, S held
         * for the configured ms -> W back -> next hit repeats it.
         */
        public void onMyHit(long tick, boolean sprintingAtHit, double distAtHit) {
                if (!cfg.wtapEnabled) return;
                if (distAtHit > 3.4) return;
                if (tick - lastWtapTick < 1) return;
                boolean deterministic = cfg.wtapChance >= 0.999f;
                // v1.0.11: the SPRINT REQUIREMENT IS GONE — the user is explicit
                // that the tap applies to ANY hit. The tap IS the sprint-reset
                // loop: each tap drops sprint for the S-window, and sprint needs
                // 1-2 ticks to re-engage after it, so combo hits landing inside
                // that lag were NEVER sprinting — and never tapped (the "Wtap
                // doesn't apply to ANY hit the model does" bug). Tapping S when
                // sprint is still re-engaging is harmless and keeps the chain:
                // sprint hit -> W off -> S for the configured ms -> W back -> repeat.
                // The mind's COMBO_EXTEND vote still vetoes mid-chance taps only.
                if (!deterministic && wtapSuppressed) return;
                float chance = cfg.wtapChance;
                if (chance > 0f && chance < 1f && adapt != null) {
                        chance = Math.min(1f, chance * adapt.wtapMult());
                }
                if (deterministic) {
                        // v2.3: at 1.0 the swing path (onMySwing) already opened this
                        // tap on the click tick — the damage confirmation 1-3 ticks
                        // later must not restart it (S was held too long, count x2)
                        if (wtapTicksLeft > 0 || tick - lastWtapTick < 6) return;
                        startTap(tick, WV_S); // v1.0.10: 1.0 = always STRAIGHT S
                        return;
                }
                if (rng.nextFloat() >= chance) return;
                float r = rng.nextFloat();
                if (r < cfg.wtapPureWChance) {
                        startTap(tick, WV_S);          // straight S-tap
                } else if (r < cfg.wtapPureWChance + (1f - cfg.wtapPureWChance) / 2f) {
                        startTap(tick, WV_SA);         // S+A diagonal
                } else {
                        startTap(tick, WV_SD);         // S+D diagonal
                }
        }

        /**
         * v1.0.10 INSTANT TAP — called by the controller the same tick a swing
         * is VERIFIED as a real, meter-draining attack (not waiting for the
         * damage packet). Deterministic (chance >= 1.0) taps start HERE so S
         * is physically pressed the tick after the click, like a human
         * W-tapping with the click. Mid-chance taps still wait for the
         * damage-confirm path so the roll stays honest.
         */
        public void onMySwing(long tick, double distAtHit) {
                if (!cfg.wtapEnabled) return;
                if (cfg.wtapChance < 0.999f) return; // probabilistic path uses onMyHit
                if (distAtHit > 3.4) return;         // v1.0.11: matches the S-window kill range
                if (tick - lastWtapTick < 1) return;
                startTap(tick, WV_S);
        }

        /** Open an S-tap window of the CONFIGURED duration (wtapMinMs..wtapMaxMs). */
        private void startTap(long tick, int variant) {
                wtapVariant = variant;
                int ms = cfg.wtapMinMs + rng.nextInt(Math.max(1, cfg.wtapMaxMs - cfg.wtapMinMs + 1));
                wtapTicksLeft = (int) Math.max(1, Math.round(ms / 50.0)); // 550..650ms == 11..13 ticks
                lastWtapTick = tick;
                wtapCount++;
        }

        /** Feed the latest terrain probes (8 directions relative to our yaw). */
        public void noteTerrain(TerrainSense terrain) {
                if (terrain != null) {
                        this.blocked = terrain.blocked;
                }
        }

        /**
         * Wall-escape trigger, called when WE got hit (knockback active).
         * Fires when a wall sits at our sides or behind within probe range —
         * exactly the "shoved into a wall sideways/backwards" situation.
         */
        public void onKnockedBack(long tick) {
                boolean sideWall = blocked[1] > 0.5f || blocked[2] > 0.5f
                                || blocked[6] > 0.5f || blocked[7] > 0.5f;
                boolean backWall = blocked[3] > 0.5f || blocked[4] > 0.5f || blocked[5] > 0.5f;
                if (sideWall || backWall) {
                        startEscape(tick);
                }
        }

        private void startEscape(long tick) {
                escapeTicksLeft = 10 + rng.nextInt(6); // hold 10-15 ticks, re-evaluated every 4
                escapeReeval = 0; // pick the escape direction on the very first tick
                lastEscapeTick = tick;
                escapeCount++;
        }

        /** @return true while a wall escape is driving the movement. */
        public boolean escapeActive() {
                return escapeTicksLeft > 0;
        }

        /** @return true while the wtap S-window is open (used to gate jumps). */
        public boolean wtapActive() {
                return wtapTicksLeft > 0;
        }

        /**
         * v2.3: +1 = strafe right, -1 = strafe left — the side that pushes me
         * further from their look direction (they must turn more to track me).
         */
        private static int escapeSideFromTheirAim(ClientPlayerEntity self, LivingEntity target) {
                double ty = Math.toRadians(target.getYaw());
                double lx = -Math.sin(ty), lz = Math.cos(ty);           // their horizontal look
                double dx = self.getX() - target.getX(), dz = self.getZ() - target.getZ();
                double along = dx * lx + dz * lz;
                double px = dx - along * lx, pz = dz - along * lz;      // my offset off their aim line
                double yr = Math.toRadians(self.getYaw());
                double rx = -Math.cos(yr), rz = -Math.sin(yr);          // my right
                double side = px * rx + pz * rz;
                if (Math.abs(side) < 1e-3) return (self.age / 20) % 2 == 0 ? 1 : -1;
                return side > 0 ? 1 : -1;
        }

        /**
         * v2.3: their OWN take-off (not a knockback launch) inside crit range
         * opens the crit-denial window until they land (max 14 ticks).
         */
        public void noteTarget(LivingEntity target, double horizontalDist, int comboTaken) {
                comboTakenNow = comboTaken;
                boolean air = !target.isOnGround();
                double vy = TargetMotion.of(target).y;
                if (air && !targetAirPrev && vy > 0.2 && target.hurtTime < 9
                                && horizontalDist > 1.2 && horizontalDist < 4.2) {
                        critDenialTicksLeft = 14;
                        critDenialCount++;
                }
                targetAirPrev = air;
        }

        /** v2.3: a crit-denial window is open (technique jumps stay off). */
        public boolean critDenialActive() {
                return critDenialTicksLeft > 0;
        }

        /** v2.3: the combo breaker is steering (technique jumps stay off; jump reset still fires). */
        public boolean comboBreakActive() {
                return comboBreakTicksLeft > 0;
        }

        private int comboTakenNow = 0;
        private int comboBreakTicksLeft = 0, comboBreakDir = 1;
        private int critDenialTicksLeft = 0;
        private boolean targetAirPrev = false;
        public int critDenialCount = 0;

        /** Pick the most open escape direction, weighted AWAY from the opponent. */
        private void pickEscapeDirection(ClientPlayerEntity self, LivingEntity target) {
                double awayX = self.getX() - target.getX();
                double awayZ = self.getZ() - target.getZ();
                double len = Math.sqrt(awayX * awayX + awayZ * awayZ);
                if (len < 1e-4) {
                        awayX = -MathHelper.sin((float) Math.toRadians(self.getYaw()));
                        awayZ = MathHelper.cos((float) Math.toRadians(self.getYaw()));
                        len = 1.0;
                }
                awayX /= len;
                awayZ /= len;
                int best = 0;
                float bestScore = Float.NEGATIVE_INFINITY;
                for (int i = 0; i < 8; i++) {
                        double ang = Math.toRadians(self.getYaw() + i * 45.0);
                        double dx = -Math.sin(ang), dz = Math.cos(ang);
                        float openness = blocked[i] > 0.5f ? -10f : 0f;   // never run INTO a wall
                        float away = (float) (dx * awayX + dz * awayZ);   // prefer running from the player too
                        float score = openness + away * 2f;
                        if (score > bestScore) {
                                bestScore = score;
                                best = i;
                        }
                }
                // probe index -> move combo (0 = our forward … 4 = our back)
                switch (best) {
                        case 0 -> escapeMove = ActionSpace.M_W;
                        case 1 -> escapeMove = ActionSpace.M_WD;
                        case 2 -> escapeMove = ActionSpace.M_D;
                        case 3 -> escapeMove = ActionSpace.M_SD;
                        case 4 -> escapeMove = ActionSpace.M_S;
                        case 5 -> escapeMove = ActionSpace.M_SA;
                        case 6 -> escapeMove = ActionSpace.M_A;
                        default -> escapeMove = ActionSpace.M_WA;
                }
        }

        /** Cornered hard (wall behind AND both sides) — hop out, rate-limited. */
        public boolean wantEscapeJump(long tick) {
                boolean cornered = (blocked[3] > 0.5f || blocked[4] > 0.5f || blocked[5] > 0.5f)
                                && blocked[1] + blocked[2] > 1f && blocked[6] + blocked[7] > 1f;
                if (!cornered) return false;
                if (tick - lastEscapeJumpTick < 20) return false;
                lastEscapeJumpTick = tick;
                return true;
        }

        // ------------------------------------------------------------ v1.0.9 intercept chase

        /**
         * PREDICTED-PATH CHASE (user spec). Tracks the opponent's velocity EMA,
         * solves where they will be (pos + v*t, t from a simple pursuit
         * equation) and walks the FORWARD-Hemisphere move combo whose
         * direction best matches the INTERCEPT bearing — diagonals included
         * ("I mean like going diagonally or something, just to Intersect the
         * Opponent"). The camera keeps tracking them for combat; the legs cut
         * the corner instead of trailing straight behind.
         *
         * Bands (config):
         *  - 10+ blocks: full sprint-jump chase (bunny-hop toward intercept)
         *  - chasePredictMinDist..10 while they FLEE: predicted-direction chase,
         *    no forced jumping ("Only Sprint Jump chase when the Opponent is
         *    10+ blocks far")
         *  - inside 4.5: normal combat movement, untouched
         */
        private void updateChase(ClientPlayerEntity self, LivingEntity target, double distH, long tick) {
                // velocity EMA — smooths their zigzag so a stutter-runner's AVERAGE
                // path is what we intercept
                float vx = (float) TargetMotion.of(target).x, vz = (float) TargetMotion.of(target).z;
                chaseVxEma += 0.15f * (vx - chaseVxEma);
                chaseVzEma += 0.15f * (vz - chaseVzEma);

                chaseDist = (float) distH;
                interceptChase = false;
                sprintJumpChase = false;
                if (!cfg.chasePredictEnabled || mindWantsOpenSpace) return;
                if (distH < cfg.chasePredictMinDist) return;

                // are they opening distance? (EMA velocity pointing away from me)
                double dx = self.getX() - target.getX(), dz = self.getZ() - target.getZ();
                double len = Math.max(1e-4, Math.sqrt(dx * dx + dz * dz));
                float away = (chaseVxEma * (float) (dx / len) + chaseVzEma * (float) (dz / len));
                boolean fleeing = away > 0.10f;
                boolean far = distH >= cfg.chaseSprintJumpMinDist;
                if (!far && !(fleeing && distH >= cfg.chasePredictMinDist)) return;

                interceptChase = true;
                sprintJumpChase = far;
        }

        /**
         * Movement combo whose direction best matches the intercept point,
         * forward hemisphere only (W / WA / WD / A / D — never S while chasing).
         * Wall-aware: if the chosen direction's probes are blocked, swing to
         * the mirror direction or plain forward.
         */
        private int interceptMove(ClientPlayerEntity self, LivingEntity target) {
                // pursuit solve: t = dist / (mySpeed + their away-speed)
                double dx = target.getX() - self.getX(), dz = target.getZ() - self.getZ();
                double dist = Math.max(0.5, Math.sqrt(dx * dx + dz * dz));
                double nx = dx / dist, nz = dz / dist;
                float away = chaseVxEma * (float) nx + chaseVzEma * (float) nz;
                float mySpeed = sprintJumpChase ? 0.36f : 0.30f; // sprint-jump vs sprint, blocks/tick
                float t = Math.max(0f, Math.min(40f, (float) (dist / Math.max(0.06, mySpeed + away))));
                // predicted position: their smoothed path ("going diagonally" emerges
                // naturally from the EMA direction)
                double px = target.getX() + chaseVxEma * t;
                double pz = target.getZ() + chaseVzEma * t;

                // bearing of the intercept point relative to MY yaw (same convention
                // as TerrainSense probes: 0 = forward, 1 = forward-right, 7 = forward-left)
                double bx = px - self.getX(), bz = pz - self.getZ();
                float bearing = (float) Math.toDegrees(Math.atan2(-bx, bz));
                float rel = MathHelper.wrapDegrees(bearing - self.getYaw());
                int i = (int) Math.floor(Math.round(rel / 45.0));
                i = ((i % 8) + 8) % 8;
                // clamp to the forward hemisphere — chasing never walks backward
                if (i >= 3 && i <= 5) i = rel > 0 ? 2 : 6;
                // wall-aware fallbacks
                if (i == 1 && (blocked[1] > 0.5f || blocked[2] > 0.5f)) i = 7;
                if (i == 7 && (blocked[7] > 0.5f || blocked[6] > 0.5f)) i = 1;
                if ((i == 1 || i == 7) && blocked[i] > 0.5f) i = 0;
                if (i == 2 && blocked[2] > 0.5f) i = 0;
                if (i == 6 && blocked[6] > 0.5f) i = 0;
                switch (i) {
                        case 1: return ActionSpace.M_WD;
                        case 2: return ActionSpace.M_D;
                        case 6: return ActionSpace.M_A;
                        case 7: return ActionSpace.M_WA;
                        default: return ActionSpace.M_W;
                }
        }

        /** @return true while the intercept chase is driving the movement. */
        public boolean chaseActive() {
                return interceptChase;
        }

        /** @return true while the 10+ blocks sprint-jump chase band is active. */
        public boolean sprintJumpChaseActive() {
                return sprintJumpChase;
        }

        /**
         * Bunny-hop cadence for the 10+ blocks chase — jump whenever grounded
         * on a 4-tick rhythm while sprinting toward the intercept (sprint-jump
         * is genuinely faster than plain sprint, and it reads as a normal
         * player's chase-hop, not a combat jump).
         */
        public boolean chaseJumpWanted(long tick, boolean grounded) {
                if (!sprintJumpChase || !grounded) return false;
                if (tick - lastChaseHopTick < 4) return false;
                lastChaseHopTick = tick;
                return true;
        }

        // ------------------------------------------------------------ per-tick resolution

        /**
         * @return the movement combo the bot should actually output this tick.
         * Priority: wall escape > backoff > wtap S-window > INTERCEPT CHASE
         * (v1.0.9: predicted-path pursuit at range) > over-retreat governor >
         * combo strafe > anti-freeze floor > strafe discipline (optional) >
         * far-lateral remap > DQN move. v1.0.9: {@code activeTrade} — the
         * exchange is live after ANY hit in either direction (see BotController).
         */
        public int movePolicy(int dqnMove, ClientPlayerEntity self, LivingEntity target,
                              long tick, double horizontalDist, int comboDealt, boolean activeTrade) {
                // v2.3: the jump-reset window is consumed by pollJumpReset() (the
                // controller's jump section) — movePolicy used to clear it first,
                // so the jump reset NEVER pressed jump while the HUD counted it.

                // ---- v1.0.9 chase model update (velocity EMA + band decision) —
                // runs every tick so the prediction stays warm even when a higher-
                // priority layer owns the legs right now
                updateChase(self, target, horizontalDist, tick);

                // ---- wall escape (v1.0.5): sprint out of corners, away from walls + player
                if (escapeTicksLeft > 0) {
                        escapeTicksLeft--;
                        if (--escapeReeval <= 0 || escapeMove == ActionSpace.M_NONE) {
                                pickEscapeDirection(self, target);
                                escapeReeval = 4;
                        }
                        boolean stillCornered = blocked[1] + blocked[2] + blocked[3]
                                        + blocked[5] + blocked[6] + blocked[7] > 0.5f;
                        if (escapeTicksLeft > 0 && stillCornered) {
                                return escapeMove;
                        }
                        escapeTicksLeft = 0;
                }

                // ---- v2.3 COMBO BREAKER: we have taken 2+ hits in a row. Running
                // straight back keeps us in their line (the classic way to get
                // comboed to death); a sprint-strafe toward the side their aim is
                // weakest makes them turn to follow, breaks the KB chain and lets
                // the jump reset + our TriggerBot trade back.
                if (cfg.comboBreaker && comboTakenNow >= 2 && horizontalDist < 4.0) {
                        if (comboBreakTicksLeft <= 0) {
                                comboBreakTicksLeft = 8 + rng.nextInt(5);
                                comboBreakDir = escapeSideFromTheirAim(self, target);
                        }
                        comboBreakTicksLeft--;
                        if (comboBreakDir > 0 && (blocked[1] > 0.5f || blocked[2] > 0.5f)) comboBreakDir = -1;
                        else if (comboBreakDir < 0 && (blocked[7] > 0.5f || blocked[6] > 0.5f)) comboBreakDir = 1;
                        return comboBreakDir > 0 ? ActionSpace.M_WD : ActionSpace.M_WA;
                }
                comboBreakTicksLeft = 0;

                // ---- v2.3 CRIT DENIAL: they took off (own jump, not knockback)
                // inside crit range. While they are airborne they cannot steer —
                // hold the EDGE of reach (~3 blocks) so the falling crit comes up
                // short or has to drift into our grounded sprint hit, and never
                // jump to trade crits. The TriggerBot clicks the moment they
                // enter reach.
                if (cfg.critDenial && critDenialTicksLeft > 0) {
                        critDenialTicksLeft--;
                        if (target.isOnGround()) {
                                critDenialTicksLeft = 0;
                        } else if (horizontalDist < 2.6) {
                                boolean wallBack = blocked[3] > 0.5f || blocked[4] > 0.5f || blocked[5] > 0.5f;
                                if (!wallBack) return (tick / 3) % 2 == 0 ? ActionSpace.M_SA : ActionSpace.M_SD;
                                return escapeSideFromTheirAim(self, target) > 0 ? ActionSpace.M_D : ActionSpace.M_A;
                        } else if (horizontalDist > 3.3) {
                                return ActionSpace.M_W;
                        } else {
                                return escapeSideFromTheirAim(self, target) > 0 ? ActionSpace.M_D : ActionSpace.M_A;
                        }
                }

                // ---- over-retreat governor (v1.0.6): the "backs up TOOOOO much"
                // fix. A rolling counter tracks how much the resolved movement has
                // been backward lately; past the threshold it forces forward for
                // 8 ticks. Wall escape and real backoff bypass it (they are
                // legitimate retreats with their own caps).
                retreatPressure = retreatPressure * 0.94f;
                if (overRetreatTicks > 0) overRetreatTicks--;

                // ---- backoff: too close means inside their swing arc — create space
                // v1.0.8: the release distance adapts per opponent (spacing profile):
                // rushers earn more room, passive opponents get squeezed.
                float backoffRelease = cfg.backoffReleaseDist;
                if (adapt != null) {
                        backoffRelease = Math.max(1.8f, Math.min(2.6f, backoffRelease * adapt.spacingMult()));
                }
                boolean free = cfg.classicFreeMovement; // v2.3.5: the brain owns the moveset
                if (cfg.backoffEnabled && !free) {
                        // v2.3: a capped-out backoff can no longer re-arm on the very
                        // next tick (a rusher kept the bot backpedalling forever with
                        // a 1-tick gap every 25 ticks) — 20 ticks of rest first.
                        if (!backoffActive && horizontalDist < cfg.tooCloseDist
                                        && tick - lastBackoffEndTick >= 20) {
                                backoffActive = true;
                                backoffStartTick = tick;
                                backoffCount++;
                        } else if (backoffActive
                                        && (horizontalDist >= backoffRelease
                                        || tick - backoffStartTick > Math.max(6, cfg.maxBackoffTicks))) {
                                backoffActive = false;
                                lastBackoffEndTick = tick;
                        }
                }

                if (backoffActive) {
                        // diagonal ARC retreat; curve around a wall behind instead
                        // of backing straight into it, with a re-close step in the mix
                        // v2.3: each arc segment is held 4 ticks (it flipped SA/SD every
                        // tick before — a visible A/D jitter instead of an arc)
                        backoffWobble = (int) ((tick - backoffStartTick) / 4);
                        int mv = BACKOFF_ARC[backoffWobble % BACKOFF_ARC.length];
                        if (mv == ActionSpace.M_S && (blocked[3] > 0.5f || blocked[4] > 0.5f || blocked[5] > 0.5f)) {
                                mv = backoffWobble % 2 == 0 ? ActionSpace.M_WA : ActionSpace.M_WD;
                        }
                        return mv;
                }

                // ---- v1.0.10 CRIT WINDOW: the jump-crit is airborne — RELEASE W
                // (no forward, no strafe, no anti-freeze floor, no chase). The bot
                // rises straight and the click is held for the DESCENT by the
                // controller, which is where vanilla crits land. Ends on landing.
                if (critWindowTicksLeft > 0) {
                        critWindowTicksLeft--;
                        if (self.isOnGround()) {
                                critWindowTicksLeft = 0; // landed — normal legs again
                        } else {
                                return ActionSpace.M_NONE;
                        }
                }

                // ---- wtap S-window (v1.0.5): press S (+maybe A/D), release after ~0.6s
                // v1.0.8: if the opponent left tap range mid-window, release S
                // immediately — holding S at a fleeing opponent is the
                // "wtap happens when he's far" bug.
                if (wtapTicksLeft > 0) {
                        if (horizontalDist > 3.4) {
                                wtapTicksLeft = 0;
                                wtapVariant = WV_NONE;
                        } else {
                                wtapTicksLeft--;
                                switch (wtapVariant) {
                                        case WV_SA: return ActionSpace.M_SA;
                                        case WV_SD: return ActionSpace.M_SD;
                                        default: return ActionSpace.M_S; // pure S-tap
                                }
                        }
                }

                // ---- v1.0.9 INTERCEPT CHASE: the opponent is 10+ blocks out, or
                // fleeing beyond 4.5 — walk the predicted path (diagonals included)
                // instead of trailing straight behind them. Jumping inside this
                // band is handled by chaseJumpWanted() from the controller.
                if (interceptChase) {
                        return interceptMove(self, target);
                }

                if (free) {
                        return dqnMove;
                }
                // ---- over-retreat enforcement (v1.0.6) — AFTER backoff/wtap so
                // legitimate S-moves are untouched; counts only the DQN's own
                // backward drift when we are NOT in a forced retreat.
                boolean dqnBackward = dqnMove == ActionSpace.M_S || dqnMove == ActionSpace.M_SA
                                || dqnMove == ActionSpace.M_SD;
                if (dqnBackward && horizontalDist < 4.5) {
                        retreatPressure += 1f;
                }
                if (retreatPressure > 10f && horizontalDist > 1.6f) {
                        overRetreatTicks = 8;
                        retreatPressure = 0f;
                }
                if (overRetreatTicks > 0) {
                        return (tick % 8) < 4 ? ActionSpace.M_W : ActionSpace.M_WA; // re-engage
                }

                // ---- v1.0.9 COMBO STRAFE (rework): the old gate asked for
                // comboDealt >= 1 — but comboDealt resets the instant WE take a
                // hit, so in a real trade-fight the orbit was almost always off.
                // It ALSO required the DQN to be holding a forward key at that
                // exact tick. Both together is why the user never saw WA/WD.
                // Now: ANY live exchange (hit dealt or taken within the last 2s)
                // inside combo range orbits on WA/WD — sprint stays forced on
                // (sprintHitOnly), so this is literally "strafe while sprint
                // hitting". The only veto is the policy itself retreating —
                // a learned S/SA/SD still wins. Wall-aware flips unchanged.
                if (cfg.comboStrafe && (comboDealt >= 1 || activeTrade)
                                && horizontalDist > 1.35 && horizontalDist < 3.4) {
                        boolean dqnRetreat = dqnMove == ActionSpace.M_S
                                        || dqnMove == ActionSpace.M_SA || dqnMove == ActionSpace.M_SD;
                        if (!dqnRetreat) {
                                if (--comboStrafeTicksLeft <= 0 || comboStrafeDir == 0) {
                                        int hold = adapt != null ? adapt.strafeHoldTicks() : 9;
                                        comboStrafeTicksLeft = hold;
                                        if (rng.nextFloat() < 0.55f) comboStrafeDir = -comboStrafeDir;
                                        if (comboStrafeDir == 0) comboStrafeDir = rng.nextBoolean() ? 1 : -1;
                                }
                                // wall-aware flip: right orbit blocked (probes 1 WD, 2 D) → go left, and vice versa
                                if (comboStrafeDir > 0 && (blocked[1] > 0.5f || blocked[2] > 0.5f)) comboStrafeDir = -1;
                                else if (comboStrafeDir < 0 && (blocked[7] > 0.5f || blocked[6] > 0.5f)) comboStrafeDir = 1;
                                if (comboStrafeTicksLeft > 0) {
                                        return comboStrafeDir > 0 ? ActionSpace.M_WD : ActionSpace.M_WA;
                                }
                        }
                }

                // ---- anti-freeze floor (v1.0.5): never idle inside reach.
                // v1.0.6: lunge/crit intents from the DecisionMind trigger it sooner.
                boolean inReach = horizontalDist <= 3.2 && target != null;
                int idleFloor = aggressionHint > 0 ? 4 : 8;
                if (inReach && self.isOnGround() && dqnMove == ActionSpace.M_NONE) {
                        if (++idleInReachTicks >= idleFloor) freezeFloorActive = true;
                } else {
                        idleInReachTicks = 0;
                        freezeFloorActive = false;
                }
                if (freezeFloorActive) {
                        return ActionSpace.M_W; // chase until the policy moves again
                }

                // ---- strafe discipline (optional, default OFF in v1.0.5).
                // Remapped: strafe-only becomes FORWARD (never stand still),
                // back-diagonals stay retreat (never cancel into a stall).
                if (cfg.strafeDiscipline && comboDealt <= 0) {
                        switch (dqnMove) {
                                case ActionSpace.M_A:
                                case ActionSpace.M_D:
                                        return ActionSpace.M_W;
                                case ActionSpace.M_WA:
                                case ActionSpace.M_WD:
                                        return ActionSpace.M_W;
                                default:
                                        return dqnMove;
                        }
                }
                // ---- v1.0.9 far-lateral remap: pure A/D at 4.5+ blocks is the
                // "strafes/dances while the opponent is SO FAR" look — convert it
                // to the forward-diagonal direction toward where they actually are.
                if (horizontalDist > 4.5f && !mindWantsOpenSpace
                                && (dqnMove == ActionSpace.M_A || dqnMove == ActionSpace.M_D)) {
                        return interceptMove(self, target);
                }
                return dqnMove;
        }

        /** @return true while a spacing backoff is driving the movement. */
        public boolean backoffActive() {
                return backoffActive;
        }

        /** @return true while the over-retreat governor is forcing forward. */
        public boolean overRetreatActive() {
                return overRetreatTicks > 0;
        }

        /** Should the bot press jump RIGHT NOW for the scheduled jump reset? */
        public boolean shouldJumpReset(long tick) {
                return cfg.jumpResetEnabled
                                && tick - lastJumpResetTick == 0 // fires exactly on the tick movePolicy stamped
                                && cfg.jumpResetEnabled;
        }

        /**
         * v2.3: jump-reset trigger. Fires on the first tick at/after the scheduled
         * time (configured 100-150 ms after the hit) on which we can jump. A
         * grounded knockback launches us (vy 0.4), so the old "grounded exactly
         * on the scheduled tick" rule almost never held — the press now waits
         * for the landing inside a 12-tick window (jump-on-landing breaks the
         * follow-up combo) and is counted only when it really fires.
         */
        public boolean pollJumpReset(long tick, boolean canJump) {
                if (jumpResetAtTick <= 0 || tick < jumpResetAtTick) return false;
                if (tick - jumpResetAtTick > 12) {
                        jumpResetAtTick = -1;
                        return false;
                }
                if (!canJump || tick - lastJumpResetTick < 4) return false;
                jumpResetAtTick = -1;
                lastJumpResetTick = tick;
                jumpResetCount++;
                return true;
        }

        // ---- attack techniques -------------------------------------------------

        // --- DecisionMind aggression hint (set by BotController each tick):
        // 1 = lunge/crit intent (jump + engage sooner), -1 = reset/space intent
        // (no technique jumps), 0 = neutral.
        public int aggressionHint = 0;

        private long lastBackoffEndTick = -1000; // v2.3: backoff re-arm cooldown

        /** Physical eligibility shared by the crit attempt paths. */
        private boolean critEligible(long tick) {
                if (aggressionHint < 0) return false;
                if (critDenialTicksLeft > 0 || comboBreakTicksLeft > 0) return false; // v2.3
                if (wtapTicksLeft > 0) return false; // v1.0.5: never jump inside the wtap window
                if (backoffActive) return false;     // v1.0.6: NEVER jump while backing off
                if (escapeTicksLeft > 0) return false;
                return tick - lastCritJumpTick >= cfg.critCooldownTicks;
        }

        /**
         * Roll a crit attempt (jump + hit on the way down) when the DQN itself
         * asked for a jump. v1.0.7 deterministic semantics: chance <= 0 NEVER
         * crits, chance >= 1.0 always (RNG skipped), between = probabilistic.
         * The CRIT intent triples the roll; the SPACE intent forbids it.
         */
        public boolean wantCritJump(long tick) {
                if (!critEligible(tick)) return false;
                float chance = Math.min(1f, Math.max(0f, cfg.critAttemptChance));
                if (chance <= 0f) return false;
                if (chance < 1f) {
                        chance = Math.min(1f, chance * (aggressionHint > 0 ? 3f : 1f));
                        // v1.0.8: adaptive air-game multiplier for this opponent
                        if (adapt != null) chance = Math.min(1f, chance * adapt.airMult());
                        if (rng.nextFloat() >= chance) return false;
                }
                lastCritJumpTick = tick;
                beginCritWindow(); // v1.0.10: DQN-path jump also gets the W-release window
                return true;
        }

        /**
         * v1.0.7: critAttemptChance >= 1.0 SELF-DRIVES crit attempts — the
         * bot jumps every time the crit cooldown is ready and it is in
         * reach, no DQN jump-bit required. This is what "crit chance = 1.0"
         * is supposed to feel like.
         */
        public boolean forceCritJump(long tick) {
                if (!critEligible(tick)) return false;
                lastCritJumpTick = tick;
                beginCritWindow();
                return true;
        }

        // ------------------------------------------------ v1.0.10 CRIT WINDOW

        /**
         * v1.0.10 CRIT WINDOW (user: "it should stop Holding/pressing W when
         * jump Hit since that's how crit works right?"). Vanilla crits need a
         * FALLING hit — and sprint/forward pressure makes the jump drift into
         * the target and land the hit on the way UP (a plain hit, crit wasted).
         * From the crit jump until landing, the movement layer now RELEASES W
         * entirely (no forward, no strafe, no anti-freeze floor) so the bot
         * rises straight and clicks only on the way DOWN. MidAir hits
         * (midAirChance, rising) are a separate path and stay untouched.
         */
        private int critWindowTicksLeft = 0;

        private void beginCritWindow() {
                critWindowTicksLeft = 14; // ~0.7s max — ends on landing anyway
        }

        /** True while a crit jump is in the air and W must stay released. */
        public boolean critWindowActive() {
                return critWindowTicksLeft > 0;
        }

        /** Crit cooldown ready (used by the self-drive gate). */
        public boolean critCooldownReady(long tick) {
                return tick - lastCritJumpTick >= cfg.critCooldownTicks;
        }

        /** Physical eligibility shared by the midair hit paths. */
        private boolean midAirEligible(long tick) {
                if (aggressionHint < 0) return false;
                if (comboBreakTicksLeft > 0) return false; // v2.3
                if (wtapTicksLeft > 0) return false;
                if (backoffActive) return false;
                if (escapeTicksLeft > 0) return false;
                return tick - lastMidAirJumpTick >= cfg.midAirCooldownTicks;
        }

        /** Roll a rising midair hit for pure knockback. v1.0.7: deterministic
         *  at the extremes — 0.0 never, >= 1.0 always (RNG skipped). */
        public boolean wantMidAir(long tick) {
                if (!midAirEligible(tick)) return false;
                float chance = Math.min(1f, Math.max(0f, cfg.midAirChance));
                if (chance <= 0f) return false;
                if (chance < 1f) {
                        // v1.0.8: AIR_DENIAL intent vote — the mind wants to meet their
                        // jumps in the air — triples the roll; adapt engine scales it too
                        if (airDenialBoost) chance = Math.min(1f, chance * 3f);
                        if (adapt != null) chance = Math.min(1f, chance * adapt.airMult());
                        if (rng.nextFloat() >= chance) return false;
                }
                lastMidAirJumpTick = tick;
                midAirAttackUntilTick = tick + 12;
                midAirCount++;
                return true;
        }

        /** v1.0.7: midAirChance >= 1.0 self-drives rising hits on its cooldown. */
        public boolean forceMidAir(long tick) {
                if (!midAirEligible(tick)) return false;
                lastMidAirJumpTick = tick;
                midAirAttackUntilTick = tick + 12;
                midAirCount++;
                return true;
        }

        /** @return true while a midair (rising) hit window is open. */
        public boolean midAirWindow(long tick) {
                return tick <= midAirAttackUntilTick;
        }

        /** @return true while a sneak window is holding shift around a click. */
        public boolean sneakWindowOpen() {
                return sneakTicksLeft > 0;
        }

        /**
         * v1.0.7: called AT CLICK TIME, right before an attack fires. Makes
         * THIS hit a sneak hit when the roll passes. Deterministic at the
         * extremes: chance 0.0 never sneaks, chance >= 1.0 ALWAYS sneaks
         * (cooldown ignored — 1.0 means every hit is a shift-hit); between
         * the extremes the cooldown rate-limits like before. The
         * SNEAK_TRAP intent still triples the roll.
         */
        public boolean trySneakForClick(long tick) {
                if (!cfg.sneakHitsEnabled) return false;
                if (sneakTicksLeft > 0) return true; // window already open — ride it
                if (wtapTicksLeft > 0) return false; // wtap window owns the legs
                if (backoffActive) return false;
                float chance = Math.min(1f, Math.max(0f, cfg.sneakHitChance));
                if (chance <= 0f) return false;
                if (chance < 1f) {
                        chance = Math.min(1f, chance * (sneakIntentBoost ? 3f : 1f));
                        if (tick - lastSneakHitTick < cfg.sneakHitCooldownTicks) return false;
                        if (rng.nextFloat() >= chance) return false;
                }
                boolean sneakJump = false;
                if (cfg.sneakJumpHitChance > 0f) {
                        float jch = Math.min(1f, cfg.sneakJumpHitChance);
                        sneakJump = jch >= 1f || rng.nextFloat() < jch;
                }
                lastSneakHitTick = tick;
                sneakTicksLeft = sneakJump ? 5 : 4; // shift covers the click tick + 2-3 after
                sneakJumpPending = sneakJump;
                sneakHitCount++;
                return true;
        }

        /** Set by the controller when the SNEAK intent is driving. */
        public boolean sneakIntentBoost = false;

        /** @return true while shift should be held (covers sneak + sneak-jump hits). */
        public boolean sneakActive(long tick) {
                if (sneakTicksLeft > 0) {
                        sneakTicksLeft--;
                        return true;
                }
                return false;
        }

        public boolean consumeSneakJump() {
                boolean v = sneakJumpPending;
                sneakJumpPending = false;
                return v;
        }

        public void resetFight() {
                wtapTicksLeft = 0;
                wtapVariant = WV_NONE;
                jumpResetAtTick = -1;
                sneakTicksLeft = 0;
                sneakJumpPending = false;
                backoffActive = false;
                lastBackoffEndTick = -1000;
                midAirAttackUntilTick = -1;
                escapeTicksLeft = 0;
                idleInReachTicks = 0;
                freezeFloorActive = false;
                retreatPressure = 0f;
                overRetreatTicks = 0;
                sneakIntentBoost = false;
                comboStrafeDir = 0;
                comboStrafeTicksLeft = 0;
                wtapSuppressed = false;
                airDenialBoost = false;
                mindWantsOpenSpace = false;
                chaseVxEma = 0f;
                chaseVzEma = 0f;
                interceptChase = false;
                sprintJumpChase = false;
                chaseDist = 99f;
                critWindowTicksLeft = 0; // v1.0.10
                comboBreakTicksLeft = 0;  // v2.3
                critDenialTicksLeft = 0;
                targetAirPrev = false;
                comboTakenNow = 0;
                lastChaseHopTick = -1000;
        }

        public String summary() {
                return String.format("wtaps %d | jumpresets %d | sneakhits %d | midairs %d | backoffs %d | escapes %d",
                                wtapCount, jumpResetCount, sneakHitCount, midAirCount, backoffCount, escapeCount);
        }
}
