package dev.z.pvpbot.ui;

import dev.z.pvpbot.BotConfig;
import dev.z.pvpbot.PvpBot;
import dev.z.pvpbot.bot.ActionSpace;
import dev.z.pvpbot.bot.Actuator;
import dev.z.pvpbot.bot.HitWatcher;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Combat HUD: locator-bar style target tracker, attack cooldown, self HP,
 * HIT/CRIT/MISS flash, training telemetry, trade log and combo meter.
 * Drawn with plain fills + text — no external assets.
 *
 * v1.0.11 HUD CUSTOMIZER (user: "add a HUD customizer to edit the Position
 * and Size of each huds"): every element now draws in its own LOCAL
 * coordinate box, positioned by an anchor + pixel offset + scale stored in
 * {@link BotConfig#hudLayout}. All eight elements are drag/resize-editable in
 * the {@link PvpBotHudEditScreen} (open: /pvpbot hud). {@link #elementRects}
 * exposes each element's last screen-space rect for that editor.
 *
 * Also fixed here (v1.0.11): the cooldown bar used sw/2 for its Y (the
 * crosshair X center) so it sat near the BOTTOM of the screen on wide HUDs —
 * it is crosshair-relative (sh/2) now; the LMB keystroke cell lights on bot
 * clicks via {@link Actuator#attackVisualActive()}; and the telemetry line
 * shows the REAL train loss and the REAL reward EMA separately.
 */
public final class BotHud {

        private static final int WHITE = 0xFFFFFFFF;
        private static final int GREEN = 0xFF55FF55;
        private static final int GOLD = 0xFFFFAA00;
        private static final int RED = 0xFFFF5555;
        private static final int DARKRED = 0xFFAA3333;
        private static final int CYAN = 0xFF55FFFF;
        private static final int GRAY = 0xFFAAAAAA;
        private static final int PANEL = 0xA0000000;

        /** Element ids (config keys + editor labels). */
        public static final String[] ELEMENT_IDS = {
                        "tracker", "cooldown", "hp", "flash", "combo", "stats", "log", "keys"
        };

        /** Screen-space rect of every element drawn this frame (for the editor). */
        public final Map<String, Rect> elementRects = new LinkedHashMap<>();

        public static final class Rect {
                public int x, y, w, h;
        }

        private float curScale = 1f;

        /**
         * Position + scale transform for one element: computes the screen
         * origin from its anchor/offset/scale, records the editor rect and
         * pushes the matrix so everything until {@link #endEl} draws in LOCAL
         * pixels (0,0 = element top-left, scale applied).
         */
        private void beginEl(DrawContext ctx, BotConfig cfg, String id, int nominalW, int nominalH) {
                BotConfig.HudElement e = cfg.el(id);
                int sw = ctx.getScaledWindowWidth();
                int sh = ctx.getScaledWindowHeight();
                float s = MathHelper.clamp(e.scale, 0.5f, 2f);
                int w = Math.round(nominalW * s);
                int x, y;
                switch (e.anchor) {
                        case 1 -> { x = sw + e.ox; y = e.oy; }
                        case 2 -> { x = e.ox; y = sh + e.oy; }
                        case 3 -> { x = sw + e.ox; y = sh + e.oy; }
                        case 4 -> { x = (sw - w) / 2 + e.ox; y = e.oy; }
                        case 5 -> { x = (sw - w) / 2 + e.ox; y = sh + e.oy; }
                        case 6 -> { x = (sw - w) / 2 + e.ox; y = sh / 2 + e.oy; }
                        default -> { x = e.ox; y = e.oy; }
                }
                Rect r = elementRects.get(id);
                if (r == null) {
                        r = new Rect();
                        elementRects.put(id, r);
                }
                r.x = x;
                r.y = y;
                r.w = w;
                r.h = Math.round(nominalH * s);
                curScale = s;
                // 1.21.11: DrawContext matrices are a JOML Matrix3x2fStack
                var m = ctx.getMatrices();
                m.pushMatrix();
                m.translate(x, y);
                m.scale(s, s);
        }

        private void endEl(DrawContext ctx) {
                ctx.getMatrices().popMatrix();
                curScale = 1f;
        }

        /** Legacy entry point (live HUD). */
        public void render(DrawContext ctx, MinecraftClient mc) {
                render(ctx, mc, false);
        }

        public void render(DrawContext ctx, MinecraftClient mc, boolean preview) {
                PvpBot bot = PvpBot.get();
                BotConfig cfg = bot.config();
                if (!preview && (!cfg.hudEnabled || mc.player == null || mc.options.hudHidden)) return;
                var controller = bot.controller();
                elementRects.clear();

                ClientPlayerEntity self = mc.player;
                int sw = ctx.getScaledWindowWidth();
                int sh = ctx.getScaledWindowHeight();
                var tr = mc.textRenderer;

                boolean engaged = preview || controller.isStarted();
                LivingEntity target = !preview && engaged ? controller.selector().target() : null;

                // ---------------- paused overlay (fixed, not a movable element)
                if (!preview && controller.isPaused()) {
                        ctx.drawCenteredTextWithShadow(tr, Text.literal("PAUSED — /pvpbot resume"),
                                        sw / 2, sh / 2 + 26, (mc.player.age / 10) % 2 == 0 ? GOLD : GRAY);
                        return;
                }

                // ---------------- 1. locator-bar style target tracker
                if (engaged) {
                        beginEl(ctx, cfg, "tracker", 165, 56);
                        int barW = 155, barH = 5;
                        ctx.fill(-2, -2, barW + 2, barH + 2, PANEL);
                        ctx.fill(0, 0, barW, barH, 0xFF222222);
                        for (int i = 0; i < 5; i++) {
                                int sx = i * (barW / 5);
                                ctx.fill(sx + 1, 1, sx + barW / 5 - 1, barH - 1, 0xFF333344);
                        }
                        if (target != null || preview) {
                                double dx = target != null ? target.getX() - self.getX() : 1.5;
                                double dz = target != null ? target.getZ() - self.getZ() : 2.5;
                                float bearing = (float) Math.toDegrees(Math.atan2(-dx, dz));
                                float selfYaw = target != null ? self.getYaw() : 0f;
                                float rel = MathHelper.wrapDegrees(bearing - selfYaw);
                                float clamp = MathHelper.clamp(rel, -75f, 75f);
                                float fx = barW / 2f + (clamp / 75f) * (barW / 2f - 6);
                                double dist = target != null ? Math.sqrt(dx * dx + dz * dz) : 3.5;
                                float hp = target != null ? target.getHealth() : 20f;
                                String name = target != null ? target.getName().getString() : "Preview";
                                int mc2 = dist < 3.2 ? GREEN : (dist < 8 ? GOLD : CYAN);
                                ctx.fill((int) fx - 4, -6, (int) fx + 4, 2, mc2);
                                ctx.fill((int) fx - 6, -4, (int) fx + 6, 0, mc2);
                                String label = String.format(Locale.ROOT, "%s  %.1fm  %.0fHP", name, dist, hp);
                                ctx.drawCenteredTextWithShadow(tr, Text.literal(label), barW / 2, barH + 3, WHITE);
                                float thp = MathHelper.clamp(hp / 20f, 0f, 1f);
                                ctx.fill(0, barH + 13, barW, barH + 16, 0xFF331111);
                                ctx.fill(0, barH + 13, (int) (barW * thp), barH + 16, RED);
                                if (cfg.thoughtHudEnabled) {
                                        String thought = preview ? "cornering him now" : controller.mind.thought();
                                        if (!thought.isEmpty()) {
                                                String line = "\u201c" + thought + "\u201d";
                                                int ty = barH + 20;
                                                var mindIntent = preview ? dev.z.pvpbot.bot.DecisionMind.Intent.OPEN_FIELD : controller.mind.intent();
                                                int thoughtCol = switch (mindIntent) {
                                                        case SNEAK_TRAP, SNEAK_RESET -> GOLD;
                                                        case ENGAGE_LUNGE, CRIT_PRESSURE, WTAP_PRESSURE, TEMPO_SPIKE -> CYAN;
                                                        case DISENGAGE_HEAL, OPEN_FIELD -> GREEN;
                                                        default -> GRAY;
                                                };
                                                ctx.drawCenteredTextWithShadow(tr, Text.literal(line), barW / 2, ty, thoughtCol);
                                                String ctx2 = preview ? "combo live · 2.9m · he is cornered" : controller.mind.context();
                                                if (!ctx2.isEmpty()) {
                                                        String tag = preview ? "[OPEN_FIELD]" : "[" + controller.mind.intent().tag + "]";
                                                        ctx.drawCenteredTextWithShadow(tr, Text.literal(tag + " " + ctx2),
                                                                        barW / 2, ty + 10, 0xFF777777);
                                                }
                                        }
                                }
                        } else {
                                ctx.drawCenteredTextWithShadow(tr, Text.literal("searching for opponent…"), barW / 2, barH + 3, GRAY);
                        }
                        endEl(ctx);
                }

                // ---------------- 2. attack cooldown (crosshair-relative — the old
                // code used sw/2 as Y, parking it near the screen bottom on wide HUDs)
                beginEl(ctx, cfg, "cooldown", 72, 8);
                float cp = preview ? 1f : (self != null ? self.getAttackCooldownProgress(0.5f) : 0f);
                int cw = 42, ch = 3;
                ctx.fill(-1, -1, cw + 1, ch + 1, PANEL);
                ctx.fill(0, 0, cw, ch, 0xFF333333);
                int fill = (int) (cw * cp);
                ctx.fill(0, 0, fill, ch, cp >= 1f ? GREEN : 0xFFDDAA00);
                if (cp >= 1f && (preview || (mc.player != null && (mc.player.age / 10) % 2 == 0))) {
                        ctx.drawText(tr, Text.literal("READY"), cw + 5, -2, GREEN, true);
                }
                endEl(ctx);

                // ---------------- 3. self HP
                if (self != null) {
                        beginEl(ctx, cfg, "hp", 124, 26);
                        ctx.fill(-2, -2, 122, 24, PANEL);
                        ctx.drawText(tr, Text.literal(String.format(Locale.ROOT, "YOU  %.1f/20 HP", self.getHealth())),
                                        0, 0, self.getHealth() > 10 ? GREEN : (self.getHealth() > 6 ? GOLD : RED), true);
                        if (self.getAbsorptionAmount() > 0) {
                                ctx.drawText(tr, Text.literal(String.format(Locale.ROOT, "+%.0f ABS", self.getAbsorptionAmount())),
                                                70, 0, GOLD, true);
                        }
                        float hpf = MathHelper.clamp(self.getHealth() / 20f, 0f, 1f);
                        ctx.fill(0, 11, 100, 14, 0xFF331111);
                        ctx.fill(0, 11, (int) (100 * hpf), 14, RED);
                        if (engaged) {
                                var kit = dev.z.pvpbot.bot.KitDetect.detect(self);
                                ctx.drawText(tr, Text.literal("kit: " + kit.label), 0, 17, GRAY, true);
                        }
                        endEl(ctx);
                }

                // ---------------- 4. hit / crit / miss flash
                if (!preview && cfg.tradeLogEnabled && self != null) {
                        beginEl(ctx, cfg, "flash", 100, 20);
                        HitWatcher.TradeEvent ev = null;
                        var it = controller.hits.log.iterator();
                        if (it.hasNext()) ev = it.next();
                        if (ev != null) {
                                long age = controller.tick() - ev.tick;
                                if (age < 25) {
                                        String txt;
                                        int col;
                                        switch (ev.kind) {
                                                case "CRIT" -> { txt = String.format(Locale.ROOT, "CRIT  -%.1f", ev.amount); col = GOLD; }
                                                case "HIT" -> { txt = String.format(Locale.ROOT, "HIT  -%.1f", ev.amount); col = GREEN; }
                                                case "MISS" -> { txt = "MISS"; col = RED; }
                                                default -> { txt = String.format(Locale.ROOT, "TOOK  %.1f", ev.amount); col = DARKRED; }
                                        }
                                        int ty = -(int) (age / 3);
                                        ctx.drawCenteredTextWithShadow(tr, Text.literal(txt), 50, ty, col | ((255 - Math.min(220, (int) (age * 10))) << 24));
                                }
                        }
                        endEl(ctx);
                }

                // ---------------- 5. combo meter
                if (preview || (cfg.comboMeterEnabled && engaged)) {
                        beginEl(ctx, cfg, "combo", 150, 14);
                        int cd = preview ? 3 : controller.hits.comboDealt;
                        int ct = preview ? 0 : controller.hits.comboTaken;
                        if (cd >= 2) {
                                ctx.drawCenteredTextWithShadow(tr, Text.literal("COMBO x" + cd), 75, 0, GREEN);
                        } else if (ct >= 2) {
                                boolean flash = preview || (mc.player != null && (mc.player.age / 5) % 2 == 0);
                                ctx.drawCenteredTextWithShadow(tr, Text.literal("GETTING COMBOED x" + ct), 75, 0,
                                                flash ? DARKRED : RED);
                        }
                        endEl(ctx);
                }

                // ---------------- 6. training stats
                if (preview || (cfg.trainingStatsEnabled && engaged)) {
                        beginEl(ctx, cfg, "stats", 152, 84);
                        String[] lines;
                        if (preview) {
                                lines = new String[]{
                                                "PvPBot [TRAIN] RAPID (3/20)",
                                                "eps 0.150  lr 0.0020",
                                                "W/L/D 4/2/1",
                                                "steps 12000  buf 4096",
                                                "loss 0.012  rew 1.25",
                                                "aimloss 0.004",
                                                "S 3 eps W4/L2/D1"
                                };
                        } else {
                                int lineCount = 6 + (controller.isTrainingSession() || controller.isHumanTraining() ? 1 : 0);
                                lines = new String[lineCount];
                                String tag = controller.isHumanTraining() ? "[H-TRAIN] "
                                                : controller.isTrainingSession() ? "[TRAIN] " : "";
                                lines[0] = String.format(Locale.ROOT, "PvPBot %s%s", tag, controller.curriculumPhase());
                                lines[1] = String.format(Locale.ROOT, "eps %.3f  lr %.4f", controller.epsilon(), controller.currentLr());
                                lines[2] = String.format(Locale.ROOT, "W/L/D %d/%d/%d", controller.wins, controller.losses, controller.draws);
                                lines[3] = String.format(Locale.ROOT, "steps %d  buf %d", controller.dqn.getTrainSteps(), controller.dqn.bufferSize());
                                // v1.0.11: the REAL train loss (Huber) and the REAL reward EMA
                                lines[4] = String.format(Locale.ROOT, "loss %.3f  rew %.2f", controller.lossEma(), controller.rewardEma());
                                lines[5] = String.format(Locale.ROOT, "aimloss %.4f", controller.aim.lastLoss);
                                if (controller.isTrainingSession() || controller.isHumanTraining()) {
                                        lines[6] = String.format(Locale.ROOT, "S %d eps W%d/L%d/D%d",
                                                        controller.sessionEpisodes, controller.sessionWins,
                                                        controller.sessionLosses, controller.sessionDraws);
                                }
                        }
                        ctx.fill(-4, -3, 150, lines.length * 10 + 2, PANEL);
                        for (int i = 0; i < lines.length; i++) {
                                ctx.drawText(tr, Text.literal(lines[i]), 0, i * 10, i == 0 ? CYAN : WHITE, true);
                        }
                        endEl(ctx);
                }

                // ---------------- 7. trade log
                if (preview || (cfg.tradeLogEnabled && engaged)) {
                        beginEl(ctx, cfg, "log", 120, 62);
                        if (preview) {
                                String[] sample = {"CRIT -7.5", "HIT -4.0", "HIT -4.5", "TOOK -3.0", "MISS"};
                                for (int i = 0; i < sample.length; i++) {
                                        int col = switch (sample[i].substring(0, 3)) {
                                                case "CRI" -> GOLD;
                                                case "HIT" -> GREEN;
                                                case "TOO" -> DARKRED;
                                                default -> RED;
                                        };
                                        ctx.drawText(tr, Text.literal(sample[i]), 0, i * 10, col, true);
                                }
                        } else {
                                int i = 0;
                                for (HitWatcher.TradeEvent e : controller.hits.log) {
                                        if (i >= 6) break;
                                        long age = controller.tick() - e.tick;
                                        if (age > 200) break;
                                        int col = switch (e.kind) {
                                                case "CRIT" -> GOLD;
                                                case "HIT" -> GREEN;
                                                case "MISS" -> RED;
                                                default -> DARKRED;
                                        };
                                        // v2.3.6: "HIT 1.9 dmg  +0.38" — the damage dealt and the
                                        // learning reward it paid (the old "HIT -0.9" showed the
                                        // opponent's HP change and looked like a penalty)
                                        String s = e.kind.equals("MISS")
                                                        ? String.format(Locale.ROOT, "MISS  %+.2f", e.reward)
                                                        : String.format(Locale.ROOT, "%s %s%.1f dmg  %+.2f", e.kind,
                                                                        e.estimated ? "~" : "", Math.abs(e.amount), e.reward);
                                        ctx.drawText(tr, Text.literal(s), 0, i * 10, col, true);
                                        i++;
                                }
                        }
                        endEl(ctx);
                }

                // ---------------- 8. keystrokes: WASD + LMB + Space + Shift
                // Reads the REAL KeyBinding states (the user's own presses AND the
                // bot's virtual presses share the keys) — plus the v1.0.11 bot
                // click pulse, because doAttack never presses the attack binding.
                if (preview || cfg.keystrokesEnabled) {
                        beginEl(ctx, cfg, "keys", 64, 86);
                        renderKeystrokes(ctx, mc, preview);
                        endEl(ctx);
                }
        }

        /** Classic keystrokes grid: W / A S D / LMB / Space / Shift (LOCAL coords). */
        private void renderKeystrokes(DrawContext ctx, MinecraftClient mc, boolean preview) {
                ClientPlayerEntity p = mc.player;
                var o = mc.options;
                int k = 20;          // key cell size
                int gap = 2;

                boolean fwd = p != null && o.forwardKey.isPressed();
                boolean left = p != null && o.leftKey.isPressed();
                boolean back = p != null && o.backKey.isPressed();
                boolean right = p != null && o.rightKey.isPressed();
                boolean jump = p != null && o.jumpKey.isPressed();
                boolean sneak = p != null && o.sneakKey.isPressed();
                boolean atk = p != null && (o.attackKey.isPressed() || Actuator.attackVisualActive());

                if (preview) {
                        // demo flash so every cell is visible while positioning
                        long t = System.currentTimeMillis() / 300;
                        fwd = (t % 4) == 0;
                        left = (t % 4) == 1;
                        back = (t % 4) == 2;
                        right = (t % 4) == 3;
                        atk = (t % 2) == 0;
                        jump = (t % 3) == 0;
                        sneak = (t % 5) == 0;
                }

                // W row (centered)
                key(ctx, k + gap, 0, k, k, "W", fwd);
                // A S D row
                key(ctx, 0, k + gap, k, k, "A", left);
                key(ctx, k + gap, k + gap, k, k, "S", back);
                key(ctx, (k + gap) * 2, k + gap, k, k, "D", right);
                // LMB row (full width)
                int w = k * 3 + gap * 2;
                ctx.fill(0, (k + gap) * 2, w, (k + gap) * 2 + k - 2,
                                atk ? 0xFFE8E8E8 : PANEL);
                ctx.drawText(mc.textRenderer, Text.literal("LMB"), w / 2 - 8,
                                (k + gap) * 2 + (k - 2) / 2 - 4, atk ? 0xFF111111 : WHITE, false);
                // Space + Shift row
                int half = (w - gap) / 2;
                ctx.fill(0, (k + gap) * 3, half, (k + gap) * 3 + k - 2,
                                jump ? 0xFFE8E8E8 : PANEL);
                ctx.drawText(mc.textRenderer, Text.literal("SPACE"), half / 2 - 13,
                                (k + gap) * 3 + (k - 2) / 2 - 4, jump ? 0xFF111111 : WHITE, false);
                ctx.fill(half + gap, (k + gap) * 3, w, (k + gap) * 3 + k - 2,
                                sneak ? 0xFFE8E8E8 : PANEL);
                ctx.drawText(mc.textRenderer, Text.literal("SHIFT"), half + gap + half / 2 - 13,
                                (k + gap) * 3 + (k - 2) / 2 - 4, sneak ? 0xFF111111 : WHITE, false);
        }

        private void key(DrawContext ctx, int x, int y, int w, int h, String label, boolean pressed) {
                ctx.fill(x, y, x + w, y + h, pressed ? 0xFFE8E8E8 : PANEL);
                var tr = MinecraftClient.getInstance().textRenderer;
                ctx.drawText(tr, Text.literal(label), x + w / 2 - tr.getWidth(label) / 2,
                                y + h / 2 - 4, pressed ? 0xFF111111 : WHITE, false);
        }
}
