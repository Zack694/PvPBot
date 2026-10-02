package dev.z.pvpbot.bot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import dev.z.pvpbot.BotConfig;
import dev.z.pvpbot.PvpBot;
import net.minecraft.client.MinecraftClient;
import net.minecraft.particle.ParticlesMode;
import net.minecraft.sound.SoundCategory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * v2.0 PHASE 1 — FOCUS (ECO) MODE (user: "a bind to make the screen black,
 * disable all particles, render distance 2-3, optimize everything, kill the
 * noise, save battery — and restore with the same key while the model keeps
 * training").
 *
 * Rendering dominates Minecraft's power draw; the simulation (and therefore
 * the bot's perception + training) is unaffected by every change here:
 *
 *   screen           full black overlay (drawn by PvpBotMod's HUD hook)
 *   particles        MINIMAL
 *   render distance  2 chunks (config)
 *   entity distance  50%
 *   max fps          15 (config) — the single biggest battery lever
 *   master volume    0 (config)
 *
 * Everything touched is a vanilla client-side option. On activation the
 * snapshot is written to config/pvpbot/focus.json; on deactivation it is
 * restored and the file deleted. If the game crashes while focused, the
 * next launch finds the file and restores automatically — the settings can
 * never stay stuck.
 *
 * The bot's perception reads WORLD DATA (entities, health, positions), not
 * pixels, so a black screen changes nothing about what the model sees:
 * training continues at full quality.
 */
public final class FocusMode {

        private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
        private static volatile boolean active = false;

        private FocusMode() {
        }

        public static boolean isActive() {
                return active;
        }

        public static boolean toggle(MinecraftClient mc) {
                if (active) {
                        deactivate(mc);
                } else {
                        activate(mc);
                }
                return active;
        }

        private static Path file() {
                return dev.z.pvpbot.BotConfig.dir().resolve("focus.json");
        }

        private static synchronized void activate(MinecraftClient mc) {
                if (active || mc == null || mc.options == null) return;
                try {
                        JsonObject snap = new JsonObject();
                        // ---- snapshot ----
                        snap.addProperty("particles", mc.options.getParticles().getValue().name());
                        snap.addProperty("viewDistance", mc.options.getViewDistance().getValue());
                        snap.addProperty("entityDistance", mc.options.getEntityDistanceScaling().getValue());
                        snap.addProperty("maxFps", mc.options.getMaxFps().getValue());
                        snap.addProperty("masterVolume", mc.options.getSoundVolumeOption(SoundCategory.MASTER).getValue());
                        // ---- apply focus profile ----
                        BotConfig cfg = PvpBot.get().config();
                        mc.options.getParticles().setValue(ParticlesMode.MINIMAL);
                        mc.options.getViewDistance().setValue(Math.max(2, cfg.focusRenderDistance));
                        mc.options.getEntityDistanceScaling().setValue(0.5);
                        mc.options.getMaxFps().setValue(Math.max(5, cfg.focusFrameCap));
                        if (cfg.focusMute) {
                                mc.options.getSoundVolumeOption(SoundCategory.MASTER).setValue(0.0);
                        }
                        // ---- crash-recovery record ----
                        Files.createDirectories(file().getParent());
                        Files.writeString(file(), GSON.toJson(snap));
                        active = true;
                        if (mc.player != null) {
                                mc.player.sendMessage(net.minecraft.text.Text.literal(
                                                "[PvPBot] FOCUS MODE ON — screen darkened, GPU/mute scaled down, training at full speed. Toggle again to restore."), false);
                        }
                } catch (Throwable t) {
                        PvpBot.LOGGER.warn("[pvpbot] focus activate failed: {}", t.toString());
                }
        }

        private static synchronized void deactivate(MinecraftClient mc) {
                if (!active) return;
                try {
                        JsonObject snap = null;
                        if (Files.exists(file())) {
                                snap = GSON.fromJson(Files.readString(file()), JsonObject.class);
                                Files.deleteIfExists(file());
                        }
                        if (snap != null && mc != null && mc.options != null) {
                                restore(mc, snap);
                        } else if (mc != null && mc.options != null) {
                                // no snapshot (should not happen) — sane defaults
                                mc.options.getParticles().setValue(ParticlesMode.ALL);
                                mc.options.getViewDistance().setValue(12);
                                mc.options.getEntityDistanceScaling().setValue(1.0);
                                mc.options.getMaxFps().setValue(120);
                                mc.options.getSoundVolumeOption(SoundCategory.MASTER).setValue(1.0);
                        }
                        active = false;
                        if (mc != null && mc.player != null) {
                                mc.player.sendMessage(net.minecraft.text.Text.literal(
                                                "[PvPBot] FOCUS MODE OFF — settings restored."), false);
                        }
                } catch (Throwable t) {
                        PvpBot.LOGGER.warn("[pvpbot] focus restore failed: {}", t.toString());
                        active = false;
                }
        }

        private static void restore(MinecraftClient mc, JsonObject snap) {
                try {
                        mc.options.getParticles().setValue(
                                        ParticlesMode.valueOf(snap.get("particles").getAsString()));
                } catch (Throwable ignored) {
                }
                optSet(mc.options.getViewDistance(), snap.get("viewDistance"));
                optSet(mc.options.getEntityDistanceScaling(), snap.get("entityDistance"));
                optSet(mc.options.getMaxFps(), snap.get("maxFps"));
                optSet(mc.options.getSoundVolumeOption(SoundCategory.MASTER), snap.get("masterVolume"));
        }

        /** Crash recovery: if the last session died while focused, restore now. */
        public static synchronized void recoverIfCrashed(MinecraftClient mc) {
                try {
                        Path f = file();
                        if (!Files.exists(f)) return;
                        JsonObject snap = GSON.fromJson(Files.readString(f), JsonObject.class);
                        Files.deleteIfExists(f);
                        if (snap != null && mc != null && mc.options != null) {
                                restore(mc, snap);
                                PvpBot.LOGGER.info("[pvpbot] focus.json found at startup — settings restored after an unclean exit");
                        }
                } catch (Throwable t) {
                        PvpBot.LOGGER.warn("[pvpbot] focus recovery failed: {}", t.toString());
                }
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private static void optSet(net.minecraft.client.option.SimpleOption opt, com.google.gson.JsonElement v) {
                if (v == null || opt == null) return;
                try {
                        if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) {
                                java.lang.Number n = v.getAsNumber();
                                // SimpleOption<T> — hand it the type it expects
                                Object cur = opt.getValue();
                                if (cur instanceof Integer) opt.setValue(n.intValue());
                                else if (cur instanceof Double) opt.setValue(n.doubleValue());
                                else if (cur instanceof Float) opt.setValue(n.floatValue());
                                else if (cur instanceof Long) opt.setValue(n.longValue());
                                else opt.setValue(v.getAsString());
                        } else {
                                opt.setValue(v.getAsString());
                        }
                } catch (Throwable ignored) {
                }
        }
}
