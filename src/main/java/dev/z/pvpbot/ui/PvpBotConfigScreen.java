package dev.z.pvpbot.ui;

import dev.z.pvpbot.BotConfig;
import dev.z.pvpbot.PvpBot;
import dev.z.pvpbot.ml.ILStore;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * v2.3 — PvPBot config, fully custom UI (no Minecraft buttons).
 *
 * The v2.2 screen's switches and sliders were STATIC: each row captured the
 * value it had when the screen opened and drew that forever, so a click
 * changed the setting invisibly. Every row now reads the LIVE config value
 * every frame (and the defaults instance for right-click reset).
 *
 * Layout: header + section sidebar (click = jump) + smooth-scrolling content
 * + footer. Rows: animated switches, sliders (drag, or scroll with SHIFT for
 * fine steps), segmented choices, action buttons, live status lines.
 * Right-click any setting to restore its default. Hover for help.
 */
public final class PvpBotConfigScreen extends Screen {

        private final Screen parent;
        private final BotConfig cfg;
        private final BotConfig defaults = new BotConfig();
        private String status = "All changes save instantly";
        private long statusMs = 0L;

        private static final int ROW_H = 26;
        private static final int SECTION_H = 24;
        private static final int GAP = 3;

        private final List<Row> rows = new ArrayList<>();
        private final List<Section> sections = new ArrayList<>();
        private int contentH = 0;

        private float scrollCur = 0f, scrollTarget = 0f;
        private boolean gripDrag = false;
        private SliderRow dragging = null;
        private long lastFrameMs = 0L;
        private float doneHover = 0f;
        private float openAnim = 0f;

        public PvpBotConfigScreen(Screen parent, int page) {
                super(Text.literal("PvPBot Config"));
                this.parent = parent;
                this.cfg = PvpBot.get().config();
        }

        // ================================================================ model

        private static final class Section {
                final String name;
                final int color;
                int y;      // content-space y of its header row
                float hover;

                Section(String name, int color) {
                        this.name = name;
                        this.color = color;
                }
        }

        private abstract class Row {
                int h = ROW_H;
                int y;      // content-space y
                float hover;
                String label = "";
                String tip = null;

                abstract void draw(DrawContext c, int x, int y, int w, double mx, double my, float dt);

                boolean click(Click click, int x, int y, int w) {
                        return false;
                }

                void drag(double mx, int x, int w) {
                }

                boolean scroll(double v, boolean fine) {
                        return false;
                }

                boolean resetDefault() {
                        return false;
                }

                boolean interactive() {
                        return true;
                }
        }

        private final class SectionRow extends Row {
                final Section sec;

                SectionRow(Section sec) {
                        this.sec = sec;
                        this.h = SECTION_H;
                }

                @Override
                void draw(DrawContext c, int x, int y, int w, double mx, double my, float dt) {
                        c.fill(x, y + 6, x + 3, y + 18, sec.color);
                        Gfx.text(c, textRenderer, sec.name.toUpperCase(), x + 9, y + 8, sec.color);
                        c.fill(x + 12 + textRenderer.getWidth(sec.name.toUpperCase()), y + 12, x + w, y + 13, Gfx.BORDER);
                }

                @Override
                boolean interactive() {
                        return false;
                }
        }

        private final class ToggleRow extends Row {
                final Function<BotConfig, Boolean> get;
                final BiConsumer<BotConfig, Boolean> set;
                float knob = -1f;

                ToggleRow(String label, String tip, Function<BotConfig, Boolean> get, BiConsumer<BotConfig, Boolean> set) {
                        this.label = label;
                        this.tip = tip;
                        this.get = get;
                        this.set = set;
                }

                @Override
                void draw(DrawContext c, int x, int y, int w, double mx, double my, float dt) {
                        boolean on = get.apply(cfg);
                        if (knob < 0f) knob = on ? 1f : 0f;
                        knob = Gfx.approach(knob, on ? 1f : 0f, dt, 18f);
                        Gfx.text(c, textRenderer, label, x + 10, y + 9, Gfx.TEXT);
                        String st = on ? "ON" : "OFF";
                        Gfx.textRight(c, textRenderer, st, x + w - 40, y + 9, on ? Gfx.GOOD : Gfx.DIM);
                        Gfx.toggle(c, x + w - 34, y + 7, knob, hover > 0.5f);
                }

                @Override
                boolean click(Click click, int x, int y, int w) {
                        set.accept(cfg, !get.apply(cfg));
                        saved(label + ": " + (get.apply(cfg) ? "ON" : "OFF"));
                        return true;
                }

                @Override
                boolean resetDefault() {
                        set.accept(cfg, get.apply(defaults));
                        saved(label + " reset to default");
                        return true;
                }
        }

        private final class SliderRow extends Row {
                final float min, max;
                final int digits;
                final Function<BotConfig, Float> get;
                final BiConsumer<BotConfig, Float> set;
                final String unit;
                float shown = Float.NaN;

                SliderRow(String label, String unit, float min, float max, int digits, String tip,
                          Function<BotConfig, Float> get, BiConsumer<BotConfig, Float> set) {
                        this.label = label;
                        this.unit = unit;
                        this.min = min;
                        this.max = max;
                        this.digits = digits;
                        this.tip = tip;
                        this.get = get;
                        this.set = set;
                        this.h = ROW_H + 4;
                }

                float norm(float v) {
                        return (v - min) / (max - min);
                }

                void setValue(float v) {
                        v = Math.max(min, Math.min(max, v));
                        float step = (float) Math.pow(10, -digits);
                        v = Math.round(v / step) * step;
                        set.accept(cfg, v);
                }

                String fmt(float v) {
                        String n = digits == 0 ? String.format("%.0f", v) : String.format("%." + digits + "f", v);
                        return unit.isEmpty() ? n : n + " " + unit;
                }

                @Override
                void draw(DrawContext c, int x, int y, int w, double mx, double my, float dt) {
                        float v = get.apply(cfg);
                        float n = norm(v);
                        shown = Float.isNaN(shown) ? n : (dragging == this ? n : Gfx.approach(shown, n, dt, 20f));
                        Gfx.text(c, textRenderer, label, x + 10, y + 5, Gfx.TEXT);
                        String val = fmt(v);
                        int vw = textRenderer.getWidth(val) + 8;
                        Gfx.round(c, x + w - 10 - vw, y + 3, vw, 12, dragging == this ? Gfx.ACCENT_2 : Gfx.SURFACE_HI);
                        Gfx.textRight(c, textRenderer, val, x + w - 14, y + 5, Gfx.TEXT);
                        Gfx.slider(c, x + 10, y + 20, w - 20, shown, dragging == this, hover > 0.5f);
                }

                @Override
                boolean click(Click click, int x, int y, int w) {
                        dragging = this;
                        drag(click.x(), x, w);
                        return true;
                }

                @Override
                void drag(double mx, int x, int w) {
                        double rel = (mx - (x + 14)) / Math.max(1.0, w - 28.0);
                        setValue((float) (min + Math.max(0.0, Math.min(1.0, rel)) * (max - min)));
                }

                @Override
                boolean scroll(double v, boolean fine) {
                        if (!fine) return false;
                        float step = (float) Math.pow(10, -digits);
                        float span = (max - min) / 100f;
                        setValue(get.apply(cfg) + (float) Math.signum(v) * Math.max(step, span));
                        saved(label + " = " + fmt(get.apply(cfg)));
                        return true;
                }

                @Override
                boolean resetDefault() {
                        set.accept(cfg, get.apply(defaults));
                        saved(label + " reset to " + fmt(get.apply(cfg)));
                        return true;
                }
        }

        private final class ChoiceRow extends Row {
                final String[] options;
                final Function<BotConfig, Integer> get;
                final BiConsumer<BotConfig, Integer> set;
                float sel = -1f;

                ChoiceRow(String label, String tip, String[] options, Function<BotConfig, Integer> get,
                          BiConsumer<BotConfig, Integer> set) {
                        this.label = label;
                        this.tip = tip;
                        this.options = options;
                        this.get = get;
                        this.set = set;
                        this.h = ROW_H + 8;
                }

                @Override
                void draw(DrawContext c, int x, int y, int w, double mx, double my, float dt) {
                        int cur = Math.max(0, Math.min(options.length - 1, get.apply(cfg)));
                        if (sel < 0f) sel = cur;
                        sel = Gfx.approach(sel, cur, dt, 16f);
                        Gfx.text(c, textRenderer, label, x + 10, y + 4, Gfx.TEXT);
                        int bx = x + 10, bw = w - 20, by = y + 15, bh = 14;
                        Gfx.round(c, bx, by, bw, bh, Gfx.TRACK);
                        int segW = bw / options.length;
                        Gfx.round(c, bx + (int) (sel * segW) + 1, by + 1, segW - 2, bh - 2, Gfx.ACCENT);
                        for (int i = 0; i < options.length; i++) {
                                Gfx.textCentered(c, textRenderer, options[i], bx + i * segW + segW / 2, by + 3,
                                                i == cur ? 0xFFFFFFFF : Gfx.MUTED);
                        }
                }

                @Override
                boolean click(Click click, int x, int y, int w) {
                        int bx = x + 10, bw = w - 20;
                        int segW = bw / options.length;
                        int i = (int) ((click.x() - bx) / Math.max(1, segW));
                        if (i < 0 || i >= options.length || click.y() < y + 13) return true;
                        set.accept(cfg, i);
                        saved(label + ": " + options[i]);
                        return true;
                }

                @Override
                boolean resetDefault() {
                        set.accept(cfg, get.apply(defaults));
                        saved(label + " reset");
                        return true;
                }
        }

        private final class ButtonRow extends Row {
                final int color;
                final Runnable action;

                ButtonRow(String label, int color, String tip, Runnable action) {
                        this.label = label;
                        this.color = color;
                        this.tip = tip;
                        this.action = action;
                }

                @Override
                void draw(DrawContext c, int x, int y, int w, double mx, double my, float dt) {
                        Gfx.button(c, textRenderer, x + 6, y + 3, w - 12, h - 6, label, color, hover, true);
                }

                @Override
                boolean click(Click click, int x, int y, int w) {
                        action.run();
                        return true;
                }
        }

        private final class StatusRow extends Row {
                final Supplier<String> text;

                StatusRow(Supplier<String> text) {
                        this.text = text;
                        this.h = 18;
                }

                @Override
                void draw(DrawContext c, int x, int y, int w, double mx, double my, float dt) {
                        String s = textRenderer.trimToWidth(text.get(), w - 24);
                        Gfx.round(c, x + 6, y + 1, w - 12, 16, 0xFF0F1422);
                        Gfx.text(c, textRenderer, s, x + 12, y + 5, 0xFFB7C6EA);
                }

                @Override
                boolean interactive() {
                        return false;
                }
        }

        // ================================================================ build

        private void section(String name, int color) {
                Section s = new Section(name, color);
                sections.add(s);
                rows.add(new SectionRow(s));
        }

        private void toggle(String label, String tip, Function<BotConfig, Boolean> g, BiConsumer<BotConfig, Boolean> s) {
                rows.add(new ToggleRow(label, tip, g, s));
        }

        private void slider(String label, String unit, float min, float max, int digits, String tip,
                            Function<BotConfig, Float> g, BiConsumer<BotConfig, Float> s) {
                rows.add(new SliderRow(label, unit, min, max, digits, tip, g, s));
        }

        private void islider(String label, String unit, int min, int max, String tip,
                             Function<BotConfig, Integer> g, BiConsumer<BotConfig, Integer> s) {
                rows.add(new SliderRow(label, unit, min, max, 0, tip, c -> (float) g.apply(c), (c, v) -> s.accept(c, Math.round(v))));
        }

        private static String tipOf(Text t) {
                return t == null ? null : t.getString();
        }

        @Override
        protected void init() {
                rows.clear();
                sections.clear();

                section("Combat", 0xFFFF9A6B);
                toggle("TriggerBot", tipOf(BotTooltips.TRIGGERBOT), c -> c.triggerBot, (c, v) -> c.triggerBot = v);
                slider("Attack band min", "", 0.5f, 1.0f, 2, tipOf(BotTooltips.BAND_MIN), c -> c.attackCooldownMin, (c, v) -> c.attackCooldownMin = v);
                slider("Attack band max", "", 0.5f, 1.0f, 2, tipOf(BotTooltips.BAND_MAX), c -> c.attackCooldownMax, (c, v) -> c.attackCooldownMax = v);
                slider("Click range", "blocks", 2.5f, 3.2f, 2, tipOf(BotTooltips.CLICK_MAX_DIST), c -> c.clickMaxDist, (c, v) -> c.clickMaxDist = v);
                toggle("Sprint hits only", tipOf(BotTooltips.SPRINT_HIT_ONLY), c -> c.sprintHitOnly, (c, v) -> c.sprintHitOnly = v);
                toggle("W-Tap (S-tap)", tipOf(BotTooltips.WTAP_ENABLED), c -> c.wtapEnabled, (c, v) -> c.wtapEnabled = v);
                slider("W-Tap chance", "", 0f, 1f, 2, tipOf(BotTooltips.WTAP_CHANCE), c -> c.wtapChance, (c, v) -> c.wtapChance = v);
                islider("W-Tap S hold min", "ms", 100, 1000, tipOf(BotTooltips.WTAP_MIN_MS), c -> c.wtapMinMs, (c, v) -> c.wtapMinMs = v);
                islider("W-Tap S hold max", "ms", 100, 1200, tipOf(BotTooltips.WTAP_MAX_MS), c -> c.wtapMaxMs, (c, v) -> c.wtapMaxMs = v);
                toggle("Jump reset", tipOf(BotTooltips.JUMP_RESET), c -> c.jumpResetEnabled, (c, v) -> c.jumpResetEnabled = v);
                islider("Jump reset min", "ms", 0, 300, tipOf(BotTooltips.JUMP_RESET_MIN_MS), c -> c.jumpResetMinMs, (c, v) -> c.jumpResetMinMs = v);
                islider("Jump reset max", "ms", 0, 400, tipOf(BotTooltips.JUMP_RESET_MAX_MS), c -> c.jumpResetMaxMs, (c, v) -> c.jumpResetMaxMs = v);
                slider("Sneak hit chance", "", 0f, 1f, 2, tipOf(BotTooltips.SNEAK_HIT), c -> c.sneakHitChance, (c, v) -> c.sneakHitChance = v);
                slider("Sneak + jump chance", "", 0f, 1f, 2, tipOf(BotTooltips.SNEAK_JUMP_HIT), c -> c.sneakJumpHitChance, (c, v) -> c.sneakJumpHitChance = v);
                slider("Crit chance", "", 0f, 1f, 2, tipOf(BotTooltips.CRIT_CHANCE), c -> c.critAttemptChance, (c, v) -> c.critAttemptChance = v);
                slider("Mid-air hit chance", "", 0f, 1f, 2, tipOf(BotTooltips.MIDAIR_CHANCE), c -> c.midAirChance, (c, v) -> c.midAirChance = v);
                toggle("Combo breaker", "Classic brain: after taking 2+ hits in a row, sprint-strafe toward the side the opponent's aim is weakest instead of running straight back (that keeps you in their combo). The jump reset still fires.",
                                c -> c.comboBreaker, (c, v) -> c.comboBreaker = v);
                toggle("Crit denial", "Classic brain: when the opponent jumps in crit range, hold the edge of reach (~3 blocks) until they land and never jump to trade crits. Their falling crit comes up short or drifts into your grounded sprint hit.",
                                c -> c.critDenial, (c, v) -> c.critDenial = v);
                toggle("Backoff spacing", tipOf(BotTooltips.BACKOFF_ENABLED), c -> c.backoffEnabled, (c, v) -> c.backoffEnabled = v);
                slider("Backoff below", "blocks", 0f, 3f, 2, tipOf(BotTooltips.BACKOFF_BELOW), c -> c.tooCloseDist, (c, v) -> c.tooCloseDist = v);
                slider("Backoff release", "blocks", 0f, 3f, 2, tipOf(BotTooltips.BACKOFF_RELEASE), c -> c.backoffReleaseDist, (c, v) -> c.backoffReleaseDist = v);
                islider("Backoff max", "ticks", 6, 100, tipOf(BotTooltips.BACKOFF_MAX), c -> c.maxBackoffTicks, (c, v) -> c.maxBackoffTicks = v);

                section("Aim", 0xFF5BA8FF);
                slider("Anti-wobble", "", 0f, 1f, 2, tipOf(BotTooltips.ANTI_WOBBLE), c -> c.antiWobble, (c, v) -> c.antiWobble = v);
                rows.add(new ChoiceRow("Aim zone", tipOf(BotTooltips.AIM_ZONE), new String[]{"Head", "Eyes", "Neck", "Chest"},
                                c -> c.aimZone, (c, v) -> c.aimZone = v));
                toggle("Aim assist", tipOf(BotTooltips.AIM_ASSIST), c -> c.aimAssistEnabled, (c, v) -> c.aimAssistEnabled = v);
                slider("Assist strength", "", 0f, 1f, 2, tipOf(BotTooltips.AIM_ASSIST_STRENGTH), c -> c.aimAssistStrength, (c, v) -> c.aimAssistStrength = v);
                slider("Smoothing min", "", 0f, 1f, 2, tipOf(BotTooltips.SMOOTH_MIN), c -> c.aimSmoothMin, (c, v) -> c.aimSmoothMin = v);
                slider("Smoothing max", "", 0f, 1f, 2, tipOf(BotTooltips.SMOOTH_MAX), c -> c.aimSmoothMax, (c, v) -> c.aimSmoothMax = v);
                slider("Turn cap", "°/tick", 5f, 180f, 0, tipOf(BotTooltips.TURN_CAP), c -> c.aimMaxTurnDeg, (c, v) -> c.aimMaxTurnDeg = v);
                slider("Micro noise", "°", 0f, 1f, 2, tipOf(BotTooltips.AIM_NOISE), c -> c.aimNoiseDeg, (c, v) -> c.aimNoiseDeg = v);
                islider("Lead", "ticks", 0, 6, tipOf(BotTooltips.AIM_LEAD), c -> c.aimLeadTicks, (c, v) -> c.aimLeadTicks = v);
                toggle("Head priority", "Wander inside the head zone (chin to crown) instead of the whole body.",
                                c -> c.aimHeadPriority, (c, v) -> c.aimHeadPriority = v);
                toggle("Threaded 120 Hz aim", "Aim is computed on its own 120 Hz thread and injected every frame (smoothest). Off = per-frame aim.",
                                c -> c.threadedAim, (c, v) -> {
                                        c.threadedAim = v;
                                        if (v) PvpBot.get().controller().aimThread.ensureStarted();
                                });
                toggle("Frame aim (60 Hz+)", tipOf(BotTooltips.FRAME_AIM), c -> c.frameAim, (c, v) -> c.frameAim = v);

                section("Pure mode (v2 brain)", 0xFFB48CFF);
                toggle("Pure mode", tipOf(BotTooltips.PURE_MODE), c -> c.pureMode, (c, v) -> PvpBot.get().controller().setPureMode(v));
                toggle("Immediate attack", tipOf(BotTooltips.PURE_IMMEDIATE), c -> c.pureImmediateAttack, (c, v) -> c.pureImmediateAttack = v);
                islider("Retreat limit", "ticks", 0, 30, tipOf(BotTooltips.PURE_RETREAT), c -> c.pureRetreatLimit, (c, v) -> c.pureRetreatLimit = v);
                islider("Aggression floor", "ticks", 0, 100, tipOf(BotTooltips.PURE_CLOSE), c -> c.pureCloseLimit, (c, v) -> c.pureCloseLimit = v);
                islider("Sprint-gate patience", "ticks", 2, 60, tipOf(BotTooltips.SPRINT_PATIENCE), c -> c.sprintGatePatiencePure, (c, v) -> c.sprintGatePatiencePure = v);
                slider("Learn rate (rapid)", "", 0f, 0.0005f, 5, tipOf(BotTooltips.V2_LR), c -> c.v2LrRapid, (c, v) -> c.v2LrRapid = v);
                slider("Learn rate (stable)", "", 0f, 0.0005f, 5, tipOf(BotTooltips.V2_LR), c -> c.v2LrStable, (c, v) -> c.v2LrStable = v);
                toggle("Aim head (opt-in)", tipOf(BotTooltips.PURE_AIM_HEAD), c -> c.pureAimHead, (c, v) -> c.pureAimHead = v);
                toggle("Sneak muscle (opt-in)", tipOf(BotTooltips.PURE_SNEAK), c -> c.pureSneak, (c, v) -> c.pureSneak = v);
                slider("Aim max per tick", "°", 5f, 90f, 0, tipOf(BotTooltips.PURE_AIM_MAX), c -> c.pureAimMaxDeg, (c, v) -> c.pureAimMaxDeg = v);
                toggle("Face-target shaping", tipOf(BotTooltips.PURE_SHAPING), c -> c.pureShaping, (c, v) -> c.pureShaping = v);
                rows.add(new StatusRow(() -> PvpBot.get().controller().v2StatusLine()));

                section("Learning", 0xFF6BE3A0);
                toggle("Imitation (DQfD)", tipOf(BotTooltips.IMITATION), c -> c.imitationEnabled, (c, v) -> c.imitationEnabled = v);
                slider("Imitation share", "", 0f, 0.6f, 2, tipOf(BotTooltips.IMITATION_RATIO), c -> c.imitationRatio, (c, v) -> c.imitationRatio = v);
                slider("Kill reward", "", 6f, 120f, 0, tipOf(BotTooltips.KILL_REWARD), c -> c.winReward, (c, v) -> c.winReward = v);
                slider("Loss penalty", "", -40f, 0f, 0, tipOf(BotTooltips.LOSS_REWARD), c -> c.lossReward, (c, v) -> c.lossReward = v);
                slider("Exploration floor", "", 0f, 0.3f, 2, tipOf(BotTooltips.INNOVATION), c -> c.epsilonStable, (c, v) -> c.epsilonStable = v);
                toggle("Round text detection", tipOf(BotTooltips.ROUND_TEXT), c -> c.roundTextDetection, (c, v) -> c.roundTextDetection = v);
                islider("Round debounce", "ms", 1000, 10000, tipOf(BotTooltips.ROUND_DEBOUNCE), c -> c.roundDebounceMs, (c, v) -> c.roundDebounceMs = v);
                toggle("IL autoload", tipOf(BotTooltips.IL_AUTOLOAD), c -> c.ilAutoLoad, (c, v) -> c.ilAutoLoad = v);
                rows.add(new StatusRow(() -> ILStore.get().statusLine()));
                rows.add(new ButtonRow("Load IL video sessions", 0xFF2B5E46, "Scan config/pvpbot/il/ and load every session into both brains.", () -> {
                        saved("Loading IL sessions…");
                        final PvpBot bot = PvpBot.get();
                        PvpBot.worker().execute(() -> saved(ILStore.get().loadAll(bot)));
                }));
                rows.add(new ButtonRow("Train 60 bursts on IL demos", 0xFF2B5E46, "Queue 60 imitation training bursts on the loaded demos.", () -> {
                        ILStore il = ILStore.get();
                        if (!il.isLoaded()) {
                                saved("IL not loaded — load sessions first");
                        } else {
                                il.train(PvpBot.get(), 60);
                                saved("IL training queued (60 bursts)");
                        }
                }));

                section("HUD & misc", 0xFFFFD36B);
                toggle("HUD", tipOf(BotTooltips.HUD), c -> c.hudEnabled, (c, v) -> c.hudEnabled = v);
                toggle("Keystrokes", tipOf(BotTooltips.KEYSTROKES), c -> c.keystrokesEnabled, (c, v) -> c.keystrokesEnabled = v);
                toggle("Thought line", tipOf(BotTooltips.THOUGHT_HUD), c -> c.thoughtHudEnabled, (c, v) -> c.thoughtHudEnabled = v);
                toggle("Trade log", "Show the last hits / crits / misses / hits taken.", c -> c.tradeLogEnabled, (c, v) -> c.tradeLogEnabled = v);
                toggle("Training stats", "Show the learning panel (epsilon, loss, reward, buffer).", c -> c.trainingStatsEnabled, (c, v) -> c.trainingStatsEnabled = v);
                toggle("Combo meter", "Show the combo counter.", c -> c.comboMeterEnabled, (c, v) -> c.comboMeterEnabled = v);
                rows.add(new ButtonRow("Open HUD layout editor", 0xFF5E4A1F, "Drag HUD elements to move them, scroll on one to resize it.", () -> {
                        cfg.save();
                        if (client != null) client.setScreen(new PvpBotHudEditScreen(this));
                }));

                layoutRows();
        }

        private void layoutRows() {
                int y = 0;
                for (Row r : rows) {
                        r.y = y;
                        if (r instanceof SectionRow sr) sr.sec.y = y;
                        y += r.h + GAP;
                }
                contentH = y + 6;
        }

        private void saved(String msg) {
                cfg.save();
                status = msg;
                statusMs = System.currentTimeMillis();
        }

        // ================================================================ layout

        private int pw() {
                return Math.min(this.width - 16, 560);
        }

        private int ph() {
                return Math.min(this.height - 16, 420);
        }

        private int px() {
                return (this.width - pw()) / 2;
        }

        private int py() {
                return (this.height - ph()) / 2;
        }

        private int sideW() {
                return pw() >= 380 ? 112 : 0;
        }

        private int contentX() {
                return px() + sideW() + 8;
        }

        private int contentW() {
                return pw() - sideW() - 22;
        }

        private int viewTop() {
                return py() + 38;
        }

        private int viewBottom() {
                return py() + ph() - 34;
        }

        private float maxScroll() {
                return Math.max(0, contentH - (viewBottom() - viewTop()));
        }

        // ================================================================ render

        @Override
        public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
                c.fillGradient(0, 0, this.width, this.height, 0xB0060810, 0xD0080B14);
        }

        @Override
        public void render(DrawContext c, int mouseX, int mouseY, float delta) {
                long now = System.currentTimeMillis();
                if (lastFrameMs == 0L) lastFrameMs = now;
                float dt = Math.min(0.1f, (now - lastFrameMs) / 1000f);
                lastFrameMs = now;
                openAnim = Gfx.approach(openAnim, 1f, dt, 10f);

                scrollTarget = Math.max(0f, Math.min(maxScroll(), scrollTarget));
                scrollCur = Gfx.approach(scrollCur, scrollTarget, dt, 16f);

                int px = px(), py = py() + (int) ((1f - openAnim) * 12), pw = pw(), ph = ph();
                Gfx.shadow(c, px, py, pw, ph);
                Gfx.card(c, px, py, pw, ph, Gfx.BG, Gfx.BORDER);
                // header
                c.fillGradient(px + 1, py + 1, px + pw - 1, py + 3, Gfx.ACCENT, Gfx.ACCENT_2);
                Gfx.text(c, textRenderer, "PvPBot", px + 12, py + 12, 0xFFFFFFFF);
                Gfx.text(c, textRenderer, "settings", px + 14 + textRenderer.getWidth("PvPBot"), py + 12, Gfx.MUTED);
                String mode = cfg.pureMode ? "PURE v2" : "CLASSIC v1";
                int mw = textRenderer.getWidth(mode) + 12;
                Gfx.round(c, px + pw - 12 - mw, py + 9, mw, 14, cfg.pureMode ? 0xFF4B2F86 : 0xFF23406E);
                Gfx.textCentered(c, textRenderer, mode, px + pw - 12 - mw / 2, py + 12, 0xFFFFFFFF);
                c.fill(px + 8, py + 31, px + pw - 8, py + 32, Gfx.BORDER);

                // sidebar
                int sw = sideW();
                if (sw > 0) {
                        int sy = viewTop();
                        String active = activeSection();
                        for (Section s : sections) {
                                boolean hov = Gfx.in(mouseX, mouseY, px + 8, sy, sw - 8, 20);
                                s.hover = Gfx.approach(s.hover, hov ? 1f : 0f, dt, 14f);
                                boolean act = s.name.equals(active);
                                int fill = act ? Gfx.SURFACE_HOVER : Gfx.lerpColor(Gfx.BG, Gfx.SURFACE_HI, s.hover);
                                Gfx.round(c, px + 8, sy, sw - 8, 20, fill);
                                if (act) c.fill(px + 8, sy + 4, px + 10, sy + 16, s.color);
                                Gfx.text(c, textRenderer, s.name, px + 16, sy + 6, act ? 0xFFFFFFFF : Gfx.MUTED);
                                sy += 23;
                        }
                        Gfx.text(c, textRenderer, "Right-click: default", px + 12, viewBottom() - 22, Gfx.DIM);
                        Gfx.text(c, textRenderer, "Shift+wheel: fine", px + 12, viewBottom() - 11, Gfx.DIM);
                }

                // content
                int cx = contentX(), cw = contentW(), top = viewTop(), bottom = viewBottom();
                c.enableScissor(cx, top, cx + cw, bottom);
                Row hovered = null;
                for (Row r : rows) {
                        int ry = top + r.y - (int) scrollCur;
                        if (ry + r.h < top || ry > bottom) continue;
                        boolean hov = r.interactive() && dragging == null && !gripDrag
                                        && Gfx.in(mouseX, mouseY, cx, Math.max(top, ry), cw, Math.min(bottom, ry + r.h) - Math.max(top, ry));
                        r.hover = Gfx.approach(r.hover, hov || dragging == r ? 1f : 0f, dt, 16f);
                        if (r.interactive() && !(r instanceof ButtonRow)) {
                                Gfx.round(c, cx, ry, cw, r.h, Gfx.lerpColor(Gfx.SURFACE, Gfx.SURFACE_HOVER, r.hover));
                        }
                        r.draw(c, cx, ry, cw, mouseX, mouseY, dt);
                        if (hov) hovered = r;
                }
                c.disableScissor();

                // scrollbar
                if (maxScroll() > 0) {
                        int sx = cx + cw + 4;
                        float span = bottom - top;
                        float gh = Math.max(24f, span * span / Math.max(span, contentH));
                        float gy = top + (scrollCur / maxScroll()) * (span - gh);
                        Gfx.round(c, sx, top, 4, bottom - top, Gfx.SURFACE);
                        Gfx.round(c, sx, (int) gy, 4, (int) gh, gripDrag ? Gfx.ACCENT : Gfx.BORDER);
                }

                // footer
                int fy = py + ph - 28;
                c.fill(px + 8, fy - 3, px + pw - 8, fy - 2, Gfx.BORDER);
                boolean fresh = now - statusMs < 2500;
                Gfx.text(c, textRenderer, textRenderer.trimToWidth(status, pw - 130), px + 12, fy + 7, fresh ? Gfx.GOOD : Gfx.MUTED);
                int bx = px + pw - 92, bw = 80;
                boolean dh = Gfx.in(mouseX, mouseY, bx, fy + 1, bw, 20);
                doneHover = Gfx.approach(doneHover, dh ? 1f : 0f, dt, 16f);
                Gfx.button(c, textRenderer, bx, fy + 1, bw, 20, "Done", Gfx.ACCENT, doneHover, true);

                // tooltip on top of everything
                if (hovered != null && hovered.tip != null && dragging == null) {
                        c.createNewRootLayer();
                        Gfx.tooltip(c, textRenderer, hovered.label, hovered.tip, mouseX, mouseY, this.width, this.height);
                }
        }

        private String activeSection() {
                String name = sections.isEmpty() ? "" : sections.get(0).name;
                for (Section s : sections) {
                        if (s.y - scrollCur <= 30) name = s.name;
                }
                return name;
        }

        // ================================================================ input

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
                boolean fine = hasShiftDown();
                if (fine) {
                        Row r = rowAt(mouseX, mouseY);
                        if (r != null && r.scroll(vertical, true)) return true;
                }
                scrollTarget = Math.max(0f, Math.min(maxScroll(), scrollTarget - (float) vertical * 40f));
                return true;
        }

        private static boolean hasShiftDown() {
                long h = net.minecraft.client.MinecraftClient.getInstance().getWindow().getHandle();
                return org.lwjgl.glfw.GLFW.glfwGetKey(h, org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT) == org.lwjgl.glfw.GLFW.GLFW_PRESS
                                || org.lwjgl.glfw.GLFW.glfwGetKey(h, org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT) == org.lwjgl.glfw.GLFW.GLFW_PRESS;
        }

        private Row rowAt(double mx, double my) {
                int cx = contentX(), cw = contentW(), top = viewTop(), bottom = viewBottom();
                if (!Gfx.in(mx, my, cx, top, cw, bottom - top)) return null;
                for (Row r : rows) {
                        int ry = top + r.y - (int) scrollCur;
                        if (my >= ry && my < ry + r.h) return r;
                }
                return null;
        }

        @Override
        public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
                int k = input.key();
                float page = (viewBottom() - viewTop()) * 0.8f;
                switch (k) {
                        case org.lwjgl.glfw.GLFW.GLFW_KEY_UP -> scrollTarget -= 30f;
                        case org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN -> scrollTarget += 30f;
                        case org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_UP -> scrollTarget -= page;
                        case org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN -> scrollTarget += page;
                        case org.lwjgl.glfw.GLFW.GLFW_KEY_HOME -> scrollTarget = 0f;
                        case org.lwjgl.glfw.GLFW.GLFW_KEY_END -> scrollTarget = maxScroll();
                        default -> {
                                return super.keyPressed(input);
                        }
                }
                scrollTarget = Math.max(0f, Math.min(maxScroll(), scrollTarget));
                return true;
        }

        @Override
        public boolean mouseClicked(Click click, boolean doubled) {
                double mx = click.x(), my = click.y();
                int fy = py() + ph() - 28;
                if (Gfx.in(mx, my, px() + pw() - 92, fy + 1, 80, 20)) {
                        close();
                        return true;
                }
                // sidebar jump
                if (sideW() > 0) {
                        int sy = viewTop();
                        for (Section s : sections) {
                                if (Gfx.in(mx, my, px() + 8, sy, sideW() - 8, 20)) {
                                        scrollTarget = Math.max(0f, Math.min(maxScroll(), s.y));
                                        return true;
                                }
                                sy += 23;
                        }
                }
                // scrollbar
                int sx = contentX() + contentW() + 2;
                if (maxScroll() > 0 && Gfx.in(mx, my, sx, viewTop(), 10, viewBottom() - viewTop())) {
                        gripDrag = true;
                        gripTo(my);
                        return true;
                }
                Row r = rowAt(mx, my);
                if (r != null && r.interactive()) {
                        int ry = viewTop() + r.y - (int) scrollCur;
                        if (click.button() == 1) {
                                return r.resetDefault();
                        }
                        if (click.button() == 0) {
                                return r.click(click, contentX(), ry, contentW());
                        }
                }
                return super.mouseClicked(click, doubled);
        }

        private void gripTo(double my) {
                float span = viewBottom() - viewTop();
                float gh = Math.max(24f, span * span / Math.max(span, contentH));
                float n = (float) ((my - viewTop() - gh / 2) / Math.max(1f, span - gh));
                scrollTarget = Math.max(0f, Math.min(maxScroll(), n * maxScroll()));
                scrollCur = scrollTarget;
        }

        @Override
        public boolean mouseDragged(Click click, double dx, double dy) {
                if (dragging != null) {
                        dragging.drag(click.x(), contentX(), contentW());
                        return true;
                }
                if (gripDrag) {
                        gripTo(click.y());
                        return true;
                }
                return super.mouseDragged(click, dx, dy);
        }

        @Override
        public boolean mouseReleased(Click click) {
                if (dragging != null) {
                        SliderRow s = dragging;
                        dragging = null;
                        saved(s.label + " = " + s.fmt(s.get.apply(cfg)));
                        return true;
                }
                if (gripDrag) {
                        gripDrag = false;
                        return true;
                }
                return super.mouseReleased(click);
        }

        @Override
        public boolean shouldPause() {
                return false;
        }

        @Override
        public void close() {
                cfg.save();
                if (client != null) client.setScreen(parent);
        }
}
