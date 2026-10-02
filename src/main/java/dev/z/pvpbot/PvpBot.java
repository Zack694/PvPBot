package dev.z.pvpbot;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.z.pvpbot.bot.BotController;
import dev.z.pvpbot.ml.Dqn;
import dev.z.pvpbot.ml.ModelStore;
import dev.z.pvpbot.ml.NeuralNet;
import dev.z.pvpbot.ml.PolicyNet;
import dev.z.pvpbot.ui.BotHud;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Singleton wiring: config, neural networks (policy DQN + aim net),
 * controller, HUD. Models live in config/pvpbot/model so training survives
 * restarts; bundled pre-trained defaults are copied there on first run.
 */
public final class PvpBot {

        public static final Logger LOGGER = LoggerFactory.getLogger("pvpbot");

        private static PvpBot instance;

        /**
         * ONE background worker for everything heavy: DQN training steps and
         * model serialization/writes. v1.0.4 ran these on the game thread —
         * a batch-16 step through the 480x480 brain costs 15-40ms and every
         * episode end serialized + wrote a 6.4MB JSON, which stuttered the
         * game exactly when training (the "frame gen" lag). Single thread =
         * FIFO, so a queued save always lands AFTER the queued training burst.
         */
        private static final java.util.concurrent.ExecutorService WORKER =
                        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                                Thread t = new Thread(r, "PvPBot-Worker");
                                t.setDaemon(true);
                                return t;
                        });

        private final BotConfig config;
        private final Dqn dqn;
        private final NeuralNet aimNet;
        private final PolicyNet policy;   // v2.0 PHASE 2-b: the four-head brain
        private final BotController controller;
        private final BotHud hud = new BotHud();
        private final Gson gson = new Gson();
        private volatile boolean saveQueued = false;

        /** v1.0.4 brain: 64-dim perception -> 480x480 hidden -> 72 actions.
         *  296,712 parameters ≈ 1.13 MB of float32 weights (the "1 MB brain"). */
        /** v2.3.6: the classic brain reads the same parity-tested ObsV4 (100 dims) as pure mode. */
        public static final int[] POLICY_ARCH = {dev.z.pvpbot.ml.obs.ObsV4.DIM, 480, 480, 72};

        /** v2.3: offline-pretrained four-head brain shipped inside the jar. */
        public static final String BUNDLED_V2 = "/assets/pvpbot/model/brain_v2.pbm";
        private boolean bundledV2Loaded = false;

        /** True when the running v2 brain came from the bundled pretrained file. */
        public boolean bundledV2Loaded() {
                return bundledV2Loaded;
        }

        private PvpBot() {
                this.config = BotConfig.load();
                this.dqn = buildPolicy();
                this.aimNet = buildAim();
                this.policy = buildPolicyV2();
                this.controller = new BotController(config, net.minecraft.client.MinecraftClient.getInstance(), dqn, aimNet, policy);
                dqn.setImitationMargin(config.imitationMargin);
                loadMeta();
                // v2.0 PHASE 2-a: restore the last hot-swapped snapshot (if any)
                ModelStore.loadActiveIfAny(this);
                // v2.2.0: auto-load extractor sessions for the IL pipeline — the
                // "IL Model says it's not loaded" fix: demos now load THEMSELVES
                // at boot (background worker), with an honest status either way.
                if (config.ilAutoLoad) {
                        final PvpBot botRef = this;
                        WORKER.execute(() -> {
                                try {
                                        String st = dev.z.pvpbot.ml.ILStore.get().loadAll(botRef);
                                        LOGGER.info("[pvpbot] IL autoload: {}", st);
                                } catch (Throwable t) {
                                        LOGGER.warn("[pvpbot] IL autoload failed: {}", t.toString());
                                }
                        });
                }
        }

        /** v2.0 PHASE 2-b four-head brain: models/v2-autosave.pbm → fresh. Never throws.
         *  Snapshots/hot-swaps of named v2 models go through the ModelStore. */
        private PolicyNet buildPolicyV2() {
                PolicyNet p = new PolicyNet(Math.max(config.replayCapacity, 4096),
                                Math.max(config.expertBufferCapacity, 1024), 0.995f, 20261001L);
                p.nStep = Math.max(1, config.nStep);
                try {
                        java.nio.file.Path auto = ModelStore.dir().resolve("v2-autosave.pbm");
                        if (java.nio.file.Files.exists(auto)) {
                                PolicyNet loaded = ModelStore.readPolicyNet(auto);
                                p.loadWeights(loaded);
                                p.setTrainSteps(loaded.getTrainSteps());
                                LOGGER.info("[pvpbot] v2 four-head brain restored from {} ({} steps)",
                                                auto.getFileName(), p.getTrainSteps());
                                return p;
                        }
                } catch (Exception e) {
                        LOGGER.warn("[pvpbot] v2 autosave unusable ({}) — loading the bundled pretrained brain", e.toString());
                }
                // v2.3: the BUNDLED offline-pretrained brain (hours of simulator
                // self-play + scripted opponents) — pure mode no longer starts
                // from random weights.
                try (InputStream is = PvpBot.class.getResourceAsStream(BUNDLED_V2)) {
                        if (is != null) {
                                PolicyNet loaded = ModelStore.readPolicyNet(is);
                                p.loadWeights(loaded);
                                p.setTrainSteps(loaded.getTrainSteps());
                                bundledV2Loaded = true;
                                LOGGER.info("[pvpbot] v2 brain: bundled pretrained model ({} offline steps)", p.getTrainSteps());
                        }
                } catch (Exception e) {
                        LOGGER.warn("[pvpbot] bundled v2 brain unusable ({}) — starting fresh", e.toString());
                }
                return p;
        }

        /** Policy brain: saved model → bundled pre-trained → fresh. Never throws.
         *  A saved model with a mismatched architecture (older mod version) is
         *  rejected so the new, bigger brain always loads. */
        /** v2.3: bump when the bundled v1 brain must replace on-device v1 brains. */
        private static final int V1_BRAIN_EPOCH = 5;

        /**
         * v2.3 ONE-TIME v1 UPGRADE. Until v2.3.1 a bot click never counted as a
         * hit (doAttack's return value was misread), so every on-device v1 brain
         * learned without ever receiving a damage-dealt reward. On the first
         * launch of this version the old brain is ARCHIVED to
         * models/v1-before-v2.3.json (loadable from the Models tab) and the
         * freshly simulator-trained bundled brain takes over.
         */
        private void upgradeV1BrainOnce() {
                try {
                        Path dir = BotConfig.dir().resolve("model");
                        Path marker = dir.resolve("v1_brain_epoch.txt");
                        int epoch = 0;
                        if (Files.exists(marker)) {
                                epoch = Integer.parseInt(Files.readString(marker).trim());
                        }
                        if (epoch >= V1_BRAIN_EPOCH) return;
                        Path saved = dir.resolve("policy.json");
                        if (Files.exists(saved)) {
                                Path models = BotConfig.dir().resolve("models");
                                Files.createDirectories(models);
                                Files.copy(saved, models.resolve("v1-before-v2.3.json"), StandardCopyOption.REPLACE_EXISTING);
                                Files.delete(saved);
                                LOGGER.info("[pvpbot] v1 brain archived to models/v1-before-v2.3.json — bundled v2.3 v1 brain loaded");
                        }
                        Files.createDirectories(dir);
                        Files.writeString(marker, Integer.toString(V1_BRAIN_EPOCH));
                } catch (Exception e) {
                        LOGGER.warn("[pvpbot] v1 brain upgrade skipped: {}", e.toString());
                }
        }

        private Dqn buildPolicy() {
                upgradeV1BrainOnce();
                int expertCap = Math.max(1024, config.expertBufferCapacity);
                JsonObject saved = loadOrDefault("policy.json");
                if (saved != null) {
                        try {
                                Dqn d = Dqn.fromJson(saved, config.replayCapacity, 0.995f, 20260928L, expertCap, POLICY_ARCH);
                        d.nStep = Math.max(1, config.nStep);
                        return d;
                        } catch (Exception e) {
                                LOGGER.warn("[pvpbot] saved policy.json unusable ({}), falling back to bundled pre-trained model", e.toString());
                        }
                }
                JsonObject bundled = readBundled("/assets/pvpbot/model/policy.json");
                if (bundled != null) {
                        try {
                                Dqn d = Dqn.fromJson(bundled, config.replayCapacity, 0.995f, 20260928L, expertCap, POLICY_ARCH);
                                d.nStep = Math.max(1, config.nStep);
                                return d;
                        } catch (Exception ignored) {
                        }
                }
                Dqn d = new Dqn(POLICY_ARCH, config.replayCapacity, 0.995f, 20260928L, expertCap);
                d.nStep = Math.max(1, config.nStep);
                return d;
        }

        /** Aim net: saved model → bundled pre-trained → fresh. Never throws. */
        private NeuralNet buildAim() {
                JsonObject saved = loadOrDefault("aim.json");
                if (saved != null) {
                        try {
                                return validatedAim(NeuralNet.fromJson(saved));
                        } catch (Exception e) {
                                LOGGER.warn("[pvpbot] saved aim.json unusable ({}), falling back to bundled pre-trained model", e.toString());
                        }
                }
                JsonObject bundled = readBundled("/assets/pvpbot/model/aim.json");
                if (bundled != null) {
                        try {
                                return validatedAim(NeuralNet.fromJson(bundled));
                        } catch (Exception ignored) {
                        }
                }
                return new NeuralNet(dev.z.pvpbot.bot.AimController.ARCH, 777);
        }

        private NeuralNet validatedAim(NeuralNet net) {
                if (net.sizes.length != dev.z.pvpbot.bot.AimController.ARCH.length
                                || net.sizes[0] != dev.z.pvpbot.bot.AimController.IN
                                || net.sizes[net.sizes.length - 1] != 2) {
                        throw new IllegalArgumentException("aim arch mismatch");
                }
                return net;
        }

        public static PvpBot init() {
                if (instance == null) {
                        instance = new PvpBot();
                }
                return instance;
        }

        public static PvpBot get() {
                return instance;
        }

        public BotConfig config() {
                return config;
        }

        public BotController controller() {
                return controller;
        }

        public BotHud hud() {
                return hud;
        }

        public Dqn dqn() {
                return dqn;
        }

        /** v2.0 PHASE 2-b: the four-head policy brain (movement/flags/aim/click). */
        public PolicyNet policy() {
                return policy;
        }

        /** v2.0 PHASE 2-a: name of the hot-swapped model running now (null = autosave brain). */
        public String activeModelName() {
                return ModelStore.activeName();
        }

        // ------------------------------------------------------------- persistence

        /** Background worker shared with the controller (training steps). */
        public static java.util.concurrent.ExecutorService worker() {
                return WORKER;
        }

        /**
         * Queue a model save on the background worker. Never blocks the game
         * thread; multiple queued saves collapse into one. The heavy work
         * (serializing ~6.4MB of JSON + file writes) used to freeze the game
         * for 100-300ms at every episode end — that is gone.
         */
        public void saveModelAsync() {
                if (saveQueued) return;
                saveQueued = true;
                WORKER.execute(() -> {
                        saveQueued = false;
                        saveModel();
                });
        }

        /** Saved model only (may be null) — bundled fallbacks are handled by callers. */
        private JsonObject loadOrDefault(String fileName) {
                Path p = BotConfig.dir().resolve("model").resolve(fileName);
                try {
                        if (Files.exists(p)) {
                                JsonObject parsed = gson.fromJson(Files.readString(p), JsonObject.class);
                                if (parsed != null && parsed.size() > 0) {
                                        return parsed;
                                }
                                // corrupt/empty saved file: remove it so we fall back to the bundled model
                                Files.deleteIfExists(p);
                        }
                } catch (Exception e) {
                        // corrupt save must never crash startup
                        LOGGER
                                        .warn("[pvpbot] could not read {} ({}), ignoring it", p, e.toString());
                        try {
                                Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                }
                return null;
        }

        private JsonObject readBundled(String resourcePath) {
                try (InputStream is = PvpBot.class.getResourceAsStream(resourcePath)) {
                        if (is != null) {
                                JsonObject parsed = gson.fromJson(new java.io.InputStreamReader(is, StandardCharsets.UTF_8), JsonObject.class);
                                if (parsed != null && parsed.size() > 0) {
                                        return parsed;
                                }
                        }
                } catch (Exception ignored) {
                }
                return null;
        }

        public void saveModel() {
                try {
                        Path dir = BotConfig.dir().resolve("model");
                        Files.createDirectories(dir);
                        // atomic-ish writes: a crash mid-save can never corrupt an existing model
                        atomicWrite(dir.resolve("policy.json.tmp"), gson.toJson(dqn.toJson()));
                        Files.move(dir.resolve("policy.json.tmp"), dir.resolve("policy.json"), StandardCopyOption.REPLACE_EXISTING);
                        atomicWrite(dir.resolve("aim.json.tmp"), gson.toJson(aimNet.toJson()));
                        Files.move(dir.resolve("aim.json.tmp"), dir.resolve("aim.json"), StandardCopyOption.REPLACE_EXISTING);
                        JsonObject meta = new JsonObject();
                        controller.saveStateTo(meta);
                        meta.addProperty("savedAt", java.time.Instant.now().toString());
                        atomicWrite(dir.resolve("meta.json.tmp"), gson.toJson(meta));
                        Files.move(dir.resolve("meta.json.tmp"), dir.resolve("meta.json"), StandardCopyOption.REPLACE_EXISTING);
                        // v2.0 PHASE 2-b: autosave the four-head brain (binary, ~1 MB, fast)
                        try {
                                Path models = BotConfig.dir().resolve("models");
                                Files.createDirectories(models);
                                Path v2 = models.resolve("v2-autosave.pbm.tmp");
                                try (java.io.DataOutputStream out = new java.io.DataOutputStream(Files.newOutputStream(v2))) {
                                        ModelStore.writeV2Header(out, "v2-autosave", controller.episodesDone,
                                                        controller.wins, controller.losses, controller.draws,
                                                        policy.getTrainSteps());
                                        policy.saveBinary(out);
                                }
                                Files.move(v2, models.resolve("v2-autosave.pbm"), StandardCopyOption.REPLACE_EXISTING);
                        } catch (Exception e2) {
                                LOGGER.warn("[pvpbot] v2 brain autosave failed: {}", e2.toString());
                        }
                } catch (IOException e) {
                        LOGGER
                                        .warn("[pvpbot] model save failed: {}", e.toString());
                }
        }

        private void atomicWrite(Path p, String content) throws IOException {
                Files.writeString(p, content, StandardCharsets.UTF_8);
        }

        private void loadMeta() {
                try {
                        Path p = BotConfig.dir().resolve("model").resolve("meta.json");
                        if (Files.exists(p)) {
                                JsonObject meta = gson.fromJson(Files.readString(p), JsonObject.class);
                                controller.loadStateFrom(meta);
                        }
                } catch (Exception ignored) {
                }
        }

        /** Reset the brain to the bundled pre-trained defaults (or fresh if none). */
        public static void wipe() {
                try {
                        Path dir = BotConfig.dir().resolve("model");
                        Files.deleteIfExists(dir.resolve("policy.json"));
                        Files.deleteIfExists(dir.resolve("aim.json"));
                        Files.deleteIfExists(dir.resolve("meta.json"));
                        Files.deleteIfExists(BotConfig.dir().resolve("models").resolve("v2-autosave.pbm"));
                        ModelStore.clearActive();
                } catch (IOException ignored) {
                }
                if (instance != null) {
                        instance.controller.stop();
                }
                instance = new PvpBot(); // fresh weights (bundled defaults are NOT re-copied: files were deleted → fresh random net)
        }
}
