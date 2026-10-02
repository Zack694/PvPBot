package dev.z.pvpbot.bot;

import dev.z.pvpbot.BotConfig;
import dev.z.pvpbot.PvpBot;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.opengl.GL11;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * v2.1.0 — VISION RECORDER (the "player images" dataset builder).
 *
 * The user asked for image data so the Sight system can learn to RECOGNIZE
 * players from pixels. This recorder builds that dataset IN GAME with
 * PERFECT labels — better than any scraped dataset because:
 *
 *   - every crop comes from the user's own client, texture pack, skins and
 *     lighting (the exact distribution the recognizer will run on);
 *   - labels are read from the client's own entity data (position, name,
 *     sneaking, grounded, hurt state, distance, line of sight) — zero
 *     annotation work, zero labeling noise;
 *   - matched NEGATIVES (crops of empty scenery) are captured with the same
 *     lighting/textures so a classifier learns "player vs everything else
 *     in MY world", not "player vs ImageNet".
 *
 * Mechanics: every visionEveryMs while enabled, the framebuffer is read
 * (glReadPixels on the render thread during HUD pass — the main framebuffer
 * is bound there), each visible player's chest is projected to screen
 * coordinates with pure trigonometry (camera basis from yaw/pitch + the
 * configured FOV — no Mojang matrix churn across versions), and a
 * crop x crop px PNG is cut around the projection plus one matched negative.
 * Everything lands in <game dir>/pvpbot-vision/ with a JSONL manifest.
 *
 * Public web datasets (links in docs/VISION-DATASETS.md) can be merged with
 * this set later; the in-game set is the one that matches the deploy env.
 *
 * HARD SAFETY RULES: off by default, never allocates when off, catches every
 * Throwable, auto-disables itself after its first error (one warn log) and
 * respects visionMaxFiles so it can never fill a disk.
 */
public final class VisionRecorder {

        private static VisionRecorder instance;
        private final Random rng = new Random();

        private boolean registered;
        private boolean errored;
        private long lastCaptureMs = 0L;
        private int posCount, negCount;
        private Path dir;
        private Path manifest;

        public static VisionRecorder get() {
                if (instance == null) {
                        instance = new VisionRecorder();
                }
                return instance;
        }

        /** Register the render hook (idempotent; call once from the mod init). */
        public void init() {
                if (registered) {
                        return;
                }
                registered = true;
                dir = FabricLoader.getInstance().getGameDir().resolve("pvpbot-vision");
                manifest = dir.resolve("manifest.jsonl");
                HudRenderCallback.EVENT.register((context, tickCounter) -> {
                        try {
                                frame(MinecraftClient.getInstance());
                        } catch (Throwable t) {
                                // the recorder must NEVER take the game down
                                errored = true;
                                setRecording(false);
                                PvpBot.LOGGER.warn("[pvpbot] vision recorder disabled after error: {}", t.toString());
                        }
                });
        }

        public static void setRecording(boolean on) {
                BotConfig cfg = PvpBot.get() != null ? PvpBot.get().config() : null;
                if (cfg == null) {
                        return;
                }
                if (cfg.visionRecord == on) {
                        return;
                }
                cfg.visionRecord = on;
                cfg.save();
        }

        /** One-line status for /pvpbot vision status. */
        public String status() {
                BotConfig cfg = PvpBot.get() != null ? PvpBot.get().config() : null;
                boolean on = cfg != null && cfg.visionRecord;
                return String.format("vision %s | positives %d | negatives %d | dir %s%s",
                                on ? "RECORDING" : "off", posCount, negCount,
                                dir != null ? dir.getFileName().toString() : "-",
                                errored ? " | LAST ERROR disabled it (see log)" : "");
        }

        private void frame(MinecraftClient mc) throws IOException {
                if (errored) {
                        return;
                }
                BotConfig cfg = PvpBot.get() != null ? PvpBot.get().config() : null;
                if (cfg == null || !cfg.visionRecord || mc.player == null || mc.world == null) {
                        return;
                }
                if (mc.currentScreen != null) {
                        return; // never capture GUI frames
                }
                long now = System.currentTimeMillis();
                int every = Math.max(150, cfg.visionEveryMs);
                if (now - lastCaptureMs < every) {
                        return;
                }
                lastCaptureMs = now;

                // disk safety: stop before we can fill anything
                if (posCount + negCount >= Math.max(100, cfg.visionMaxFiles)) {
                        setRecording(false);
                        announce("[pvpbot] vision dataset hit the " + cfg.visionMaxFiles + "-file cap — recorder paused.");
                        return;
                }

                int w = mc.getWindow().getFramebufferWidth();
                int h = mc.getWindow().getFramebufferHeight();
                if (w <= 0 || h <= 0) {
                        return;
                }
                // read the whole frame once (render thread; main framebuffer is
                // bound during the HUD pass). RGBA, bottom-up origin.
                ByteBuffer buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
                GL11.glReadPixels(0, 0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);

                // camera basis from the player's own eye/yaw/pitch (third-person
                // offset ignored — the crop is generous enough to absorb it)
                float yaw = mc.player.getYaw(), pitch = mc.player.getPitch();
                float yawRad = (float) Math.toRadians(yaw), pitchRad = (float) Math.toRadians(pitch);
                Vec3d eye = mc.player.getEyePos();
                Vec3d fwd = new Vec3d(-MathHelper.sin(yawRad) * MathHelper.cos(pitchRad),
                                -MathHelper.sin(pitchRad),
                                MathHelper.cos(yawRad) * MathHelper.cos(pitchRad));
                Vec3d right = new Vec3d(-MathHelper.cos(yawRad), 0f, -MathHelper.sin(yawRad));
                Vec3d up = right.crossProduct(fwd).normalize();
                float fovY = Math.max(30f, mc.options.getFov().getValue());
                float tanHalf = (float) Math.tan(Math.toRadians(fovY) * 0.5f);
                float aspect = (float) w / (float) h;

                // gather visible players + their screen projections
                List<AbstractClientPlayerEntity> players = mc.world.getPlayers();
                List<double[]> shots = new ArrayList<>();   // {sx, sy, depth}
                List<AbstractClientPlayerEntity> shotEnts = new ArrayList<>();
                for (AbstractClientPlayerEntity p : players) {
                        if (p == mc.player || !p.isAlive()) {
                                continue;
                        }
                        double dx = p.getX() - mc.player.getX(), dz = p.getZ() - mc.player.getZ();
                        double dist = MathHelper.sqrt((float) (dx * dx + dz * dz));
                        if (dist > cfg.visionMaxDist) {
                                continue;
                        }
                        Vec3d chest = p.getEyePos().subtract(0, 0.7, 0); // ~chest: standing eye 1.62 - 0.7
                        Vec3d rel = chest.subtract(eye);
                        double depth = rel.dotProduct(fwd);
                        if (depth < 0.4) {
                                continue; // behind the camera
                        }
                        double ndcX = (rel.dotProduct(right) / depth) / (tanHalf * aspect);
                        double ndcY = (rel.dotProduct(up) / depth) / tanHalf;
                        if (Math.abs(ndcX) > 1.1 || Math.abs(ndcY) > 1.1) {
                                continue; // off frame
                        }
                        double sx = (ndcX * 0.5 + 0.5) * w;
                        double sy = (1.0 - (ndcY * 0.5 + 0.5)) * h; // screen-space Y (top-left origin)
                        shots.add(new double[]{sx, sy, depth});
                        shotEnts.add(p);
                }

                Files.createDirectories(dir);
                int crop = Math.max(48, cfg.visionCrop);
                boolean wrote = false;

                // ---- positives: one crop per projected player ----
                for (int i = 0; i < shots.size(); i++) {
                        double[] s = shots.get(i);
                        AbstractClientPlayerEntity p = shotEnts.get(i);
                        int cx = (int) Math.round(s[0]), cy = (int) Math.round(s[1]);
                        int x0 = MathHelper.clamp(cx - crop / 2, 0, w - crop);
                        int y0 = MathHelper.clamp(cy - crop / 2, 0, h - crop);
                        if (cx < 0 || cy < 0 || cx >= w || cy >= h) {
                                continue;
                        }
                        BufferedImage img = cropFrom(buf, w, h, x0, y0, crop, crop);
                        String name = "pos_" + Long.toHexString(System.currentTimeMillis()) + "_" + posCount + ".png";
                        ImageIO.write(img, "png", dir.resolve(name).toFile());
                        boolean los = lineOfSight(mc, p);
                        manifestLine(String.format(
                                        "{\"file\":\"%s\",\"label\":\"player\",\"uuid\":\"%s\",\"name\":\"%s\",\"dist\":%.2f,"
                                                        + "\"sneaking\":%b,\"onGround\":%b,\"hurtTime\":%d,\"los\":%b,\"screenX\":%.0f,\"screenY\":%.0f,\"frameW\":%d,\"frameH\":%d}",
                                        name, p.getUuidAsString(), p.getName().getString().replace("\"", ""),
                                        Math.sqrt(mc.player.squaredDistanceTo(p)), p.isSneaking(), p.isOnGround(),
                                        p.hurtTime, los, s[0], s[1], w, h));
                        posCount++;
                        wrote = true;
                }

                // ---- matched negative: a crop away from every player box ----
                if (!shots.isEmpty() || rng.nextBoolean()) {
                        for (int attempt = 0; attempt < 8; attempt++) {
                                int x0 = crop / 2 + rng.nextInt(Math.max(1, w - crop));
                                int y0 = crop / 2 + rng.nextInt(Math.max(1, h - crop));
                                boolean clear = true;
                                for (double[] s : shots) {
                                        if (Math.abs(s[0] - x0) < crop * 1.5 && Math.abs(s[1] - y0) < crop * 1.5) {
                                                clear = false;
                                                break;
                                        }
                                }
                                if (clear) {
                                        BufferedImage img = cropFrom(buf, w, h, x0, y0, crop, crop);
                                        String name = "neg_" + Long.toHexString(System.currentTimeMillis()) + "_" + negCount + ".png";
                                        ImageIO.write(img, "png", dir.resolve(name).toFile());
                                        manifestLine(String.format(
                                                        "{\"file\":\"%s\",\"label\":\"empty\",\"frameW\":%d,\"frameH\":%d}",
                                                        name, w, h));
                                        negCount++;
                                        wrote = true;
                                        break;
                                }
                        }
                }
                if (wrote && (posCount + negCount) % 25 == 0) {
                        announce("[pvpbot] vision dataset: " + posCount + " players / " + negCount
                                        + " negatives saved (manifest.jsonl alongside).");
                }
        }

        /** Cut an RGB crop from the RGBA readback buffer (GL origin is bottom-left). */
        private static BufferedImage cropFrom(ByteBuffer buf, int frameW, int frameH,
                                              int x0, int y0, int cw, int ch) {
                BufferedImage img = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_RGB);
                for (int py = 0; py < ch; py++) {
                        int bufRow = frameH - 1 - (y0 + py); // flip Y
                        for (int px = 0; px < cw; px++) {
                                int idx = (bufRow * frameW + (x0 + px)) * 4;
                                int r = buf.get(idx) & 0xFF;
                                int g = buf.get(idx + 1) & 0xFF;
                                int b = buf.get(idx + 2) & 0xFF;
                                img.setRGB(px, py, (r << 16) | (g << 8) | b);
                        }
                }
                return img;
        }

        /** Cheap occlusion label: eye -> target chest blocked by blocks? */
        private static boolean lineOfSight(MinecraftClient mc, AbstractClientPlayerEntity p) {
                try {
                        Vec3d from = mc.player.getEyePos();
                        Vec3d to = p.getEyePos().subtract(0, 0.65, 0);
                        HitResult hit = mc.world.raycast(new net.minecraft.world.RaycastContext(
                                        from, to,
                                        net.minecraft.world.RaycastContext.ShapeType.COLLIDER,
                                        net.minecraft.world.RaycastContext.FluidHandling.NONE, mc.player));
                        return hit == null || hit.getType() == HitResult.Type.MISS;
                } catch (Throwable t) {
                        return true;
                }
        }

        private void manifestLine(String json) {
                try {
                        Files.writeString(manifest, json + System.lineSeparator(),
                                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException ignored) {
                }
        }

        private static void announce(String msg) {
                try {
                        if (PvpBot.get() != null && PvpBot.get().controller() != null) {
                                PvpBot.get().controller().announcePublic(msg);
                        }
                } catch (Throwable ignored) {
                }
        }
}
