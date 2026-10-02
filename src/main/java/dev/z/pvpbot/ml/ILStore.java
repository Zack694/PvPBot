package dev.z.pvpbot.ml;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.z.pvpbot.BotConfig;
import dev.z.pvpbot.PvpBot;
import dev.z.pvpbot.bot.ActionSpace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * v2.2.0 — IMITATION LEARNING FROM VIDEO (the "IL Model").
 *
 * The external extractor (scripts/il_extract.py in the source zip) turns
 * recorded PvP videos — good pvper gameplay with a visible keystrokes HUD —
 * into *.jsonl session files. Every line is one decision step:
 *
 *   {"s":  [104 floats],           v2 (PolicyNet) state, Perception.DIM_V3 layout
 *    "s1": [64 floats],            v1 (Dqn) state, Perception.DIM layout
 *    "mv": 0-8,                    move combo (ActionSpace.M_*)
 *    "sp": 0|1, "jp": 0|1, "sn": 0|1,
 *    "ay": float, "ap": float,     aim label (normalized by pureAimMaxDeg)
 *    "ck": 0|1,                    click label
 *    "r":  float,                  shaped reward for this step
 *    "done": false}                true on the last step of a round
 *
 * loadAll() feeds every transition into BOTH brains' expert rings:
 *   - PolicyNet.rememberExpert (the four-head DQfD ring, 104-dim),
 *   - Dqn.rememberExpert       (the v1 DQfD ring, 64-dim),
 * so margin-cloned demonstrations steer both models during ALL later
 * training — exactly what human-train does live, but sourced from video.
 *
 * The extractor reconstructs the state vectors approximately (screen
 * geometry + keystroke HUD OCR + crosshair/cooldown analysis); imperfect
 * labels are FINE for DQfD — the large-margin clone steers, and the live
 * RL loop corrects residual drift. Sessions land in
 * .minecraft/config/pvpbot/il/ and load at boot (cfg.ilAutoLoad), on
 * demand (/pvpbot il load), or from the ClickGUI MODELS tab.
 */
public final class ILStore {

        private int sessions = 0;
        private int transitions = 0;
        private int skipped = 0;
        private String lastError = "";
        private boolean loaded = false;
        public volatile float trainLoss = Float.NaN;

        public static ILStore get() {
                if (INSTANCE == null) INSTANCE = new ILStore();
                return INSTANCE;
        }

        private static ILStore INSTANCE;

        private ILStore() {
        }

        /** Directory the extractor drops session files into. */
        public static Path dir() {
                return BotConfig.dir().resolve("il");
        }

        public synchronized boolean isLoaded() {
                return loaded && transitions > 0;
        }

        /** One-line human status (commands + UI). */
        public synchronized String statusLine() {
                if (!loaded) {
                        return "IL: NOT LOADED — " + (lastError.isEmpty()
                                        ? "no data loaded yet (/pvpbot il load)" : lastError);
                }
                if (transitions == 0) {
                        return "IL: 0 sessions — drop extractor *.jsonl in config/pvpbot/il/ "
                                        + "(see il-tools/IL-GUIDE.md in the mod zip), then /pvpbot il load";
                }
                return String.format("IL: LOADED — %d sessions, %d transitions (%d skipped lines)",
                                sessions, transitions, skipped);
        }

        /**
         * Scan the il/ folder and feed every valid transition into both expert
         * rings. Safe to call repeatedly (the rings dedupe nothing — loading
         * twice doubles the demo weight; /pvpbot il clear resets the rings).
         */
        public synchronized String loadAll(PvpBot bot) {
                sessions = 0;
                transitions = 0;
                skipped = 0;
                lastError = "";
                Path dir = dir();
                if (!Files.isDirectory(dir)) {
                        loaded = true;
                        lastError = "no il/ folder yet — run the video extractor first";
                        return statusLine();
                }
                List<Path> files = new ArrayList<>();
                try (Stream<Path> s = Files.list(dir)) {
                        s.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".jsonl"))
                                        .sorted().forEach(files::add);
                } catch (IOException e) {
                        loaded = true;
                        lastError = "cannot read il/ folder: " + e.getMessage();
                        return statusLine();
                }
                if (files.isEmpty()) {
                        loaded = true;
                        lastError = "no *.jsonl sessions in il/ — run the video extractor first";
                        return statusLine();
                }
                int aimMax = Math.max(1, Math.round(bot.config().pureAimMaxDeg));
                for (Path f : files) {
                        int[] counts = loadSession(f, bot, aimMax);
                        sessions += counts[0] > 0 ? 1 : 0;
                        transitions += counts[0];
                        skipped += counts[1];
                }
                loaded = true;
                return statusLine();
        }

        /** @return {valid transitions, skipped lines} for one session file */
        private int[] loadSession(Path f, PvpBot bot, int aimMax) {
                int ok = 0, bad = 0;
                try {
                        List<String> lines = Files.readAllLines(f);
                        JsonObject prev = null;
                        for (String raw : lines) {
                                String line = raw.trim();
                                if (line.isEmpty() || line.startsWith("//") || line.startsWith("#")) {
                                        continue;
                                }
                                JsonObject o;
                                try {
                                        o = JsonParser.parseString(line).getAsJsonObject();
                                } catch (Exception e) {
                                        bad++;
                                        prev = null;
                                        continue;
                                }
                                float[] s = readVec(o.get("s"), PolicyNet.IN_DIM);
                                float[] s1 = readVec(o.get("s1"), 64);
                                if (s == null || s1 == null) {
                                        bad++;
                                        prev = null;
                                        continue;
                                }
                                int mv = o.has("mv") ? o.get("mv").getAsInt() : 0;
                                boolean sp = o.has("sp") && o.get("sp").getAsInt() != 0;
                                boolean jp = o.has("jp") && o.get("jp").getAsInt() != 0;
                                boolean sn = o.has("sn") && o.get("sn").getAsInt() != 0;
                                float ay = o.has("ay") ? o.get("ay").getAsFloat() : 0f;
                                float ap = o.has("ap") ? o.get("ap").getAsFloat() : 0f;
                                float ck = o.has("ck") ? o.get("ck").getAsFloat() : 0f;
                                float r = o.has("r") ? o.get("r").getAsFloat() : 0f;
                                boolean done = o.has("done") && o.get("done").getAsBoolean();
                                if (mv < 0 || mv >= ActionSpace.MOVES) {
                                        bad++;
                                        prev = null;
                                        continue;
                                }
                                // next state: explicit "s2", else the next line's "s"
                                float[] sNext = readVec(o.get("s2"), PolicyNet.IN_DIM);
                                if (sNext == null && prev != null) {
                                        sNext = readVec(prev.get("s"), PolicyNet.IN_DIM);
                                }
                                // TD transitions need a next state; terminal ones do not
                                if (sNext == null && !done) {
                                        // keep as open chain: remember with itself is wrong —
                                        // skip until a successor exists
                                        prev = o;
                                        continue;
                                }
                                // ---- v2 four-head expert ring (104-dim) ----
                                bot.policy().rememberExpert(s, mv, sp, jp, sn,
                                                clampN(ay), clampN(ap), ck, r,
                                                sNext != null ? sNext : s, done);
                                // ---- v1 DQN expert ring (64-dim) ----
                                float[] s1Next = sliceOrNull(o.get("s1n"), 64);
                                if (s1Next == null && prev != null) {
                                        s1Next = sliceVec(prev.get("s1"));
                                }
                                int v1Action = ActionSpace.encode(mv, sp, jp, ck >= 0.5f);
                                if (s1Next != null || done) {
                                        bot.dqn().rememberExpert(s1, v1Action, r,
                                                        s1Next != null ? s1Next : s1, done);
                                }
                                prev = o;
                                ok++;
                        }
                } catch (IOException e) {
                        bad++;
                } catch (Exception e) {
                        bad++;
                }
                return new int[]{ok, bad};
        }

        private static float clampN(float v) {
                return v < -1f ? -1f : Math.min(1f, v);
        }

        private static float[] readVec(com.google.gson.JsonElement el, int dim) {
                float[] v = sliceVec(el);
                return v == null || v.length != dim ? null : v;
        }

        private static float[] sliceOrNull(com.google.gson.JsonElement el, int dim) {
                float[] v = sliceVec(el);
                return v == null || v.length != dim ? null : v;
        }

        private static float[] sliceVec(com.google.gson.JsonElement el) {
                if (el == null || !el.isJsonArray()) return null;
                JsonArray a = el.getAsJsonArray();
                float[] out = new float[a.size()];
                for (int i = 0; i < out.length; i++) {
                        try {
                                out[i] = a.get(i).isJsonPrimitive() ? a.get(i).getAsFloat() : 0f;
                        } catch (Exception e) {
                                return null;
                        }
                }
                return out;
        }

        /**
         * Train both brains on the loaded demonstrations (background worker):
         * margin-cloned BC through the DQfD expert rings, done in bursts so the
         * game thread never stalls. Reports the last policy loss.
         */
        public synchronized void train(PvpBot bot, int bursts) {
                if (!isLoaded() || bursts <= 0) return;
                final int b = Math.min(400, bursts);
                final float lr = bot.config().lrRapid;
                final int batch = Math.max(16, bot.config().trainBatch);
                final float ratio = 0.9f; // IL bursts are demo-heavy by design
                final float margin = bot.config().imitationMargin;
                PvpBot.worker().execute(() -> {
                        float last = Float.NaN;
                        for (int i = 0; i < b; i++) {
                                float l = bot.policy().trainStep(batch, lr, ratio, margin);
                                if (!Float.isNaN(l)) last = l;
                                bot.dqn().trainStep(batch, lr, ratio);
                        }
                        bot.policy().syncTarget();
                        bot.dqn().syncTarget();
                        trainLoss = last;
                        if (bot.controller() != null) {
                                bot.controller().announcePublic(String.format(
                                                "IL training done — %d bursts, policy loss %s. The demos now steer every later training step.",
                                                b, Float.isNaN(last) ? "-" : String.format("%.4f", last)));
                        }
                });
        }

        /** Reset the expert rings (does NOT touch the trained weights). */
        public synchronized void clear(PvpBot bot) {
                bot.dqn().clearExpert();
                bot.policy().clearExpert();
                sessions = 0;
                transitions = 0;
                skipped = 0;
                loaded = false;
        }
}
