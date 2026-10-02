package dev.z.pvpbot.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.z.pvpbot.BotConfig;
import dev.z.pvpbot.PvpBot;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.MathHelper;

import java.io.File;
import java.nio.file.Files;
import java.util.Random;

/**
 * v1.0.6 DECISION THINKING — the layer that turns "an action index" into
 * "hmm… should I sneak hit since he is kinda comboing me?".
 *
 * How it works (and why it is NOT a scripted list of strats):
 *
 *  1. SITUATION ENCODING — every deliberation builds a 13-dim situation
 *     vector from live measurements: being comboed, cooldown advantage,
 *     their swing telegraph, both HPs, spacing pressure, walls, whether
 *     they retreat or approach, attack readiness. No thresholds decide
 *     anything by themselves — the features only DESCRIBE the moment.
 *
 *  2. INTENT SCORING — twelve tactical intents (lunge, crit pressure,
 *     sneak trap, wtap pressure, defense reset, pocket hold, circle
 *     strafe, retreat recover, bait punish, chase down, poke slide,
 *     jitter stutter). Each intent's score is
 *
 *        w1 * situationFit + w2 * qAgreement + w3 * successEma + innovation
 *
 *     situationFit = sigmoid(w_vec . situation) where w_vec is ONLINE
 *     LEARNED (started from tiny hand seeds, updated every segment from
 *     the damage balance the intent actually produced — so "sneak hit
 *     while being comboed" only keeps its score if it has been PAYING
 *     OFF against these opponents).
 *     qAgreement = softmax over the DQN's own Q-values aggregated per
 *     intent family — the policy network's learned judgement directly
 *     votes on which intent is smart RIGHT NOW.
 *     successEma = recent damage-balance while this intent drove.
 *
 *  3. INNOVATION — with cfg.innovationChance the mind deliberately picks
 *     its LEAST-USED intent ("let me try something new"), and if that
 *     experiment wins trades, the learned weights drift toward it. New
 *     strats are discovered by exploration + reinforcement, not authored.
 *
 *  4. EXECUTION BIAS — the chosen intent only SOFTLY biases the DQN
 *     (cfg.decisionBias): when the policy's pick is far from the intent's
 *     action family, the mind may swap in the family's own best-Q action.
 *     The DQN stays the muscles; the mind is the inner voice.
 *
 *  5. THE THOUGHT — the chosen intent renders a human-readable line for
 *     the HUD ("he's comboing me — sneak hit to break his rhythm?") so
 *     the deliberation is visible, like a stream-of-consciousness.
 */
public final class DecisionMind {

        public enum Intent {
                HOLD_POCKET("POCKET", "hold the 2.3-3.3 pocket, punish his next whiff"),
                ENGAGE_LUNGE("LUNGE", "my cooldown is ready — engage and lunge in"),
                CRIT_PRESSURE("CRIT", "he's exposed — jump crit for the finish"),
                SNEAK_TRAP("SNEAK", "he's comboing me — sneak hit to break his rhythm?"),
                WTAP_PRESSURE("TAP", "trade, then S-tap for the extra knockback"),
                DEFENSE_RESET("RESET", "his swing is coming — reset and brace to jump it"),
                RETREAT_RECOVER("SPACE", "too close — create space before he restarts"),
                CIRCLE_STRAFE("CIRCLE", "circle-strafe him, stay off the rhythm"),
                // v1.0.7 — four new intents (12 total). Same learned machinery as
                // the originals: sigmoid situation-fit + DQN Q-vote + success EMA
                // + innovation exploration. Nothing here is a scripted combo.
                BAIT_PUNISH("BAIT", "step back half a block — bait his swing, punish the whiff"),
                CHASE_DOWN("CHASE", "he's running — run him down before he resets"),
                POKE_SLIDE("POKE", "stay at max reach — poke and slide out of his swing"),
                JITTER_STUTTER("JITTER", "stutter my strafe — desync his aim tracking"),
                // v1.0.8 — twenty more intents (32 total). Same learned machinery:
                // sigmoid situation-fit + DQN Q-vote + success EMA + innovation.
                // The new situation features (their airborne state, their swing
                // tempo, the height edge) let the mind reason about timing and
                // terrain, not just spacing.
                SHADOW_STEP("SHADOW", "his swing just whiffed — slide around his side"),
                RUSH_BREAK("RUSHBRK", "he's sprinting straight in — sidestep and make him miss"),
                CRIT_TRADE("CTRADE", "both cooldowns up, HP even — take the jump crit trade"),
                PUNISH_LULL("PUNISH", "his tempo just dropped — push in before he re-sprints"),
                SPRINT_LOCK("LOCK", "he lives on sprint resets — stay glued so the reset fails"),
                AIR_DENIAL("DENY", "he's airborne — time my hit for his descent"),
                HIGH_GROUND("HIGHGND", "I have the height — hold the edge and hit down"),
                CORRAL_WALL("CORRAL", "he's cornered — press, he has nowhere to run"),
                OPEN_FIELD("OPENF", "my back is near a wall — slide out to open ground"),
                COMBO_EXTEND("EXTEND", "combo is live — tight follow, no tap, keep him locked"),
                RESET_BREAK("RBREAK", "he's about to tap — hold W straight through his reset"),
                FEINT_LUNGE("FEINT", "half-step in, then out — make him swing at air"),
                ORBIT_HOLD("ORBIT", "orbit at reach — my cooldown needs a beat"),
                SNEAK_RESET("SRESET", "shift-hit through his combo — kills his sprint rhythm"),
                TRADE_STAND("TRADE", "my cooldown beats his — stand and trade hits"),
                DISENGAGE_HEAL("HEAL", "low and unpressured — open the gap, let regen work"),
                CORNER_BAIT("CBAIT", "give ground to open space, bait the overextend, explode out"),
                TEMPO_SPIKE("SPIKE", "his cooldown is down — burst clicks NOW"),
                LATERAL_DRAIN("DRAIN", "side passes — chip him sideways, never stop moving"),
                MIRROR_MATCH("MIRROR", "match his rhythm — answer his own game back at him");

                public final String tag;
                public final String thought;

                Intent(String tag, String thought) {
                        this.tag = tag;
                        this.thought = thought;
                }
        }

        public static final int N_INTENTS = Intent.values().length;
        // v1.0.8: 15 situation features + bias (was 12 + bias). The three new
        // dims (13 theyAirborne, 14 theirTempoFast, 15 heightEdge) load as 0
        // from older mind.json files and get learned from there — safe upgrade.
        public static final int N_FEAT = 16;

        private static final Intent[] ORDER = Intent.values();
        // move families per intent (ActionSpace move ids)
        private static final int[][] FAMILIES = {
                        {ActionSpace.M_W, ActionSpace.M_WA, ActionSpace.M_WD},          // HOLD_POCKET
                        {ActionSpace.M_W, ActionSpace.M_WA, ActionSpace.M_WD},          // ENGAGE_LUNGE
                        {ActionSpace.M_NONE, ActionSpace.M_W, ActionSpace.M_A, ActionSpace.M_D}, // CRIT
                        {ActionSpace.M_NONE, ActionSpace.M_A, ActionSpace.M_D, ActionSpace.M_S}, // SNEAK
                        {ActionSpace.M_W, ActionSpace.M_WA, ActionSpace.M_WD},          // WTAP
                        {ActionSpace.M_S, ActionSpace.M_SA, ActionSpace.M_SD},          // DEFENSE
                        {ActionSpace.M_S, ActionSpace.M_SA, ActionSpace.M_SD},          // RETREAT
                        {ActionSpace.M_A, ActionSpace.M_D, ActionSpace.M_WA, ActionSpace.M_WD},   // CIRCLE
                        {ActionSpace.M_S, ActionSpace.M_SA, ActionSpace.M_SD, ActionSpace.M_NONE},// BAIT (micro-retreat, then punish)
                        {ActionSpace.M_W, ActionSpace.M_WA, ActionSpace.M_WD},          // CHASE
                        {ActionSpace.M_WA, ActionSpace.M_WD, ActionSpace.M_A, ActionSpace.M_D},   // POKE (strafe at reach edge)
                        {ActionSpace.M_A, ActionSpace.M_D}                              // JITTER (strafe stutter)
                        ,
                        // ---- v1.0.8 families (order matches the enum) ----
                        {ActionSpace.M_A, ActionSpace.M_D, ActionSpace.M_WA, ActionSpace.M_WD},   // SHADOW (circle their whiff)
                        {ActionSpace.M_A, ActionSpace.M_D, ActionSpace.M_WA, ActionSpace.M_WD},   // RUSHBRK (sidestep)
                        {ActionSpace.M_W, ActionSpace.M_NONE},                          // CTRADE (commit)
                        {ActionSpace.M_W, ActionSpace.M_WA, ActionSpace.M_WD},          // PUNISH (push the lull)
                        {ActionSpace.M_W, ActionSpace.M_WA, ActionSpace.M_WD},          // LOCK (glue)
                        {ActionSpace.M_W, ActionSpace.M_NONE},                          // DENY (timing holds position)
                        {ActionSpace.M_NONE, ActionSpace.M_A, ActionSpace.M_D, ActionSpace.M_S},  // HIGHGND (edge hold)
                        {ActionSpace.M_W, ActionSpace.M_WA, ActionSpace.M_WD},          // CORRAL (press)
                        {ActionSpace.M_W, ActionSpace.M_WA, ActionSpace.M_WD},          // OPENF (slide out)
                        {ActionSpace.M_W, ActionSpace.M_WA, ActionSpace.M_WD},          // EXTEND (tight follow)
                        {ActionSpace.M_W},                                              // RBREAK (straight through)
                        {ActionSpace.M_WA, ActionSpace.M_WD, ActionSpace.M_A, ActionSpace.M_D},   // FEINT (in-out)
                        {ActionSpace.M_A, ActionSpace.M_D, ActionSpace.M_WA, ActionSpace.M_WD},   // ORBIT
                        {ActionSpace.M_NONE, ActionSpace.M_A, ActionSpace.M_D, ActionSpace.M_S},  // SRESET (shift-hit posture)
                        {ActionSpace.M_W, ActionSpace.M_NONE},                          // TRADE (stand ground)
                        {ActionSpace.M_S, ActionSpace.M_SA, ActionSpace.M_SD},          // HEAL (open the gap)
                        {ActionSpace.M_S, ActionSpace.M_SA, ActionSpace.M_SD, ActionSpace.M_NONE},// CBAIT (give ground)
                        {ActionSpace.M_W, ActionSpace.M_NONE, ActionSpace.M_A, ActionSpace.M_D},  // SPIKE (burst clicks)
                        {ActionSpace.M_A, ActionSpace.M_D, ActionSpace.M_WA, ActionSpace.M_WD},   // DRAIN (side passes)
                        {ActionSpace.M_W, ActionSpace.M_WA, ActionSpace.M_WD, ActionSpace.M_A, ActionSpace.M_D} // MIRROR
        };

        // learned state
        private final float[][] w = new float[N_INTENTS][N_FEAT];
        private final float[] successEma = new float[N_INTENTS];
        private final int[] usage = new int[N_INTENTS];
        private float baseline = 0f;

        // damage totals fed every tick (segment boundaries use the deltas)
        private float hitsDealt, hitsTaken;

        // live deliberation state
        private Intent current = Intent.HOLD_POCKET;
        private Intent lastDeliberated = Intent.HOLD_POCKET;
        private int intentHeldTicks = 0;
        private int sinceDeliberate = 0;
        private int suggestedAction = -1;
        private String thoughtLine = "";
        private String contextNote = "";
        private boolean innovative = false;

        // segment learning bookkeeping
        private float segDmgDealt, segDmgTaken;
        private final Random rng = new Random();

        // target velocity history for the situation model (approach/retreat)
        private float prevTheirTowardMe = 0f;
        // opponent label for MIRROR_MATCH thoughts (updated every deliberation)
        private String opponentLabel = "mirrored";

        public DecisionMind() {
                seedWeights();
        }

        /** Tiny hand seeds — ONLY starting priors; everything is overwritten
         *  by online learning within a few fights. */
        private void seedWeights() {
                w[Intent.SNEAK_TRAP.ordinal()][0] = 0.7f;      // being comboed
                w[Intent.CRIT_PRESSURE.ordinal()][5] = 0.7f;   // they are low
                w[Intent.ENGAGE_LUNGE.ordinal()][2] = 0.6f;    // cooldown advantage
                w[Intent.DEFENSE_RESET.ordinal()][3] = 0.6f;   // their swing telegraph
                w[Intent.RETREAT_RECOVER.ordinal()][4] = 0.5f; // I am low
                w[Intent.RETREAT_RECOVER.ordinal()][6] = 0.5f; // too close
                w[Intent.HOLD_POCKET.ordinal()][7] = 0.4f;     // comfortable spacing
                w[Intent.CIRCLE_STRAFE.ordinal()][1] = 0.3f;   // I am comboing
                w[Intent.WTAP_PRESSURE.ordinal()][2] = 0.3f;
                w[Intent.BAIT_PUNISH.ordinal()][3] = 0.55f;    // v1.0.7: his swing telegraph → bait it
                w[Intent.BAIT_PUNISH.ordinal()][7] = 0.2f;     //   works from the pocket
                w[Intent.CHASE_DOWN.ordinal()][10] = 0.6f;     // v1.0.7: they retreat → chase
                w[Intent.CHASE_DOWN.ordinal()][2] = 0.3f;      //   my cooldown ready
                w[Intent.POKE_SLIDE.ordinal()][7] = 0.45f;     // v1.0.7: pocket spacing → poke
                w[Intent.POKE_SLIDE.ordinal()][2] = 0.2f;
                w[Intent.JITTER_STUTTER.ordinal()][0] = 0.35f; // v1.0.7: being comboed → desync their aim
                w[Intent.JITTER_STUTTER.ordinal()][1] = 0.2f;
                // v1.0.8 seeds — tiny starting priors; learning overwrites quickly
                w[Intent.SHADOW_STEP.ordinal()][3] = 0.5f;     // his swing telegraph → circle the whiff
                w[Intent.SHADOW_STEP.ordinal()][7] = 0.25f;
                w[Intent.RUSH_BREAK.ordinal()][6] = 0.4f;      // too close + straight rush → sidestep
                w[Intent.RUSH_BREAK.ordinal()][0] = 0.3f;
                w[Intent.CRIT_TRADE.ordinal()][2] = 0.55f;     // cooldown advantage → take the trade
                w[Intent.CRIT_TRADE.ordinal()][11] = 0.3f;
                w[Intent.PUNISH_LULL.ordinal()][0] = 0.4f;     // after his combo his sprint is down → push
                w[Intent.PUNISH_LULL.ordinal()][2] = 0.3f;
                w[Intent.SPRINT_LOCK.ordinal()][14] = 0.5f;    // fast swinger → glue so resets fail
                w[Intent.SPRINT_LOCK.ordinal()][1] = 0.2f;
                w[Intent.AIR_DENIAL.ordinal()][13] = 0.7f;     // they're airborne → time the descent hit
                w[Intent.AIR_DENIAL.ordinal()][2] = 0.2f;
                w[Intent.HIGH_GROUND.ordinal()][15] = 0.6f;    // height edge → hold it
                w[Intent.HIGH_GROUND.ordinal()][5] = 0.2f;
                w[Intent.CORRAL_WALL.ordinal()][10] = 0.45f;   // they retreat → press (corner prior)
                w[Intent.CORRAL_WALL.ordinal()][2] = 0.25f;
                w[Intent.OPEN_FIELD.ordinal()][9] = 0.7f;      // wall behind me → reposition first
                w[Intent.OPEN_FIELD.ordinal()][4] = 0.2f;
                w[Intent.COMBO_EXTEND.ordinal()][1] = 0.65f;   // I'm comboing → tight follow
                w[Intent.RESET_BREAK.ordinal()][3] = 0.3f;     // through his tap window
                w[Intent.RESET_BREAK.ordinal()][2] = 0.3f;
                w[Intent.FEINT_LUNGE.ordinal()][3] = 0.35f;    // his arm is up → bait it
                w[Intent.FEINT_LUNGE.ordinal()][7] = 0.3f;
                w[Intent.ORBIT_HOLD.ordinal()][7] = 0.35f;     // pocket spacing → orbit
                w[Intent.ORBIT_HOLD.ordinal()][2] = -0.35f;    // cooldown BEHIND → don't commit
                w[Intent.SNEAK_RESET.ordinal()][0] = 0.6f;     // being comboed → shift-hit break
                w[Intent.TRADE_STAND.ordinal()][2] = 0.5f;     // favored cooldown → stand ground
                w[Intent.TRADE_STAND.ordinal()][1] = 0.2f;
                w[Intent.DISENGAGE_HEAL.ordinal()][4] = 0.65f; // low HP → open the gap
                w[Intent.DISENGAGE_HEAL.ordinal()][10] = 0.3f;
                w[Intent.CORNER_BAIT.ordinal()][0] = 0.4f;     // comboed → give ground, then punish
                w[Intent.CORNER_BAIT.ordinal()][4] = 0.3f;
                w[Intent.TEMPO_SPIKE.ordinal()][2] = 0.6f;     // his cooldown down → burst
                w[Intent.LATERAL_DRAIN.ordinal()][1] = 0.25f;  // chipping → side passes
                w[Intent.LATERAL_DRAIN.ordinal()][7] = 0.3f;
                w[Intent.MIRROR_MATCH.ordinal()][14] = 0.3f;   // tempo/air → mirror his game
                w[Intent.MIRROR_MATCH.ordinal()][13] = 0.2f;
        }

        // ------------------------------------------------------------ features

        /**
         * Live situation vector. 0..12: beingComboed, comboingThem,
         * cooldownAdvantage, theirSwingTelegraph, lowSelfHp, lowTheirHp,
         * tooClose, pocketComfort, tooFar, wallBehind, theyRetreat, myReady, bias.
         */
        private float[] situation(ClientPlayerEntity self, LivingEntity target, HitWatcher hits,
                                  TerrainSense terrain, float dist, long tick) {
                float[] f = new float[N_FEAT];
                f[0] = MathHelper.clamp(hits.comboTaken / 3f, 0f, 1.5f);
                f[1] = MathHelper.clamp(hits.comboDealt / 3f, 0f, 1.5f);
                float myCharge = self.getAttackCooldownProgress(0.0f);
                float theirFresh = MathHelper.clamp((tick - hits.theirLastAttackTick) / 12.5f, 0f, 1f);
                f[2] = MathHelper.clamp(myCharge - theirFresh, -1f, 1f);
                f[3] = target.handSwinging && dist <= 3.6f ? 1f : 0f;
                f[4] = self.getHealth() < 10f ? (10f - self.getHealth()) / 10f : 0f;
                f[5] = target.getHealth() < 10f ? (10f - target.getHealth()) / 10f : 0f;
                f[6] = dist < 1.6f ? (float) Math.min(1.0, (1.6 - dist) / 0.9) : 0f;
                f[7] = dist >= 2.2f && dist <= 3.4f ? 1f : 0f;
                f[8] = dist > 3.6f ? MathHelper.clamp((float) (dist - 3.6) / 2.5f, 0f, 1f) : 0f;
                f[9] = terrain != null && (terrain.blocked[3] > 0.5f || terrain.blocked[4] > 0.5f || terrain.blocked[5] > 0.5f) ? 1f : 0f;
                // they retreat: their velocity pointing away from me (smoothed)
                double dx = self.getX() - target.getX(), dz = self.getZ() - target.getZ();
                double len = Math.sqrt(dx * dx + dz * dz);
                float away = len < 1e-4 ? 0f : (float) (TargetMotion.of(target).x * dx / len + TargetMotion.of(target).z * dz / len);
                float towardMe = -away;
                f[10] = MathHelper.clamp(away * 4f, -1f, 1f) > 0.25f
                                ? MathHelper.clamp(away * 4f, 0f, 1f) : 0f;
                f[11] = myCharge;
                // v1.0.8 — timing & terrain features the mind reasons with
                f[13] = target.isOnGround() ? 0f
                                : (TargetMotion.of(target).y > 0.05 ? 1f : 0.5f); // theyAirborne (rising = prime denial)
                f[14] = hits.theirAttackIntervalTicks > 0f
                                ? MathHelper.clamp((14f - hits.theirAttackIntervalTicks) / 8f, 0f, 1f)
                                : 0f;                                          // theirTempoFast (fast swinger)
                f[15] = MathHelper.clamp((float) ((self.getY() - target.getY()) / 1.5), 0f, 1f); // heightEdge
                f[12] = 1f; // bias
                prevTheirTowardMe = towardMe;
                return f;
        }

        // ------------------------------------------------------------ deliberation

        /**
         * Runs at ~2Hz (every {@code deliberateEvery} ticks) + on emergencies.
         * {@code qs} are the policy's own Q-values for the CURRENT state —
         * the DQN gets a direct vote on which intent is best right now.
         */
        public void think(ClientPlayerEntity self, LivingEntity target, HitWatcher hits,
                          OpponentMemory memory, TerrainSense terrain, long tick,
                          float[] qs, int deliberateEvery) {
                if (target == null) {
                        current = Intent.HOLD_POCKET;
                        thoughtLine = "";
                        return;
                }
                sinceDeliberate++;
                // v2.3: emergencies re-deliberate at most every 3 ticks (low HP is a
                // STATE — the old check re-thought at 20 Hz for the rest of the fight,
                // flickering every technique vote)
                boolean emergency = (hits.comboTaken >= 3 || self.getHealth() <= 6f) && sinceDeliberate >= 3;
                if (sinceDeliberate < deliberateEvery && !emergency) {
                        intentHeldTicks++;
                        return;
                }
                sinceDeliberate = 0;

                BotConfig cfg = safeCfg();
                if (memory != null && !memory.opponentName.isEmpty()) opponentLabel = memory.opponentName;
                double dist = Math.sqrt(Math.pow(target.getX() - self.getX(), 2) + Math.pow(target.getZ() - self.getZ(), 2));
                float[] f = situation(self, target, hits, terrain, (float) dist, tick);

                // close the previous learning segment BEFORE switching
                float[] fit = new float[N_INTENTS];
                for (int i = 0; i < N_INTENTS; i++) {
                        fit[i] = sigmoid(dot(w[i], f));
                }
                float[] qAgree = qSoftmax(qs);

                boolean explore = cfg != null && rng.nextFloat() < cfg.innovationChance;
                int best = current.ordinal();
                float bestScore = Float.NEGATIVE_INFINITY;
                if (explore) {
                        // innovation: try the LEAST-used intent — discover new strats
                        int least = 0;
                        for (int i = 0; i < N_INTENTS; i++) {
                                if (usage[i] < usage[least]) least = i;
                        }
                        if (least != current.ordinal()) {
                                best = least;
                                bestScore = 1f; // semantic: exploration choice
                        }
                }
                if (!explore || best == current.ordinal()) {
                        for (int i = 0; i < N_INTENTS; i++) {
                                float score = 1.6f * fit[i] + 1.2f * qAgree[i]
                                                + 0.8f * MathHelper.clamp(successEma[i], -1f, 1f);
                                // hysteresis: the held intent gets a small bonus, challengers
                                // must be CLEARLY better to switch mid-exchange
                                if (i == current.ordinal() && intentHeldTicks < 20) score += 0.15f;
                                if (score > bestScore) {
                                        bestScore = score;
                                        best = i;
                                }
                        }
                }

                Intent chosen = ORDER[best];
                innovative = explore;
                // the segment belongs to the intent that was DRIVING until now —
                // close its books before the switch
                learnSegment(current, f);
                current = chosen;
                usage[best]++;
                intentHeldTicks = 0;
                lastDeliberated = chosen;
                suggestedAction = bestFamilyAction(qs, best, cfg);
                buildThought(self, target, hits, chosen, f);
        }

        /** Close the previous segment: damage balance -> successEma + weight update. */
        private void learnSegment(Intent chosen, float[] f) {
                float dd = hitsDealt - segDmgDealt;
                float dt = hitsTaken - segDmgTaken;
                segDmgDealt = hitsDealt;
                segDmgTaken = hitsTaken;
                if (dd == 0f && dt == 0f) return; // nothing observed — no signal
                float r = MathHelper.clamp((dd - dt) / 4f, -1f, 1f);
                successEma[chosen.ordinal()] += 0.2f * (r - successEma[chosen.ordinal()]);
                float err = r - baseline;
                baseline += 0.1f * (r - baseline);
                float lr = 0.06f;
                for (int j = 0; j < N_FEAT; j++) {
                        float upd = lr * err * f[j];
                        w[chosen.ordinal()][j] = MathHelper.clamp(w[chosen.ordinal()][j] + upd, -2.5f, 2.5f);
                }
        }

        /** Softmax over the mean Q of each intent's representative actions. */
        private float[] qSoftmax(float[] qs) {
                float[] agg = new float[N_INTENTS];
                for (int i = 0; i < N_INTENTS; i++) {
                        agg[i] = meanFamilyQ(qs, i);
                }
                float max = Float.NEGATIVE_INFINITY;
                for (float v : agg) max = Math.max(max, v);
                float[] p = new float[N_INTENTS];
                float sum = 0f;
                for (int i = 0; i < N_INTENTS; i++) {
                        p[i] = (float) Math.exp((agg[i] - max) / 1.5f);
                        sum += p[i];
                }
                for (int i = 0; i < N_INTENTS; i++) p[i] /= sum;
                return p;
        }

        private float meanFamilyQ(float[] qs, int intent) {
                int[] fam = FAMILIES[intent];
                boolean jump = intent == Intent.CRIT_PRESSURE.ordinal();
                boolean sprint = !nonSprintIntent(intent);
                float sum = 0f;
                int n = 0;
                for (int m : fam) {
                        sum += qs[ActionSpace.encode(m, sprint, jump, true)];
                        n++;
                        if (n >= 3) break; // sample 3 per family — cheap
                }
                return n == 0 ? 0f : sum / n;
        }

        /** Intents whose families fight WITHOUT sprint (shift/retreat styles). */
        private static boolean nonSprintIntent(int intent) {
                return intent == Intent.RETREAT_RECOVER.ordinal()
                                || intent == Intent.SNEAK_TRAP.ordinal()
                                || intent == Intent.BAIT_PUNISH.ordinal()
                                || intent == Intent.DEFENSE_RESET.ordinal()
                                // v1.0.8 shift/stand/posture intents
                                || intent == Intent.SNEAK_RESET.ordinal()
                                || intent == Intent.DISENGAGE_HEAL.ordinal()
                                || intent == Intent.CORNER_BAIT.ordinal()
                                || intent == Intent.HIGH_GROUND.ordinal();
        }

        /** The intent family's own best-Q action (the "suggested" move). */
        private int bestFamilyAction(float[] qs, int intent, BotConfig cfg) {
                int[] fam = FAMILIES[intent];
                boolean jump = intent == Intent.CRIT_PRESSURE.ordinal();
                boolean sprint = !nonSprintIntent(intent);
                int best = -1;
                float bq = Float.NEGATIVE_INFINITY;
                for (int m : fam) {
                        int a = ActionSpace.encode(m, sprint, jump, true);
                        if (qs[a] > bq) {
                                bq = qs[a];
                                best = a;
                        }
                }
                return best;
        }

        private void buildThought(ClientPlayerEntity self, LivingEntity target, HitWatcher hits,
                                  Intent intent, float[] f) {
                String base = intent.thought;
                String ctx = "";
                switch (intent) {
                        case SNEAK_TRAP -> ctx = String.format("combo x%d against me", hits.comboTaken);
                        case CRIT_PRESSURE -> ctx = String.format("he's at %.0f HP", target.getHealth());
                        case ENGAGE_LUNGE -> ctx = String.format("%.1fm out", self.distanceTo(target));
                        case RETREAT_RECOVER -> ctx = String.format("%.1fm is too deep", self.distanceTo(target));
                        case DEFENSE_RESET -> ctx = String.format("his swings every %.0fms", hits.theirAttackIntervalTicks * 50f);
                        case HOLD_POCKET -> ctx = String.format("pocket %.1fm", self.distanceTo(target));
                        case CIRCLE_STRAFE -> ctx = String.format("combo x%d on him", hits.comboDealt);
                        case WTAP_PRESSURE -> ctx = String.format("sprint %s", self.isSprinting() ? "up" : "ready");
                        case BAIT_PUNISH -> ctx = String.format("his swing arm is up (%.0fms rhythm)", hits.theirAttackIntervalTicks * 50f);
                        case CHASE_DOWN -> ctx = String.format("%.1fm and opening", self.distanceTo(target));
                        case POKE_SLIDE -> ctx = String.format("edge of reach %.1fm", self.distanceTo(target));
                        case JITTER_STUTTER -> ctx = String.format("combo x%d against me", hits.comboTaken);
                        // v1.0.8 contexts
                        case SHADOW_STEP -> ctx = String.format("his swing whiffed (%.0fms rhythm)", hits.theirAttackIntervalTicks * 50f);
                        case RUSH_BREAK -> ctx = String.format("he's closing at %.1fm", self.distanceTo(target));
                        case CRIT_TRADE -> ctx = String.format("my charge %.0f%%", self.getAttackCooldownProgress(0.0f) * 100f);
                        case PUNISH_LULL -> ctx = String.format("his tempo %.0fms", hits.theirAttackIntervalTicks * 50f);
                        case SPRINT_LOCK -> ctx = String.format("his reset rhythm %.0fms", hits.theirAttackIntervalTicks * 50f);
                        case AIR_DENIAL -> ctx = target.isOnGround() ? "waiting for his jump" : "he's in the air NOW";
                        case HIGH_GROUND -> ctx = String.format("%.1fm above him", Math.max(0, self.getY() - target.getY()));
                        case CORRAL_WALL -> ctx = String.format("he's backing (%.0f%%)", f[10] * 100f);
                        case OPEN_FIELD -> ctx = "don't fight cornered";
                        case COMBO_EXTEND -> ctx = String.format("combo x%d live", hits.comboDealt);
                        case RESET_BREAK -> ctx = "hold W through his tap";
                        case FEINT_LUNGE -> ctx = "bait the swing, then punish";
                        case ORBIT_HOLD -> ctx = String.format("my charge %.0f%%", self.getAttackCooldownProgress(0.0f) * 100f);
                        case SNEAK_RESET -> ctx = String.format("combo x%d against me", hits.comboTaken);
                        case TRADE_STAND -> ctx = String.format("he's at %.0f HP", target.getHealth());
                        case DISENGAGE_HEAL -> ctx = String.format("%.0f HP — need regen", self.getHealth());
                        case CORNER_BAIT -> ctx = "bait the overextend";
                        case TEMPO_SPIKE -> ctx = "his cooldown is down NOW";
                        case LATERAL_DRAIN -> ctx = String.format("chip x%d on him", hits.comboDealt);
                        case MIRROR_MATCH -> ctx = String.format("his style: %s", opponentLabel);
                }
                contextNote = ctx;
                thoughtLine = (innovative ? "new idea… " : "hmm… ") + base;
        }

        // ------------------------------------------------------------ execution hooks

        /**
         * Soft bias: with probability decisionBias, an action OUTSIDE the
         * intent's family is swapped for the family's own best-Q action.
         */
        public int applyBias(int chosen, float decisionBias) {
                if (suggestedAction < 0 || decisionBias <= 0f) return chosen;
                if (moveInFamily(current.ordinal(), ActionSpace.moveOf(chosen))) return chosen;
                if (rng.nextFloat() < decisionBias) return suggestedAction;
                return chosen;
        }

        public boolean moveInFamily(int intentIdx, int move) {
                for (int m : FAMILIES[intentIdx]) {
                        if (m == move) return true;
                }
                return false;
        }

        /** Called every combat tick so segments see fresh damage totals. */
        public void noteDamage(float dmgDealt, float dmgTaken) {
                this.hitsDealt = dmgDealt;
                this.hitsTaken = dmgTaken;
        }

        public void resetEpisode() {
                // v2.3: HitWatcher totals restart at 0 every round — keeping last
                // round's totals as the baseline credited the first segment with
                // the INVERTED result of the previous round
                hitsDealt = hitsTaken = 0f;
                segDmgDealt = 0f;
                segDmgTaken = 0f;
                intentHeldTicks = 0;
                sinceDeliberate = 0;
                current = Intent.HOLD_POCKET;
                thoughtLine = "";
                suggestedAction = -1;
                innovative = false;
        }

        // ------------------------------------------------------------ getters

        public Intent intent() {
                return current;
        }

        public String thought() {
                return thoughtLine;
        }

        public String context() {
                return contextNote;
        }

        public boolean wantsSneak() {
                // v1.0.8: both shift-hit intents drive the sneak roll (the model
                // decides WHEN to sneak within the config chance — see BotController)
                return current == Intent.SNEAK_TRAP || current == Intent.SNEAK_RESET;
        }

        public boolean wantsCrit() {
                return current == Intent.CRIT_PRESSURE || current == Intent.CRIT_TRADE;
        }

        public boolean wantsRetreat() {
                return current == Intent.RETREAT_RECOVER || current == Intent.DEFENSE_RESET;
        }

        public boolean wantsLunge() {
                return current == Intent.ENGAGE_LUNGE || current == Intent.WTAP_PRESSURE
                                || current == Intent.CHASE_DOWN;
        }

        public boolean wantsBait() {
                return current == Intent.BAIT_PUNISH || current == Intent.FEINT_LUNGE
                                || current == Intent.CORNER_BAIT;
        }

        public boolean wantsPoke() {
                return current == Intent.POKE_SLIDE || current == Intent.JITTER_STUTTER
                                || current == Intent.LATERAL_DRAIN;
        }

        // ---- v1.0.8 model-decides hooks --------------------------------------
        // The mind (situation weights + DQN Q-vote) now votes on WHEN each
        // technique happens, inside the config chance ceilings. These are
        // votes, not scripts — the learned weights decide them.

        /** COMBO_EXTEND driving: suppress the wtap so the combo stays locked. */
        public boolean wantsComboExtend() {
                return current == Intent.COMBO_EXTEND;
        }

        /** AIR_DENIAL driving: meet their jumps with midair/descent hits. */
        public boolean wantsAirDenial() {
                return current == Intent.AIR_DENIAL;
        }

        /** TEMPO_SPIKE driving: click earlier (punish their cooldown window). */
        public boolean wantsTempoSpike() {
                return current == Intent.TEMPO_SPIKE;
        }

        /** DISENGAGE_HEAL / OPEN_FIELD driving: legitimately open distance. */
        public boolean wantsOpenSpace() {
                return current == Intent.DISENGAGE_HEAL || current == Intent.OPEN_FIELD;
        }

        public int aggressionHint() {
                if (wantsLunge() || wantsCrit()) return 1;
                if (current == Intent.PUNISH_LULL || current == Intent.SPRINT_LOCK
                                || current == Intent.COMBO_EXTEND || current == Intent.RESET_BREAK
                                || current == Intent.TEMPO_SPIKE || current == Intent.CORRAL_WALL
                                || current == Intent.TRADE_STAND || current == Intent.RUSH_BREAK) return 1;
                if (wantsRetreat() || wantsBait() || wantsOpenSpace()) return -1;
                return 0;
        }

        private static float sigmoid(float x) {
                return 1f / (1f + (float) Math.exp(-x));
        }

        private static float dot(float[] a, float[] b) {
                float s = 0f;
                for (int i = 0; i < a.length && i < b.length; i++) s += a[i] * b[i];
                return s;
        }

        private static BotConfig safeCfg() {
                try {
                        return PvpBot.get().config();
                } catch (Throwable t) {
                        return null;
                }
        }

        // ------------------------------------------------------------ persistence

        public JsonObject toJson() {
                JsonObject root = new JsonObject();
                root.addProperty("version", 2); // v1.0.8: 32 intents, 16 features
                root.addProperty("baseline", baseline);
                JsonArray arr = new JsonArray();
                for (int i = 0; i < N_INTENTS; i++) {
                        JsonObject io = new JsonObject();
                        io.addProperty("intent", ORDER[i].name());
                        io.addProperty("ema", successEma[i]);
                        io.addProperty("uses", usage[i]);
                        JsonArray wa = new JsonArray();
                        for (int j = 0; j < N_FEAT; j++) wa.add(w[i][j]);
                        io.add("w", wa);
                        arr.add(io);
                }
                root.add("intents", arr);
                return root;
        }

        public void loadJson(JsonObject root) {
                try {
                        if (root == null || !root.has("intents")) return;
                        baseline = root.has("baseline") ? root.get("baseline").getAsFloat() : 0f;
                        JsonArray arr = root.getAsJsonArray("intents");
                        for (JsonElement el : arr) {
                                JsonObject io = el.getAsJsonObject();
                                int idx = -1;
                                for (int i = 0; i < N_INTENTS; i++) {
                                        if (ORDER[i].name().equals(io.get("intent").getAsString())) idx = i;
                                }
                                if (idx < 0) continue;
                                successEma[idx] = io.has("ema") ? io.get("ema").getAsFloat() : 0f;
                                usage[idx] = io.has("uses") ? io.get("uses").getAsInt() : 0;
                                JsonArray wa = io.getAsJsonArray("w");
                                for (int j = 0; j < N_FEAT && j < wa.size(); j++) {
                                        w[idx][j] = wa.get(j).getAsFloat();
                                }
                        }
                } catch (Exception ignored) {
                        // corrupt mind file — the seeds are fine
                }
        }

        /** Persist to config/pvpbot/mind.json (called on the worker thread). */
        public void persist() {
                try {
                        java.nio.file.Path dir = BotConfig.dir();
                        Files.createDirectories(dir);
                        Files.writeString(dir.resolve("mind.json"), toJson().toString());
                } catch (Exception ignored) {
                }
        }

        public static DecisionMind load() {
                DecisionMind m = new DecisionMind();
                try {
                        java.nio.file.Path p = BotConfig.dir().resolve("mind.json");
                        if (Files.exists(p)) {
                                String s = Files.readString(p);
                                JsonObject o = com.google.gson.JsonParser.parseString(s).getAsJsonObject();
                                m.loadJson(o);
                        }
                } catch (Exception ignored) {
                }
                return m;
        }
}
