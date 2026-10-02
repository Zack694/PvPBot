package dev.z.pvpbot.ml;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.z.pvpbot.BotConfig;
import dev.z.pvpbot.PvpBot;
import org.slf4j.Logger;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * v2.0 PHASE 2-a — MODEL STORE (the "models/" folder + binary snapshots).
 *
 * v1 kept exactly ONE brain on disk (config/pvpbot/model/policy.json) that
 * was overwritten after every episode: no history, no experiments, no way to
 * go back to yesterday's better brain. The ModelStore adds named snapshots
 * in a dedicated folder:
 *
 *   config/pvpbot/models/<name>.pbm    binary v2 (default, ~3.2 MB)
 *   config/pvpbot/models/<name>.json   legacy v1 policy JSON (still loads)
 *   config/pvpbot/models/active.json   {"active": "<name>"} — restored on launch
 *
 * A .pbm file is a small header (magic PVPBMDL, version, name, creation
 * timestamp, training stats) followed by the PVB2 binary weight blob (~1.0 MB for the policy) —
 * self-describing, one sequential read, no JSON parsing.
 *
 * Hot-swap: loading a model copies ONLY the weights into the live Dqn
 * ({@link Dqn#loadWeights}) — replay buffer, expert demos and the background
 * trainer keep running untouched. Swap mid-session without losing a thing.
 */
public final class ModelStore {

        private static final Logger LOGGER = PvpBot.LOGGER;
        private static final Gson GSON = new Gson();
        private static final byte[] MAGIC = {'P', 'V', 'P', 'B', 'M', 'D', 'L'};
        private static final byte VERSION = 2;
        private static final byte KIND_POLICY = 1;
        private static final byte KIND_AIM = 2;
        /** v2.0 PHASE 2-b: the four-head policy brain (PolicyNet). */
        private static final byte KIND_V2POLICY = 3;

        private ModelStore() {
        }

        // ------------------------------------------------------------- model info

        public static final class ModelInfo {
                public final String fileName;
                public final String name;
                public final boolean binary;
                /** 1 = legacy DQN policy, 2 = aim net, 3 = v2 four-head policy. */
                public final byte kind;
                public final long sizeBytes;
                public final long createdAtMs;
                public final int episodes, wins, losses, draws;
                public final long trainSteps;

                ModelInfo(String fileName, String name, boolean binary, byte kind, long sizeBytes,
                          long createdAtMs, int episodes, int wins, int losses, int draws, long trainSteps) {
                        this.fileName = fileName;
                        this.name = name;
                        this.binary = binary;
                        this.kind = kind;
                        this.sizeBytes = sizeBytes;
                        this.createdAtMs = createdAtMs;
                        this.episodes = episodes;
                        this.wins = wins;
                        this.losses = losses;
                        this.draws = draws;
                        this.trainSteps = trainSteps;
                }

                public String kindTag() {
                        return kind == KIND_V2POLICY ? "[v2]" : kind == KIND_POLICY ? "[v1]" : "[aim]";
                }

                public String statsLine() {
                        if (episodes < 0) {
                                return String.format("%s %s  (%d KB)", kindTag(), name, sizeBytes / 1024);
                        }
                        return String.format("%s %s  ep %d  W/L/D %d/%d/%d  (%d KB)",
                                        kindTag(), name, episodes, wins, losses, draws, sizeBytes / 1024);
                }
        }

        // ------------------------------------------------------------- paths

        public static Path dir() {
                Path d = BotConfig.dir().resolve("models");
                try {
                        Files.createDirectories(d);
                } catch (IOException ignored) {
                }
                return d;
        }

        private static Path activeFile() {
                return dir().resolve("active.json");
        }

        // ------------------------------------------------------------- list

        public static List<ModelInfo> list() {
                List<ModelInfo> out = new ArrayList<>();
                try (Stream<Path> s = Files.list(dir())) {
                        s.filter(p -> {
                                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                                return n.endsWith(".pbm") || n.endsWith(".json");
                        }).forEach(p -> {
                                String fn = p.getFileName().toString();
                                if (fn.equals("active.json")) {
                                        return;
                                }
                                try {
                                        out.add(readInfo(p));
                                } catch (Exception e) {
                                        LOGGER.warn("[pvpbot] skipping unreadable model {}: {}", fn, e.toString());
                                }
                        });
                } catch (IOException ignored) {
                }
                out.sort((a, b) -> Long.compare(b.createdAtMs, a.createdAtMs));
                return out;
        }

        private static ModelInfo readInfo(Path p) throws IOException {
                String fn = p.getFileName().toString();
                long size = Files.size(p);
                if (fn.toLowerCase(Locale.ROOT).endsWith(".pbm")) {
                        try (DataInputStream in = new DataInputStream(Files.newInputStream(p))) {
                                Header h = readHeader(in);
                                return new ModelInfo(fn, h.name, true, h.kind(), size, h.createdAtMs,
                                                h.episodes, h.wins, h.losses, h.draws, h.trainSteps);
                        }
                }
                // legacy JSON: name from file, stats unknown
                return new ModelInfo(fn, fn.endsWith(".json") ? fn.substring(0, fn.length() - 5) : fn,
                                false, KIND_POLICY, size, Files.getLastModifiedTime(p).toMillis(),
                                -1, 0, 0, 0, -1);
        }

        private record Header(String name, long createdAtMs, int episodes, int wins, int losses, int draws,
                              long trainSteps, byte kind) {
        }

        private static Header readHeader(DataInputStream in) throws IOException {
                byte[] magic = new byte[MAGIC.length];
                in.readFully(magic);
                if (!java.util.Arrays.equals(magic, MAGIC)) {
                        throw new IOException("bad magic — not a PVPBMDL file");
                }
                byte ver = in.readByte();
                if (ver != VERSION) {
                        throw new IOException("unsupported model version " + ver);
                }
                byte kind = in.readByte();
                String name = in.readUTF();
                long created = in.readLong();
                int ep = in.readInt(), w = in.readInt(), l = in.readInt(), d = in.readInt();
                long steps = in.readLong();
                return new Header(name, created, ep, w, l, d, steps, kind);
        }

        // ------------------------------------------------------------- save

        /** Sanitize a user-provided snapshot name into a safe file stem. */
        public static String sanitize(String raw) {
                String s = raw == null ? "" : raw.trim().replaceAll("[^A-Za-z0-9_\\- ]", "");
                s = s.replace(' ', '_');
                if (s.length() > 40) {
                        s = s.substring(0, 40);
                }
                return s.isEmpty() ? "model" : s;
        }

        /** Unique file stem (name, name-2, name-3 …). */
        private static Path unique(Path base) {
                if (!Files.exists(base)) {
                        return base;
                }
                String stem = base.getFileName().toString().replaceFirst("\\.pbm$", "");
                for (int i = 2; i < 1000; i++) {
                        Path p = dir().resolve(stem + "-" + i + ".pbm");
                        if (!Files.exists(p)) {
                                return p;
                        }
                }
                return dir().resolve(stem + "-" + System.currentTimeMillis() + ".pbm");
        }

        /**
         * Write a policy snapshot of the CURRENT live brain. Runs on the
         * caller's thread (the GUI/command routes this through the background
         * worker); returns the file name written.
         */
        public static String saveSnapshot(String rawName, PvpBot bot) throws IOException {
                var c = bot.controller();
                String name = sanitize(rawName);
                Path file = unique(dir().resolve(name + ".pbm"));
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(tmp))) {
                        out.write(MAGIC);
                        out.writeByte(VERSION);
                        out.writeByte(KIND_POLICY);
                        out.writeUTF(name);
                        out.writeLong(System.currentTimeMillis());
                        out.writeInt(c.episodesDone);
                        out.writeInt(c.wins);
                        out.writeInt(c.losses);
                        out.writeInt(c.draws);
                        out.writeLong(bot.dqn().getTrainSteps());
                        bot.dqn().saveBinary(out);
                }
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                setActive(file.getFileName().toString());
                LOGGER.info("[pvpbot] model snapshot saved: {}", file.getFileName());
                return file.getFileName().toString();
        }

        /**
         * v2.0 PHASE 2-b: write a FOUR-HEAD brain snapshot (kind 3). Same .pbm
         * container, different kind byte — the picker and the loader route by kind.
         */
        public static String saveSnapshotV2(String rawName, PvpBot bot) throws IOException {
                var c = bot.controller();
                String name = sanitize(rawName);
                Path file = unique(dir().resolve(name + ".pbm"));
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(tmp))) {
                        writeV2Header(out, name, c.episodesDone, c.wins, c.losses, c.draws,
                                        bot.policy().getTrainSteps());
                        bot.policy().saveBinary(out);
                }
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                setActive(file.getFileName().toString());
                LOGGER.info("[pvpbot] v2 model snapshot saved: {}", file.getFileName());
                return file.getFileName().toString();
        }

        /** Shared kind-3 header writer (also used by the v2 autosave in PvpBot). */
        public static void writeV2Header(DataOutputStream out, String name, int ep, int w, int l, int d,
                                         long trainSteps) throws IOException {
                out.write(MAGIC);
                out.writeByte(VERSION);
                out.writeByte(KIND_V2POLICY);
                out.writeUTF(name);
                out.writeLong(System.currentTimeMillis());
                out.writeInt(ep);
                out.writeInt(w);
                out.writeInt(l);
                out.writeInt(d);
                out.writeLong(trainSteps);
        }

        // ------------------------------------------------------------- load / hot-swap

        /**
         * Hot-swap the given model into the LIVE brain (weights only — replay
         * buffer + expert demos survive). Routes by the file's kind byte:
         * kind 1 = legacy DQN, kind 3 = v2 four-head brain. Returns a human
         * result message.
         */
        public static String loadInto(Path file, PvpBot bot) throws IOException {
                String d = loadIntoInner(file, bot);
                // v2.0.2: a v1 brain must never run under latched pure mode —
                // covers ALL load paths (command, ClickGUI picker, boot restore)
                try {
                        bot.controller().onModelSwapped(d);
                } catch (Throwable ignored) {
                }
                return d;
        }

        private static String loadIntoInner(Path file, PvpBot bot) throws IOException {
                String fn = file.getFileName().toString();
                if (fn.toLowerCase(Locale.ROOT).endsWith(".pbm")) {
                        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
                                Header h = readHeader(in);
                                if (h.kind() == KIND_V2POLICY) {
                                        PolicyNet net = PolicyNet.loadBinary(in);
                                        bot.policy().loadWeights(net);
                                        // v2.3: the brain's training volume rides along (exploration decay)
                                        bot.policy().setTrainSteps(h.trainSteps());
                                        setActive(fn);
                                        return "[v2] " + h.name();
                                }
                                if (h.kind() != KIND_POLICY) {
                                        throw new IOException("not a policy model");
                                }
                                NeuralNet net = NeuralNet.loadBinary(in);
                                int[] want = PvpBot.POLICY_ARCH;
                                if (!java.util.Arrays.equals(net.sizes, want)) {
                                        throw new IOException("arch mismatch: file " + java.util.Arrays.toString(net.sizes)
                                                        + " != running " + java.util.Arrays.toString(want));
                                }
                                bot.dqn().loadWeights(net);
                                setActive(fn);
                                return h.name();
                        }
                }
                // legacy v1 JSON policy
                JsonObject root = GSON.fromJson(Files.readString(file), JsonObject.class);
                NeuralNet net = NeuralNet.fromJson(root.getAsJsonObject("q"));
                int[] want = PvpBot.POLICY_ARCH;
                if (!java.util.Arrays.equals(net.sizes, want)) {
                        throw new IOException("arch mismatch: file " + java.util.Arrays.toString(net.sizes)
                                        + " != running " + java.util.Arrays.toString(want));
                }
                bot.dqn().loadWeights(net);
                setActive(fn);
                return fn.endsWith(".json") ? fn.substring(0, fn.length() - 5) : fn;
        }

        /** Read ONLY the kind-3 payload of a .pbm file (used by the v2 autosave restore). */
        public static PolicyNet readPolicyNet(Path file) throws IOException {
                try (java.io.InputStream raw = Files.newInputStream(file)) {
                        return readPolicyNet(raw);
                }
        }

        /** v2.3: read a kind-3 brain from any stream (bundled jar resource or file); steps carried. */
        public static PolicyNet readPolicyNet(java.io.InputStream raw) throws IOException {
                DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(raw));
                Header h = readHeader(in);
                if (h.kind() != KIND_V2POLICY) {
                        throw new IOException("not a v2 four-head model");
                }
                PolicyNet p = PolicyNet.loadBinary(in);
                p.setTrainSteps(h.trainSteps());
                return p;
        }

        public static void delete(String fileName) throws IOException {
                if (fileName == null || fileName.contains("/") || fileName.contains("\\")
                                || fileName.equals("active.json")) {
                        return;
                }
                Files.deleteIfExists(dir().resolve(fileName));
        }

        // ------------------------------------------------------------- active tracking

        private static void setActive(String fileName) {
                try {
                        JsonObject o = new JsonObject();
                        o.addProperty("active", fileName);
                        Files.writeString(activeFile(), GSON.toJson(o));
                } catch (IOException ignored) {
                }
        }

        public static String activeName() {
                try {
                        Path p = activeFile();
                        if (Files.exists(p)) {
                                JsonObject o = GSON.fromJson(Files.readString(p), JsonObject.class);
                                if (o != null && o.has("active")) {
                                        return o.get("active").getAsString();
                                }
                        }
                } catch (Exception ignored) {
                }
                return null;
        }

        /** Forget the active-model pointer (wipe). */
        public static void clearActive() {
                try {
                        Files.deleteIfExists(activeFile());
                } catch (IOException ignored) {
                }
        }

        /**
         * On startup: restore the last hot-swapped model (if it still exists).
         * Never throws — a broken active.json or model file falls back to the
         * autosaved brain silently.
         */
        public static void loadActiveIfAny(PvpBot bot) {
                try {
                        String name = activeName();
                        if (name == null) {
                                return;
                        }
                        Path p = dir().resolve(name);
                        if (!Files.exists(p)) {
                                LOGGER.warn("[pvpbot] active model {} missing — keeping autosaved brain", name);
                                return;
                        }
                        String display = loadInto(p, bot);
                        LOGGER.info("[pvpbot] restored active model '{}' from models/", display);
                } catch (Exception e) {
                        LOGGER.warn("[pvpbot] active model restore failed ({}): {}",
                                        activeName(), e.toString());
                }
        }
}
