package dev.z.pvpbot.ui;

import dev.z.pvpbot.BotConfig;
import dev.z.pvpbot.PvpBot;
import dev.z.pvpbot.ml.ILStore;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * v2.2.0 — BUILT-IN ADVANCED CONFIG, REBUILT ON A CUSTOM WIDGET SYSTEM
 * (user: "make the All Configs/Settings scrollable smoothly cuz it's not
 * scrollable rn").
 *
 * The old screen split every setting across three FIXED pages with
 * Minecraft buttons and no scrolling at all. Everything now lives on ONE
 * smoothly scrolling page drawn with custom widgets (no Minecraft button
 * chrome):
 *
 *  - mouse WHEEL scrolling with eased, frame-rate independent interpolation;
 *  - left-drag anywhere on the panel (or on the scrollbar grip) with
 *    momentum on release;
 *  - a real scrollbar with a draggable grip;
 *  - custom pill toggles, custom sliders (click + drag anywhere on the
 *    track), section headers, live status rows and action buttons;
 *  - the SAME detailed hover tooltips as the Cloth Config bridge
 *    ({@link BotTooltips}).
 *
 * Every change persists to config/pvpbot/config.json immediately. Sections:
 * COMBAT / AIM & SMOOTHNESS / PURE MODE (v2) / LEARNING & IL / HUD & MISC.
 */
public final class PvpBotConfigScreen extends Screen {

        private final Screen parent;
        private final BotConfig cfg;
        private String status = "saved";

        // ---- layout ------------------------------------------------------
        private static final int ROW_H = 22;
        private static final int HEADER_H = 20;
        private static final int GAP = 2;

        private final List<Row> rows = new ArrayList<>();
        private int contentH = 0;

        // ---- smooth scroll state ------------------------------------------
        private float scrollCur = 0f;     // eased, what is actually drawn
        private float scrollTarget = 0f;  // where the wheel/drag wants to be
        private float inertia = 0f;       // drag momentum
        private boolean panelDrag = false;
        private boolean gripDrag = false;
        private double dragStartY = 0.0;
        private float dragStartScroll = 0f;
        private double lastDragY = 0.0;
        private long lastFrameMs = 0L;

        // ---- interaction ---------------------------------------------------
        private SliderRow draggingSlider = null;
        private final List<HoverTip> hoverTips = new ArrayList<>();

        private static final class HoverTip {
                final Row row;
                final int index;
                HoverTip(Row r, int i) { row = r; index = i; }
        }

        public PvpBotConfigScreen(Screen parent, int page) {
                super(Text.literal("PvPBot Advanced Config — scroll: wheel or drag"));
                this.parent = parent;
                this.cfg = PvpBot.get().config();
        }

        // ================================================================ rows

        private abstract class Row {
                int h = ROW_H;

                abstract void draw(DrawContext ctx, int x, int y, int w, double mx, double my, boolean hovered);

                /** @return true if the click was consumed */
                boolean click(Click click, int x, int y, int w) {
                        return false;
                }

                boolean drag(Click click, int x, int y, int w) {
                        return false;
                }

                void release() {
                }

                Text tooltip() {
                        return null;
                }
        }

        private final class HeaderRow extends Row {
                final String label;
                final int color;

                HeaderRow(String label, int color) {
                        this.label = label;
                        this.color = color;
                        this.h = HEADER_H;
                }

                @Override
                void draw(DrawContext ctx, int x, int y, int w, double mx, double my, boolean hovered) {
                        ctx.drawText(textRenderer, Text.literal(label), x + 2, y + 6, color, true);
                        ctx.fill(x, y + HEADER_H - 3, x + w, y + HEADER_H - 2, 0x40FFFFFF);
                }
        }

        private final class ToggleRow extends Row {
                final String label;
                final Supplier<Boolean> get;
                final java.util.function.Consumer<Boolean> set;
                final Text tip;

                ToggleRow(String label, boolean initial, Text tip, java.util.function.Consumer<Boolean> set) {
                        this.label = label;
                        this.get = new Supplier<>() {
                                boolean v = initial;

                                @Override
                                public Boolean get() {
                                        return v;
                                }
                        };
                        this.tip = tip;
                        this.set = set;
                }

                @Override
                void draw(DrawContext ctx, int x, int y, int w, double mx, double my, boolean hovered) {
                        boolean on = get.get();
                        ctx.drawText(textRenderer, Text.literal(label), x + 2, y + 7, 0xFFE6EEFF, true);
                        // pill switch (28 x 12)
                        int px = x + w - 34, py = y + 5;
                        ctx.fill(px, py, px + 28, py + 12, on ? 0xFF2E7D4F : 0xFF3A3F4E);
                        ctx.fill(px + 1, py + 1, px + 27, py + 11, on ? 0xFF3FA66A : 0xFF4A5060);
                        int knob = on ? px + 16 : px + 2;
                        ctx.fill(knob, py + 1, knob + 10, py + 11, 0xFFF2F5FA);
                        ctx.drawText(textRenderer, Text.literal(on ? "ON" : "OFF"), px - 26, y + 7,
                                        on ? 0xFF7DFFA0 : 0xFF8890A0, true);
                }

                @Override
                boolean click(Click click, int x, int y, int w) {
                        boolean v = !get.get();
                        set.accept(v);
                        save();
                        return true;
                }

                @Override
                Text tooltip() {
                        return tip;
                }
        }

        private final class SliderRow extends Row {
                final String label;
                final float min, max;
                final int digits;
                final Supplier<Float> get;
                final java.util.function.Consumer<Float> set;
                final Text tip;

                SliderRow(String label, float min, float max, float initial, int digits,
                          Text tip, java.util.function.Consumer<Float> set) {
                        this.label = label;
                        this.min = min;
                        this.max = max;
                        this.digits = digits;
                        this.get = new Supplier<>() {
                                float v = initial;

                                @Override
                                public Float get() {
                                        return v;
                                }
                        };
                        this.tip = tip;
                        this.set = set;
                }

                private float norm() {
                        return (get.get() - min) / (max - min);
                }

                private void setNorm(double n) {
                        float v = (float) (min + Math.max(0.0, Math.min(1.0, n)) * (max - min));
                        float step = (float) Math.pow(10, -digits);
                        v = Math.round(v / step) * step;
                        get2set(v);
                }

                private void get2set(float v) {
                        set.accept(v);
                        save();
                }

                private String fmt(float v) {
                        return digits == 0 ? String.format("%s: %.0f", label, v)
                                        : String.format("%s: %." + digits + "f", label, v);
                }

                @Override
                void draw(DrawContext ctx, int x, int y, int w, double mx, double my, boolean hovered) {
                        float v = get.get();
                        ctx.drawText(textRenderer, Text.literal(fmt(v)), x + 2, y + 2, 0xFFE6EEFF, true);
                        // track
                        int ty = y + 15;
                        ctx.fill(x + 2, ty, x + w - 2, ty + 5, 0xFF232838);
                        ctx.fill(x + 3, ty + 1, x + w - 3, ty + 4, 0xFF39415A);
                        // filled portion
                        float n = norm();
                        int fillX = (int) ((w - 4) * n);
                        ctx.fill(x + 3, ty + 1, x + 3 + fillX, ty + 4, 0xFF3D7BFF);
                        // grip
                        int gx = x + 2 + (int) ((w - 12) * n);
                        ctx.fill(gx, ty - 2, gx + 8, ty + 7, draggingSlider == this ? 0xFFFFFFFF : 0xFFDCE6F5);
                }

                @Override
                boolean click(Click click, int x, int y, int w) {
                        double rel = (click.x() - (x + 6)) / (double) Math.max(1, w - 12);
                        setNorm(rel);
                        draggingSlider = this;
                        return true;
                }

                @Override
                boolean drag(Click click, int x, int y, int w) {
                        double rel = (click.x() - (x + 6)) / (double) Math.max(1, w - 12);
                        setNorm(rel);
                        return true;
                }

                @Override
                Text tooltip() {
                        return tip;
                }
        }

        private final class ButtonRow extends Row {
                final String label;
                final int color;
                final Runnable action;

                ButtonRow(String label, int color, Runnable action) {
                        this.label = label;
                        this.color = color;
                        this.action = action;
                }

                @Override
                void draw(DrawContext ctx, int x, int y, int w, double mx, double my, boolean hovered) {
                        ctx.fill(x, y, x + w, y + h - 1, hovered ? 0xFF22335C : 0xFF182238);
                        ctx.fill(x, y, x + w, y + 1, 0xFF3D7BFF);
                        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(label),
                                        x + w / 2, y + 6, color);
                }

                @Override
                boolean click(Click click, int x, int y, int w) {
                        action.run();
                        return true;
                }
        }

        private final class StatusRow extends Row {
                final Supplier<String> text;
                final int color;

                StatusRow(Supplier<String> text, int color) {
                        this.text = text;
                        this.color = color;
                }

                @Override
                void draw(DrawContext ctx, int x, int y, int w, double mx, double my, boolean hovered) {
                        String s = text.get();
                        if (s.length() > 58) s = s.substring(0, 57) + "…";
                        ctx.fill(x, y, x + w, y + h - 1, 0xFF10151F);
                        ctx.drawText(textRenderer, Text.literal(s), x + 4, y + 7, color, true);
                }
        }

        // ================================================================ build

        @Override
        protected void init() {
                hoverTips.clear();
                rows.clear();
                scrollTarget = 0f;
                scrollCur = 0f;

                // ---- COMBAT ----
                rows.add(new HeaderRow("COMBAT", 0xFFFF9A66));
                rows.add(new ToggleRow("TriggerBot (click on crosshair)", cfg.triggerBot,
                                BotTooltips.TRIGGERBOT, v -> cfg.triggerBot = v));
                rows.add(new SliderRow("Band min", 0.5f, 1.0f, cfg.attackCooldownMin, 2,
                                BotTooltips.BAND_MIN, v -> cfg.attackCooldownMin = v));
                rows.add(new SliderRow("Band max", 0.5f, 1.0f, cfg.attackCooldownMax, 2,
                                BotTooltips.BAND_MAX, v -> cfg.attackCooldownMax = v));
                rows.add(new ToggleRow("WTap (S-tap) enabled", cfg.wtapEnabled,
                                BotTooltips.WTAP_ENABLED, v -> cfg.wtapEnabled = v));
                rows.add(new SliderRow("WTap %", 0f, 1f, cfg.wtapChance, 2,
                                BotTooltips.WTAP_CHANCE, v -> cfg.wtapChance = v));
                rows.add(new SliderRow("WTap S min ms", 100, 1000, cfg.wtapMinMs, 0,
                                BotTooltips.WTAP_MIN_MS, v -> cfg.wtapMinMs = Math.round(v)));
                rows.add(new SliderRow("WTap S max ms", 100, 1200, cfg.wtapMaxMs, 0,
                                BotTooltips.WTAP_MAX_MS, v -> cfg.wtapMaxMs = Math.round(v)));
                rows.add(new ToggleRow("Sprint hits only", cfg.sprintHitOnly,
                                BotTooltips.SPRINT_HIT_ONLY, v -> cfg.sprintHitOnly = v));
                rows.add(new ToggleRow("Jump reset", cfg.jumpResetEnabled,
                                BotTooltips.JUMP_RESET, v -> cfg.jumpResetEnabled = v));
                rows.add(new SliderRow("JumpReset min ms", 60, 300, cfg.jumpResetMinMs, 0,
                                BotTooltips.JUMP_RESET_MIN_MS, v -> cfg.jumpResetMinMs = Math.round(v)));
                rows.add(new SliderRow("JumpReset max ms", 60, 400, cfg.jumpResetMaxMs, 0,
                                BotTooltips.JUMP_RESET_MAX_MS, v -> cfg.jumpResetMaxMs = Math.round(v)));
                rows.add(new SliderRow("Sneak hit %", 0f, 3f, cfg.sneakHitChance, 2,
                                BotTooltips.SNEAK_HIT, v -> cfg.sneakHitChance = v));
                rows.add(new SliderRow("Sneak+jump %", 0f, 3f, cfg.sneakJumpHitChance, 2,
                                BotTooltips.SNEAK_JUMP_HIT, v -> cfg.sneakJumpHitChance = v));
                rows.add(new SliderRow("Crit %", 0f, 3f, cfg.critAttemptChance, 2,
                                BotTooltips.CRIT_CHANCE, v -> cfg.critAttemptChance = v));
                rows.add(new SliderRow("MidAir %", 0f, 3f, cfg.midAirChance, 2,
                                BotTooltips.MIDAIR_CHANCE, v -> cfg.midAirChance = v));
                rows.add(new SliderRow("Backoff < blocks", 0f, 3f, cfg.tooCloseDist, 2,
                                BotTooltips.BACKOFF_BELOW, v -> cfg.tooCloseDist = v));
                rows.add(new SliderRow("Backoff release", 0f, 3f, cfg.backoffReleaseDist, 2,
                                BotTooltips.BACKOFF_RELEASE, v -> cfg.backoffReleaseDist = v));
                rows.add(new SliderRow("Backoff max ticks", 6, 100, cfg.maxBackoffTicks, 0,
                                BotTooltips.BACKOFF_MAX, v -> cfg.maxBackoffTicks = Math.round(v)));
                rows.add(new ToggleRow("Backoff spacing", cfg.backoffEnabled,
                                BotTooltips.BACKOFF_ENABLED, v -> cfg.backoffEnabled = v));

                // ---- AIM & SMOOTHNESS ----
                rows.add(new HeaderRow("AIM & SMOOTHNESS", 0xFF55AAFF));
                rows.add(new SliderRow("Anti-Wobble (0 raw … 1 max calm)", 0f, 1f, cfg.antiWobble, 2,
                                BotTooltips.ANTI_WOBBLE, v -> cfg.antiWobble = v));
                rows.add(new ToggleRow("Aim assist", cfg.aimAssistEnabled,
                                BotTooltips.AIM_ASSIST, v -> cfg.aimAssistEnabled = v));
                rows.add(new SliderRow("Assist str", 0f, 3f, cfg.aimAssistStrength, 2,
                                BotTooltips.AIM_ASSIST_STRENGTH, v -> cfg.aimAssistStrength = v));
                rows.add(new SliderRow("Aim smooth min", 0f, 1f, cfg.aimSmoothMin, 2,
                                BotTooltips.SMOOTH_MIN, v -> cfg.aimSmoothMin = v));
                rows.add(new SliderRow("Aim smooth max", 0f, 1f, cfg.aimSmoothMax, 2,
                                BotTooltips.SMOOTH_MAX, v -> cfg.aimSmoothMax = v));
                rows.add(new SliderRow("Turn cap deg", 5f, 180f, cfg.aimMaxTurnDeg, 0,
                                BotTooltips.TURN_CAP, v -> cfg.aimMaxTurnDeg = v));
                rows.add(new SliderRow("Aim noise deg", 0f, 3f, cfg.aimNoiseDeg, 2,
                                BotTooltips.AIM_NOISE, v -> cfg.aimNoiseDeg = v));
                rows.add(new SliderRow("Aim zone (0 Head … 3 Chest)", 0f, 3f, cfg.aimZone, 0,
                                BotTooltips.AIM_ZONE, v -> cfg.aimZone = Math.round(v)));
                rows.add(new SliderRow("Aim lead ticks", 0, 10, cfg.aimLeadTicks, 0,
                                BotTooltips.AIM_LEAD, v -> cfg.aimLeadTicks = Math.round(v)));
                rows.add(new ToggleRow("Aim prediction (accel)", cfg.aimPredict,
                                BotTooltips.AIM_PREDICT, v -> cfg.aimPredict = v));
                rows.add(new ToggleRow("Frame aim (60Hz+)", cfg.frameAim,
                                BotTooltips.FRAME_AIM, v -> cfg.frameAim = v));

                // ---- PURE MODE (v2) ----
                rows.add(new HeaderRow("PURE MODE (v2 four-head brain)", 0xFFB07DFF));
                rows.add(new ToggleRow("Pure mode", cfg.pureMode,
                                BotTooltips.PURE_MODE, v -> PvpBot.get().controller().setPureMode(v)));
                rows.add(new ToggleRow("Pure immediate attack (no TriggerBot)", cfg.pureImmediateAttack,
                                BotTooltips.PURE_IMMEDIATE, v -> cfg.pureImmediateAttack = v));
                rows.add(new SliderRow("Retreat limit (ticks)", 0, 30, cfg.pureRetreatLimit, 0,
                                BotTooltips.PURE_RETREAT, v -> cfg.pureRetreatLimit = Math.round(v)));
                // v2.2.1: the aggression floor — the other half of "backing off
                // tooooo much" (hovering just outside reach never gets punished
                // by the back-move governor because it is not a back-move)
                rows.add(new SliderRow("Aggression floor (ticks beyond 3.0m)", 0, 100, cfg.pureCloseLimit, 0,
                                BotTooltips.PURE_CLOSE, v -> cfg.pureCloseLimit = Math.round(v)));
                rows.add(new SliderRow("Sprint-gate patience (pure, ticks)", 2, 60, cfg.sprintGatePatiencePure, 0,
                                BotTooltips.SPRINT_PATIENCE, v -> cfg.sprintGatePatiencePure = Math.round(v)));
                rows.add(new SliderRow("Click range (blocks, v2.3)", 2.5f, 3.4f, cfg.clickMaxDist, 2,
                                BotTooltips.CLICK_MAX_DIST, v -> cfg.clickMaxDist = v));
                rows.add(new SliderRow("v2 learn rate (rapid)", 0f, 0.001f, cfg.v2LrRapid, 5,
                                BotTooltips.V2_LR, v -> cfg.v2LrRapid = v));
                rows.add(new SliderRow("v2 learn rate (stable)", 0f, 0.001f, cfg.v2LrStable, 5,
                                BotTooltips.V2_LR, v -> cfg.v2LrStable = v));
                rows.add(new ToggleRow("Aim head opt-in (bench until earned)", cfg.pureAimHead,
                                BotTooltips.PURE_AIM_HEAD, v -> cfg.pureAimHead = v));
                rows.add(new ToggleRow("Sneak opt-in (bench until earned)", cfg.pureSneak,
                                BotTooltips.PURE_SNEAK, v -> cfg.pureSneak = v));
                rows.add(new SliderRow("Pure aim blend (legacy)", 0f, 1f, cfg.pureAimAssist, 2,
                                BotTooltips.PURE_AIM_BLEND, v -> cfg.pureAimAssist = v));
                rows.add(new SliderRow("Pure aim max deg", 5f, 90f, cfg.pureAimMaxDeg, 0,
                                BotTooltips.PURE_AIM_MAX, v -> cfg.pureAimMaxDeg = v));
                rows.add(new ToggleRow("Face-target reward shaping", cfg.pureShaping,
                                BotTooltips.PURE_SHAPING, v -> cfg.pureShaping = v));

                // ---- LEARNING & IL ----
                rows.add(new HeaderRow("LEARNING & IMITATION (IL)", 0xFF7DFFA0));
                rows.add(new ToggleRow("Imitation learning (DQfD)", cfg.imitationEnabled,
                                BotTooltips.IMITATION, v -> cfg.imitationEnabled = v));
                rows.add(new SliderRow("Imit. ratio", 0f, 0.6f, cfg.imitationRatio, 2,
                                BotTooltips.IMITATION_RATIO, v -> cfg.imitationRatio = v));
                rows.add(new SliderRow("Kill reward", 6, 120, cfg.winReward, 0,
                                BotTooltips.KILL_REWARD, v -> cfg.winReward = v));
                rows.add(new SliderRow("Loss penalty", -40, 0, cfg.lossReward, 0,
                                BotTooltips.LOSS_REWARD, v -> cfg.lossReward = v));
                rows.add(new SliderRow("Eps cap (exploit floor)", 0f, 0.3f, cfg.epsilonStable, 2,
                                BotTooltips.INNOVATION, v -> cfg.epsilonStable = v));
                rows.add(new ToggleRow("Round text detect", cfg.roundTextDetection,
                                BotTooltips.ROUND_TEXT, v -> cfg.roundTextDetection = v));
                rows.add(new SliderRow("Round debounce ms", 1000, 10000, cfg.roundDebounceMs, 0,
                                BotTooltips.ROUND_DEBOUNCE, v -> cfg.roundDebounceMs = Math.round(v)));
                rows.add(new ToggleRow("IL autoload at launch", cfg.ilAutoLoad,
                                BotTooltips.IL_AUTOLOAD, v -> cfg.ilAutoLoad = v));
                rows.add(new StatusRow(() -> ILStore.get().statusLine(), 0xFFBBD4FF));
                rows.add(new ButtonRow("IL: (RE)LOAD video sessions now", 0xFF9FE870, () -> {
                        status = "loading IL sessions…";
                        final PvpBot bot = PvpBot.get();
                        PvpBot.worker().execute(() -> status = ILStore.get().loadAll(bot));
                }));
                rows.add(new ButtonRow("IL: train 60 bursts on loaded demos", 0xFF9FE870, () -> {
                        ILStore il = ILStore.get();
                        if (!il.isLoaded()) {
                                status = "IL not loaded — load sessions first";
                        } else {
                                il.train(PvpBot.get(), 60);
                                status = "IL training queued (60 bursts)";
                        }
                }));
                rows.add(new ButtonRow("IL: open the il/ folder (drop sessions here)", 0xFFBBD4FF, () -> {
                        java.nio.file.Path d = ILStore.dir();
                        try {
                                java.nio.file.Files.createDirectories(d);
                                java.awt.Desktop.getDesktop().open(d.toFile());
                                status = "opened " + d;
                        } catch (Throwable ex) {
                                status = "il/ folder: " + d;
                        }
                }));

                // ---- HUD & MISC ----
                rows.add(new HeaderRow("HUD & MISC", 0xFFFFDD77));
                rows.add(new ToggleRow("HUD", cfg.hudEnabled, BotTooltips.HUD, v -> cfg.hudEnabled = v));
                rows.add(new ToggleRow("Keystrokes HUD", cfg.keystrokesEnabled,
                                BotTooltips.KEYSTROKES, v -> cfg.keystrokesEnabled = v));
                rows.add(new ToggleRow("Thought HUD", cfg.thoughtHudEnabled,
                                BotTooltips.THOUGHT_HUD, v -> cfg.thoughtHudEnabled = v));
                rows.add(new ToggleRow("Grid snap", cfg.sensitivityGridSnap,
                                BotTooltips.GRID_SNAP, v -> cfg.sensitivityGridSnap = v));
                rows.add(new ButtonRow("Open HUD Layout Editor (move / resize)", 0xFFFFDD77, () -> {
                        save();
                        if (this.client != null) {
                                this.client.setScreen(new PvpBotHudEditScreen(this));
                        }
                }));

                int y = 0;
                for (Row r : rows) {
                        y += r.h + GAP;
                }
                contentH = y;

                addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
                                .dimensions(this.width / 2 - 50, this.height - 26, 100, 20).build());
        }

        private void save() {
                cfg.save();
                status = "saved";
        }

        // ================================================================ layout

        private int panelX() {
                return (this.width - panelW()) / 2;
        }

        private int panelW() {
                return Math.min(380, this.width - 40);
        }

        private int viewTop() {
                return 28;
        }

        private int viewBottom() {
                return this.height - 34;
        }

        private float maxScroll() {
                return Math.max(0, contentH - (viewBottom() - viewTop()));
        }

        private int gripY() {
                float span = viewBottom() - viewTop();
                float m = maxScroll();
                float n = m <= 0 ? 0 : scrollCur / m;
                float gh = Math.max(24, span * (span / Math.max(span, contentH)));
                return (int) (viewTop() + n * (span - gh));
        }

        private int gripH() {
                float span = viewBottom() - viewTop();
                return (int) Math.max(24, span * (span / Math.max(span, contentH)));
        }

        // ================================================================ render

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, float delta) {
                super.render(context, mouseX, mouseY, delta);
                long now = System.currentTimeMillis();
                if (lastFrameMs == 0L) lastFrameMs = now;
                float dtSec = Math.min(0.1f, (now - lastFrameMs) / 1000f);
                lastFrameMs = now;

                // eased scrolling (inertia decays, target approaches)
                if (inertia != 0f) {
                        scrollTarget += inertia * dtSec;
                        if (Math.abs(inertia) < 24f) inertia = 0f;
                        else inertia *= (float) Math.exp(-dtSec * 6.0);
                }
                scrollTarget = Math.max(0f, Math.min(maxScroll(), scrollTarget));
                float ease = 1f - (float) Math.exp(-dtSec * 14.0);
                scrollCur += (scrollTarget - scrollCur) * ease;
                if (Math.abs(scrollTarget - scrollCur) < 0.15f) scrollCur = scrollTarget;

                int px = panelX(), pw = panelW();
                int top = viewTop(), bottom = viewBottom();

                context.fill(px - 6, top - 6, px + pw + 6, bottom + 6, 0xB0080B12);
                context.fill(px - 6, top - 6, px + pw + 6, top - 5, 0xFF3D7BFF);
                context.drawCenteredTextWithShadow(textRenderer,
                                Text.literal("PvPBot Advanced Config"), this.width / 2, top - 20, 0xFFFFFF55);

                context.enableScissor(px, top, px + pw, bottom);
                context.fill(px, top, px + pw, bottom, 0xF40B0E16);

                int y = top - (int) scrollCur;
                hoverTips.clear();
                for (int i = 0; i < rows.size(); i++) {
                        Row r = rows.get(i);
                        int ry = y;
                        y += r.h + GAP;
                        int rh = r.h + GAP;
                        if (ry + rh < top || ry > bottom) continue;
                        boolean hovered = mouseX >= px && mouseX < px + pw
                                        && mouseY >= Math.max(top, ry) && mouseY < Math.min(bottom, ry + r.h)
                                        && !panelDrag && draggingSlider == null;
                        if (hovered && !(r instanceof HeaderRow) && !(r instanceof ButtonRow)) {
                                context.fill(px + 1, Math.max(top, ry), px + pw - 1,
                                                Math.min(bottom, ry + r.h), 0x22FFFFFF);
                        }
                        r.draw(context, px + 2, ry, pw - 4, mouseX, mouseY, hovered);
                        if (r.tooltip() != null) {
                                hoverTips.add(new HoverTip(r, i));
                        }
                }
                context.disableScissor();

                // scrollbar (outside the clip)
                if (maxScroll() > 0) {
                        int sx = px + pw + 2;
                        context.fill(sx, top, sx + 3, bottom, 0xFF141A28);
                        int gy = gripY(), gh = gripH();
                        context.fill(sx, gy, sx + 3, gy + gh,
                                        gripDrag ? 0xFFFFFFFF : 0xFF44506B);
                }

                context.drawCenteredTextWithShadow(textRenderer, status,
                                this.width / 2, this.height - 32, 0xFF55FF99);
                context.drawCenteredTextWithShadow(textRenderer,
                                Text.literal("wheel = scroll   drag = scroll   hover = help"),
                                this.width / 2, this.height - 40, 0xFF667799);

                // tooltips LAST (over everything, outside the scissor)
                if (!panelDrag && draggingSlider == null) {
                        for (HoverTip tip : hoverTips) {
                                // hit-test in CURRENT scroll space: the row was drawn at
                                // its on-screen position this frame — recompute it
                                int ry = viewTop() - (int) scrollCur;
                                for (int i = 0; i < tip.index; i++) ry += rows.get(i).h + GAP;
                                if (mouseY >= ry && mouseY < ry + tip.row.h) {
                                        List<Text> lines = new ArrayList<>();
                                        for (String line : tip.row.tooltip().getString().split("\n")) {
                                                lines.add(Text.literal(line));
                                        }
                                        context.drawTooltip(textRenderer, lines, mouseX, mouseY);
                                        break;
                                }
                        }
                }
        }

        // ================================================================ input

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
                // v2.2.1: the wheel scrolls from ANYWHERE on the screen — the old
                // panel-bounds check made the wheel feel dead whenever the cursor
                // drifted a few pixels outside the panel (the most common way
                // people scroll: park the mouse on the side and spin the wheel).
                if (vertical != 0.0) {
                        inertia = 0f;
                        scrollTarget -= (float) vertical * 42f;
                        scrollTarget = Math.max(0f, Math.min(maxScroll(), scrollTarget));
                        return true;
                }
                return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
        }

        // v2.2.1: keyboard scrolling — arrows / PgUp / PgDn / Home / End — for
        // anyone who prefers keys over the wheel (and trackpads with bad wheels).
        @Override
        public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
                int keyCode = input.key();
                switch (keyCode) {
                        case org.lwjgl.glfw.GLFW.GLFW_KEY_UP -> scrollTarget -= 28f;
                        case org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN -> scrollTarget += 28f;
                        case org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_UP -> scrollTarget -= (viewBottom() - viewTop()) * 0.8f;
                        case org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN -> scrollTarget += (viewBottom() - viewTop()) * 0.8f;
                        case org.lwjgl.glfw.GLFW.GLFW_KEY_HOME -> scrollTarget = 0f;
                        case org.lwjgl.glfw.GLFW.GLFW_KEY_END -> scrollTarget = maxScroll();
                        default -> {
                                return super.keyPressed(input);
                        }
                }
                inertia = 0f;
                scrollTarget = Math.max(0f, Math.min(maxScroll(), scrollTarget));
                return true;
        }

        @Override
        public boolean mouseClicked(Click click, boolean doubled) {
                double mx = click.x(), my = click.y();
                int px = panelX(), pw = panelW();
                int top = viewTop(), bottom = viewBottom();
                if (mx >= px && mx < px + pw && my >= top && my < bottom) {
                        // scrollbar grip?
                        if (mx >= px + pw && mx < px + pw + 6) {
                                gripDrag = true;
                                return true;
                        }
                        // rows (in current scroll space)
                        int ry = top - (int) scrollCur;
                        for (Row r : rows) {
                                if (my >= ry && my < ry + r.h) {
                                        if (r.click(click, px + 2, ry, pw - 4)) {
                                                return true;
                                        }
                                }
                                ry += r.h + GAP;
                        }
                        // background = panel drag scroll
                        panelDrag = true;
                        dragStartY = my;
                        dragStartScroll = scrollTarget;
                        lastDragY = my;
                        inertia = 0f;
                        return true;
                }
                // scrollbar track region (right of the panel)
                if (mx >= px + pw && mx < px + pw + 8 && my >= top && my < bottom) {
                        gripDrag = true;
                        return true;
                }
                return super.mouseClicked(click, doubled);
        }

        @Override
        public boolean mouseDragged(Click click, double deltaX, double deltaY) {
                double mx = click.x(), my = click.y();
                int px = panelX(), pw = panelW();
                if (draggingSlider != null) {
                        int ry = viewTop() - (int) scrollCur;
                        for (Row r : rows) {
                                if (r == draggingSlider) break;
                                ry += r.h + GAP;
                        }
                        draggingSlider.drag(click, px + 2, ry, pw - 4);
                        return true;
                }
                if (gripDrag) {
                        float span = viewBottom() - viewTop();
                        float gh = gripH();
                        float m = maxScroll();
                        float n = span - gh <= 0 ? 0 : (float) ((my - top0()) - gh / 2f) / (span - gh);
                        scrollTarget = Math.max(0f, Math.min(m, n * m));
                        scrollCur = scrollTarget; // grip follows the hand 1:1
                        return true;
                }
                if (panelDrag) {
                        float d = (float) (dragStartScroll - (my - dragStartY));
                        inertia = (float) (lastDragY - my) / Math.max(0.001f, 0.016f);
                        scrollTarget = Math.max(0f, Math.min(maxScroll(), d));
                        lastDragY = my;
                        return true;
                }
                return super.mouseDragged(click, deltaX, deltaY);
        }

        private double top0() {
                return viewTop();
        }

        @Override
        public boolean mouseReleased(Click click) {
                if (draggingSlider != null) {
                        draggingSlider.release();
                        draggingSlider = null;
                        return true;
                }
                if (gripDrag) {
                        gripDrag = false;
                        return true;
                }
                if (panelDrag) {
                        panelDrag = false;
                        // momentum flick: keep a fraction of the release velocity
                        scrollTarget = Math.max(0f, Math.min(maxScroll(), scrollTarget + inertia * 0.12f));
                        return true;
                }
                return super.mouseReleased(click);
        }

        @Override
        public void close() {
                save();
                if (client != null) client.setScreen(parent);
        }
}
