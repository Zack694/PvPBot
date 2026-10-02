package dev.z.pvpbot.bot;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.z.pvpbot.BotConfig;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * v1.0.8 ADAPTATION ENGINE — "make it adapt even more to its opponents,
 * in Movement, Timings, etc."
 *
 * The DecisionMind picks INTENTS; this engine tunes the EXECUTION layer
 * (timings, distances, strafe tempo) per opponent, learned online and
 * persisted so the next fight against the same player starts pre-adapted.
 *
 * Six learned multipliers, all 1.0 = neutral, all bounded:
 *
 *   aggression — attack-band bias + anti-freeze floor. Losing to a passive
 *                opponent pushes our pace up; winning relaxes it.
 *   strafe     — combo-strafe intensity: hold time between direction flips
 *                and how eagerly we orbit while comboing.
 *   spacing    — backoff release distance multiplier. Rushers earn more
 *                space; passive opponents get squeezed.
 *   resets     — wtap frequency multiplier. Against heavy sprint-resetters
 *                we trade taps less and position more.
 *   airgame    — crit/midair roll multiplier. Jump-happy opponents meet
 *                more of our air hits.
 *   tempo      — click-band multiplier (&lt;1 = click earlier). Losing to a
 *                faster swinger speeds our band up; dominating slows it.
 *
 * HOW IT LEARNS (not scripted counters — online reinforcement):
 * every 2-second segment the damage balance (dealt - taken) scores the
 * current profile. Losing segments drift the profile toward the
 * style-counters the opponent's measured habits suggest (OpponentMemory
 * features feed the direction); winning segments freeze the profile;
 * even segments relax it back toward neutral. Multipliers only ever
 * MODIFY chances strictly between 0 and 1 — the 0.0 / 1.0 deterministic
 * config semantics from v1.0.7 are never touched.
 */
public final class AdaptiveEngine {

        private static final float LR = 0.10f;          // per 2s combat segment
        private static final int SEGMENT_TICKS = 40;    // 2s

        // the six learned multipliers
        private float aggression = 1f, strafe = 1f, spacing = 1f, resets = 1f, airgame = 1f, tempo = 1f;

        // bounds
        private static final float AGGR_MIN = 0.60f, AGGR_MAX = 1.50f;
        private static final float STRAFE_MIN = 0.60f, STRAFE_MAX = 1.80f;
        private static final float SPACE_MIN = 0.90f, SPACE_MAX = 1.30f;
        private static final float RESET_MIN = 0.50f, RESET_MAX = 1.50f;
        private static final float AIR_MIN = 0.50f, AIR_MAX = 1.60f;
        private static final float TEMPO_MIN = 0.85f, TEMPO_MAX = 1.15f;

        // per-opponent persistence: uuid -> [aggr, strafe, spacing, resets, air, tempo]
        private final Map<String, float[]> store = new HashMap<>();
        private final Map<String, String> names = new HashMap<>();
        private UUID oppId = null;
        private String oppName = "";

        // segment bookkeeping
        private float segDealt, segTaken;
        private int segTicks = 0;
        private String lastAdaptNote = "";

        /** Load persisted profiles from config/pvpbot/adapt.json. */
        public static AdaptiveEngine load() {
                AdaptiveEngine e = new AdaptiveEngine();
                try {
                        java.nio.file.Path p = BotConfig.dir().resolve("adapt.json");
                        if (Files.exists(p)) {
                                JsonObject root = com.google.gson.JsonParser.parseString(Files.readString(p)).getAsJsonObject();
                                if (root.has("opponents")) {
                                        for (Map.Entry<String, JsonElement> en : root.getAsJsonObject("opponents").entrySet()) {
                                                JsonObject o = en.getValue().getAsJsonObject();
                                                float[] v = new float[6];
                                                v[0] = o.has("aggr") ? o.get("aggr").getAsFloat() : 1f;
                                                v[1] = o.has("strafe") ? o.get("strafe").getAsFloat() : 1f;
                                                v[2] = o.has("spacing") ? o.get("spacing").getAsFloat() : 1f;
                                                v[3] = o.has("resets") ? o.get("resets").getAsFloat() : 1f;
                                                v[4] = o.has("air") ? o.get("air").getAsFloat() : 1f;
                                                v[5] = o.has("tempo") ? o.get("tempo").getAsFloat() : 1f;
                                                e.store.put(en.getKey(), v);
                                        }
                                }
                        }
                } catch (Exception ignored) {
                        // corrupt file — fresh profiles are fine
                }
                return e;
        }

        /** v2.3: round boundary — the damage totals restart at 0, so must the segment. */
        public void resetEpisode() {
                segDealt = segTaken = 0f;
                segTicks = 0;
        }

        /** Opponent switched (new duel / re-engaged a different player). */
        public void switchOpponent(UUID id, String name) {
                if (id != null && id.equals(oppId)) return; // same opponent — keep live profile
                oppId = id;
                oppName = name != null ? name : "";
                segDealt = segTaken = 0f;
                segTicks = 0;
                lastAdaptNote = "";
                float[] v = oppId == null ? null : store.get(oppId.toString());
                if (v != null && v.length >= 6) {
                        aggression = v[0]; strafe = v[1]; spacing = v[2]; resets = v[3]; airgame = v[4]; tempo = v[5];
                } else {
                        aggression = strafe = spacing = resets = airgame = tempo = 1f;
                }
        }

        /**
         * Called every combat tick. Accumulates the damage-balance segment and,
         * every {@value SEGMENT_TICKS} ticks, adapts the profile toward whatever
         * counters this opponent's measured style — but only the directions the
         * damage says we NEED (losing = adapt, winning = hold, even = relax).
         */
        public void tick(HitWatcher hits, OpponentMemory memory, boolean inCombat, long tick) {
                if (!inCombat || hits == null || memory == null) return;
                segTicks++;
                if (segTicks < SEGMENT_TICKS) return;
                segTicks = 0;

                float dd = hits.dmgDealt - segDealt;
                float dt = hits.dmgTaken - segTaken;
                segDealt = hits.dmgDealt;
                segTaken = hits.dmgTaken;
                boolean losing = dt > dd + 0.5f;
                boolean winning = dd > dt + 0.5f;

                if (!losing && !winning) {
                        // even trade — relax everything toward neutral slowly
                        aggression = relax(aggression, 0.15f, AGGR_MIN, AGGR_MAX);
                        strafe = relax(strafe, 0.15f, STRAFE_MIN, STRAFE_MAX);
                        spacing = relax(spacing, 0.15f, SPACE_MIN, SPACE_MAX);
                        resets = relax(resets, 0.15f, RESET_MIN, RESET_MAX);
                        airgame = relax(airgame, 0.15f, AIR_MIN, AIR_MAX);
                        tempo = relax(tempo, 0.15f, TEMPO_MIN, TEMPO_MAX);
                        return;
                }
                if (winning) {
                        // what we're doing works — mild relax of the extreme pushes only
                        aggression = relax(aggression, 0.10f, AGGR_MIN, AGGR_MAX);
                        return;
                }

                // ---- losing: steer the profile with the opponent's measured style ----
                float[] f = memory.features(); // 0 wtap 1 stap 2 jump 3 crit 4 aggr 5 avgDist 6 phJump 7 phRetreat
                StringBuilder note = new StringBuilder();
                if (f[2] > 0.35f) {                       // they jump often → meet them in the air
                        airgame = clamp(airgame + LR, AIR_MIN, AIR_MAX);
                        note.append("air+");
                }
                if (f[0] + f[1] > 0.30f) {                // heavy sprint-resetter → more room, fewer taps into trades
                        spacing = clamp(spacing + LR * 0.5f, SPACE_MIN, SPACE_MAX);
                        resets = clamp(resets - LR * 0.5f, RESET_MIN, RESET_MAX);
                        note.append(" space+ tap-");
                }
                if (f[4] > 0.5f) {                        // rusher → orbit harder, demand more space
                        strafe = clamp(strafe + LR, STRAFE_MIN, STRAFE_MAX);
                        spacing = clamp(spacing + LR * 0.5f, SPACE_MIN, SPACE_MAX);
                        note.append(" strafe+");
                }
                if (f[7] > 0.5f) {                        // they retreat after our hits → punish pace up
                        aggression = clamp(aggression + LR * 0.5f, AGGR_MIN, AGGR_MAX);
                        note.append(" press+");
                }
                // generic losing adjustments: click a bit earlier, hold ground firmer
                tempo = clamp(tempo - LR * 0.5f, TEMPO_MIN, TEMPO_MAX);
                aggression = clamp(aggression + LR * 0.5f, AGGR_MIN, AGGR_MAX);
                lastAdaptNote = note.length() == 0 ? "pace+" : note.toString().trim();
        }

/** Pull a multiplier a step back toward neutral 1.0. */
        private static float relax(float v, float k, float min, float max) {
                return clamp(v + (1f - v) * k, min, max);
        }

        private static float clamp(float v, float min, float max) {
                return Math.max(min, Math.min(max, v));
        }

        // ------------------------------------------------------------ execution hooks

        /** Attack-band multiplier from tempo + aggression (applied to the rolled band threshold). */
        public float bandMult() {
                return clamp(tempo - (aggression - 1f) * 0.35f, 0.80f, 1.10f);
        }

        /** WTap chance multiplier (only applied when the config chance is strictly between 0 and 1). */
        public float wtapMult() {
                return resets;
        }

        /** Crit/midair chance multiplier (same determinism guard). */
        public float airMult() {
                return airgame;
        }

        /** Backoff release distance multiplier. */
        public float spacingMult() {
                return spacing;
        }

        /** Combo-strafe hold ticks between direction flips — higher strafe = snappier orbit. */
        public int strafeHoldTicks() {
                return Math.max(5, Math.min(14, Math.round(9f / Math.max(0.5f, strafe))));
        }

        /** How eagerly the combo orbit re-tightens (0..1). */
        public float strafeBias() {
                return clamp((strafe - 1f) * 0.5f + 0.5f, 0f, 1f);
        }

        public String summary() {
                return String.format("adapt[aggr %.2f | strafe %.2f | space %.2f | tap %.2f | air %.2f | tempo %.2f]%s",
                                aggression, strafe, spacing, resets, airgame, tempo,
                                lastAdaptNote.isEmpty() ? "" : " — " + lastAdaptNote);
        }

        // ------------------------------------------------------------ persistence

        private void stash() {
                if (oppId == null) return;
                store.put(oppId.toString(), new float[]{aggression, strafe, spacing, resets, airgame, tempo});
                names.put(oppId.toString(), oppName);
        }

        /** Persist all profiles (called on the worker thread). */
        public void persist() {
                try {
                        stash(); // make sure the LIVE profile is stored before writing
                        JsonObject opponents = new JsonObject();
                        for (Map.Entry<String, float[]> en : store.entrySet()) {
                                JsonObject o = new JsonObject();
                                String n = names.get(en.getKey());
                                o.addProperty("name", n == null ? "" : n);
                                float[] v = en.getValue();
                                o.addProperty("aggr", v[0]);
                                o.addProperty("strafe", v[1]);
                                o.addProperty("spacing", v[2]);
                                o.addProperty("resets", v[3]);
                                o.addProperty("air", v[4]);
                                o.addProperty("tempo", v[5]);
                                opponents.add(en.getKey(), o);
                        }
                        JsonObject root = new JsonObject();
                        root.addProperty("version", 1);
                        root.add("opponents", opponents);
                        Files.createDirectories(BotConfig.dir());
                        Files.writeString(BotConfig.dir().resolve("adapt.json"), root.toString());
                } catch (Exception ignored) {
                }
        }
}
