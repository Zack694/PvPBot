package dev.z.pvpbot.ui;

import dev.z.pvpbot.BotConfig;
import dev.z.pvpbot.PvpBot;
import dev.z.pvpbot.bot.BotController;
import dev.z.pvpbot.bot.FocusMode;
import dev.z.pvpbot.ml.Dqn;
import dev.z.pvpbot.ml.ModelStore;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * v2.0 PHASE 1 — CLICKGUI on RIGHT CONTROL (user: "a ClickGui on Right
 * Control where I can open configs, Model Presets in future, Train/Stop and
 * Human-Train/Stop as big buttons").
 *
 * Four tabs across the top of a panel:
 *   HOME    — the big training buttons: START/STOP TRAINING (auto re-engage
 *             episode loop), HUMAN-TRAIN/STOP (imitation from your play),
 *             ENGAGE/DISENGAGE (normal takeover), plus a live status line.
 *   CONFIG  — opens the full config screen (every setting + tooltips).
 *   MODELS  — the brain the mod currently runs + swap controls (fills up in
 *             Phase 2 with the models/ folder + picker).
 *   PRESETS — reserved slot for saveable configuration bundles (Phase 3+).
 *
 * The bot auto-pauses while any screen is open, so mashing buttons mid-fight
 * is safe: keys release, the round state stays intact.
 */
public final class PvpBotClickGui extends Screen {

        private static final int PANEL_W = 260;
        private static final int PANEL_H = 186;
        private static final String[] TABS = {"HOME", "CONFIG", "MODELS", "PRESETS"};

        private final Screen parent;
        private int tab = 0;
        // v2.2.1: copy-on-write — worker threads write toasts while the render
        // thread iterates (a plain ArrayList risked ConcurrentModificationException
        // on every IL/snapshot button).
        private final java.util.concurrent.CopyOnWriteArrayList<String> toast =
                        new java.util.concurrent.CopyOnWriteArrayList<>();

        // v2.0 PHASE 2-a — model picker state (MODELS tab)
        private List<ModelStore.ModelInfo> models = new ArrayList<>();
        private int modelScroll = 0;
        private static final int VISIBLE_MODELS = 4;
        private static final int ROW_H = 15;

        public PvpBotClickGui(Screen parent) {
                super(Text.literal("PvPBot Control"));
                this.parent = parent;
        }

        @Override
        protected void init() {
                layoutButtons();
        }

        private void layoutButtons() {
                clearChildren();
                int px = (this.width - PANEL_W) / 2;
                int py = Math.max(30, (this.height - PANEL_H) / 2 - 10);

                if (tab == 0) {
                        BotController ctl = PvpBot.get().controller();
                        boolean training = ctl.isTrainingSession();
                        boolean human = ctl.isHumanTraining();
                        boolean engaged = ctl.isStarted() && !training && !human;
                        addDrawableChild(ButtonWidget.builder(
                                        Text.literal(training ? "STOP TRAINING" : "START TRAINING"),
                                        b -> {
                                                if (ctl.isTrainingSession()) ctl.stop();
                                                else ctl.startTraining();
                                                layoutButtons();
                                        })
                                .dimensions(px + 14, py + 30, 232, 30).build());
                        addDrawableChild(ButtonWidget.builder(
                                        Text.literal(human ? "STOP HUMAN-TRAIN" : "START HUMAN-TRAIN"),
                                        b -> {
                                                if (ctl.isHumanTraining()) ctl.stop();
                                                else ctl.startHumanTrain();
                                                layoutButtons();
                                        })
                                .dimensions(px + 14, py + 66, 232, 30).build());
                        addDrawableChild(ButtonWidget.builder(
                                        Text.literal(engaged ? "DISENGAGE BOT" : "ENGAGE BOT"),
                                        b -> {
                                                ctl.toggle();
                                                layoutButtons();
                                        })
                                .dimensions(px + 14, py + 102, 112, 20).build());
                        addDrawableChild(ButtonWidget.builder(
                                        Text.literal(FocusMode.isActive() ? "FOCUS: ON" : "FOCUS: OFF"),
                                        b -> {
                                                FocusMode.toggle(this.client);
                                                layoutButtons();
                                        })
                                .dimensions(px + 134, py + 102, 112, 20).build());
                        // v2.0 PHASE 2-b — PURE MODE: the four-head brain is the only
                        // authority (movement/sprint/jump/sneak/aim/click). The v1.0.12
                        // physics gates (band, governor, on-target) ALWAYS stay.
                        addDrawableChild(ButtonWidget.builder(
                                        Text.literal(PvpBot.get().config().pureMode ? "PURE MODE: ON" : "PURE MODE: OFF"),
                                        b -> {
                                                ctl.setPureMode(!PvpBot.get().config().pureMode);
                                                layoutButtons();
                                        })
                                .dimensions(px + 14, py + 126, 232, 20).build());
                } else if (tab == 1) {
                        addDrawableChild(ButtonWidget.builder(
                                        Text.literal("Open Full Config (all settings + tooltips)"),
                                        b -> {
                                                this.client.setScreen(new PvpBotConfigScreen(this, 0));
                                        })
                                .dimensions(px + 14, py + 30, 232, 20).build());
                        addDrawableChild(ButtonWidget.builder(
                                        Text.literal("Open HUD Layout Editor"),
                                        b -> {
                                                this.client.setScreen(new PvpBotHudEditScreen(this));
                                        })
                                .dimensions(px + 14, py + 56, 232, 20).build());
                } else if (tab == 2) {
                        // v2.0 PHASE 2-a — MODEL PICKER: save a snapshot of the live
                        // brain + manage the models/ folder (click = hot-swap,
                        // shift-click = delete)
                        addDrawableChild(ButtonWidget.builder(
                                        Text.literal("SAVE SNAPSHOT of current brain"),
                                        b -> {
                                                var c = PvpBot.get().controller();
                                                String name = (c.config().pureMode ? "v2-ep" : "ep") + c.episodesDone + "-"
                                                                + java.time.LocalTime.now()
                                                                                .format(java.time.format.DateTimeFormatter.ofPattern("HHmmss"));
                                                final PvpBot botRef = PvpBot.get();
                                                final boolean v2 = c.config().pureMode;
                                                PvpBot.worker().execute(() -> {
                                                        try {
                                                                String fn = v2 ? ModelStore.saveSnapshotV2(name, botRef)
                                                                                : ModelStore.saveSnapshot(name, botRef);
                                                                toast("Saved " + fn);
                                                        } catch (Exception ex) {
                                                                toast("Save failed: " + ex.getMessage());
                                                        }
                                                });
                                                toast("Saving snapshot…");
                                        })
                                .dimensions(px + 14, py + 34, 232, 20).build());
                        addDrawableChild(ButtonWidget.builder(
                                        Text.literal("REFRESH"),
                                        b -> {
                                                refreshModels();
                                        })
                                .dimensions(px + 14, py + 56, 112, 16).build());
                        addDrawableChild(ButtonWidget.builder(
                                        Text.literal("LOAD: click a model below"),
                                        b -> {
                                                refreshModels();
                                                toast("Click a model row to hot-swap it in.");
                                        })
                                .dimensions(px + 134, py + 56, 112, 16).build());
                        // v2.2.0 — IL (imitation-from-video) controls live here too:
                        // load the extractor's sessions into both brains + train bursts.
                        addDrawableChild(ButtonWidget.builder(
                                        Text.literal("IL: LOAD SESSIONS"),
                                        b -> {
                                                final PvpBot botRef = PvpBot.get();
                                                toast("Scanning config/pvpbot/il/ …");
                                                PvpBot.worker().execute(() -> toast(dev.z.pvpbot.ml.ILStore.get().loadAll(botRef)));
                                        })
                                .dimensions(px + 14, py + 76, 112, 16).build());
                        addDrawableChild(ButtonWidget.builder(
                                        Text.literal("IL: TRAIN 60"),
                                        b -> {
                                                dev.z.pvpbot.ml.ILStore il = dev.z.pvpbot.ml.ILStore.get();
                                                if (!il.isLoaded()) {
                                                        toast("IL not loaded — click IL: LOAD SESSIONS first");
                                                } else {
                                                        il.train(PvpBot.get(), 60);
                                                        toast("IL training queued (60 bursts)");
                                                }
                                        })
                                .dimensions(px + 134, py + 76, 112, 16).build());
                        refreshModels();
                }
        }

        @Override
        public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
                ctx.fill(0, 0, this.width, this.height, 0x88000000);
                int px = (this.width - PANEL_W) / 2;
                int py = Math.max(30, (this.height - PANEL_H) / 2 - 10);
                var tr = this.textRenderer;

                // panel
                ctx.fill(px, py, px + PANEL_W, py + PANEL_H, 0xF0101018);
                ctx.fill(px, py, px + PANEL_W, py + 1, 0xFF3D7BFF);
                ctx.fill(px, py + 1, px + PANEL_W, py + 16, 0xFF16203A);

                // tabs
                int tabW = PANEL_W / TABS.length;
                for (int i = 0; i < TABS.length; i++) {
                        int tx = px + i * tabW;
                        boolean sel = i == tab;
                        ctx.fill(tx + 1, py + 17, tx + tabW - 1, py + 31, sel ? 0xFF2C4E8E : 0xFF101B33);
                        ctx.drawCenteredTextWithShadow(tr, Text.literal(TABS[i]),
                                        tx + tabW / 2, py + 21, sel ? 0xFFFFFF : 0xFF8899BB);
                }

                // tab content text
                BotController ctl = PvpBot.get().controller();
                int cy = py + 38;
                if (tab == 0) {
                        String status;
                        if (ctl.isHumanTraining()) status = "HUMAN-TRAIN: your fights are recorded as expert demos";
                        else if (ctl.isTrainingSession()) status = "TRAINING: auto re-engage, brain updates every round";
                        else if (ctl.isStarted()) status = "ENGAGED: fighting";
                        else status = "IDLE";
                        ctx.drawText(tr, Text.literal(status), px + 14, py + 128, 0xFF9FE870, true);
                        Dqn dqn = PvpBot.get().dqn();
                        String line2 = String.format("eps %d  W/L/D %d/%d/%d  replay %d/%d  demos %d",
                                        ctl.episodesDone, ctl.wins, ctl.losses, ctl.draws,
                                        dqn.bufferSize(), dqn.bufferCapacity(), dqn.expertSize());
                        ctx.drawText(tr, Text.literal(line2), px + 14, py + 140, 0xFFAABBDD, true);
                        ctx.drawText(tr, Text.literal("curriculum " + ctl.curriculumPhase()
                                        + String.format("  eps-greedy %.2f", ctl.epsilon())),
                                        px + 14, py + 152, 0xFF8899BB, true);
                } else if (tab == 1) {
                        ctx.drawText(tr, Text.literal("Every slider, chance and timing lives in the"),
                                        px + 14, py + 84, 0xFFAABBDD, true);
                        ctx.drawText(tr, Text.literal("full config screen — hover anything for help."),
                                        px + 14, py + 96, 0xFFAABBDD, true);
                } else if (tab == 2) {
                        Dqn dqn = PvpBot.get().dqn();
                        String active = PvpBot.get().activeModelName();
                        ctx.drawText(tr, Text.literal("Active brain: " + (active != null ? active : "autosave (model/policy.json)")),
                                        px + 14, py + 40, 0xFF9FE870, true);
                        ctx.drawText(tr, Text.literal("arch " + dqn.qArchSummary() + "   steps " + dqn.getTrainSteps()),
                                        px + 14, py + 52, 0xFFAABBDD, true);
                        // v2.2.0: live IL pipeline status
                        ctx.drawText(tr, Text.literal(dev.z.pvpbot.ml.ILStore.get().statusLine()),
                                        px + 14, py + 68, 0xFFBBD4FF, true);
                        // model rows (4 visible, scroll with the wheel)
                        int listY = py + 96;
                        ctx.fill(px + 12, listY - 2, px + PANEL_W - 12, listY + VISIBLE_MODELS * ROW_H + 2, 0xFF060A14);
                        if (models.isEmpty()) {
                                ctx.drawText(tr, Text.literal("models/ is empty — save a snapshot first"),
                                                px + 16, listY + 20, 0xFF667799, true);
                        }
                        for (int i = 0; i < VISIBLE_MODELS; i++) {
                                int idx = modelScroll + i;
                                if (idx >= models.size()) break;
                                ModelStore.ModelInfo m = models.get(idx);
                                boolean isActive = m.fileName.equals(active);
                                int rowY = listY + i * ROW_H;
                                if (isActive) {
                                        ctx.fill(px + 13, rowY, px + PANEL_W - 13, rowY + ROW_H - 1, 0xFF1B3320);
                                }
                                ctx.drawText(tr, Text.literal(m.fileName), px + 16, rowY + 1,
                                                isActive ? 0xFF7DFFA0 : 0xFFCCDDEE, true);
                                ctx.drawText(tr, Text.literal(m.statsLine()), px + 16, rowY + 8,
                                                0xFF7788AA, true);
                        }
                        ctx.drawText(tr, Text.literal("CLICK = hot-swap (replay + demos kept)   SHIFT+CLICK = delete"),
                                        px + 14, py + 174, 0xFFFFDD77, true);
                } else {
                        ctx.drawText(tr, Text.literal("PRESETS — saveable config bundles per model."),
                                        px + 14, py + 60, 0xFFFFDD77, true);
                        ctx.drawText(tr, Text.literal("Reserved slot: the picker lands first (Phase 2),"),
                                        px + 14, py + 72, 0xFFAABBDD, true);
                        ctx.drawText(tr, Text.literal("then presets bind a config + a brain together."),
                                        px + 14, py + 84, 0xFFAABBDD, true);
                }

                // title
                ctx.drawCenteredTextWithShadow(tr, Text.literal("PvPBot Control"),
                                this.width / 2, py - 12, 0xFF55AAFF);

                for (int i = 0; i < toast.size(); i++) {
                        ctx.drawCenteredTextWithShadow(tr, Text.literal(toast.get(i)),
                                        this.width / 2, py + PANEL_H + 8 + i * 10, 0xFF55FF55);
                }
                super.render(ctx, mouseX, mouseY, delta);
        }

        @Override
        public boolean mouseClicked(Click click, boolean doubled) {
                int px = (this.width - PANEL_W) / 2;
                int py = Math.max(30, (this.height - PANEL_H) / 2 - 10);
                int mx = (int) click.x(), my = (int) click.y();
                int tabW = PANEL_W / TABS.length;
                if (my >= py + 17 && my < py + 31) {
                        for (int i = 0; i < TABS.length; i++) {
                                if (mx >= px + i * tabW + 1 && mx < px + (i + 1) * tabW - 1) {
                                        if (tab != i) {
                                                tab = i;
                                                toast.clear();
                                                layoutButtons();
                                        }
                                        return true;
                                }
                        }
                }
                // v2.0 PHASE 2-a — model row clicks (LOAD / shift+DELETE)
                if (tab == 2 && !models.isEmpty()) {
                        int listY = py + 96;
                        if (mx >= px + 12 && mx <= px + PANEL_W - 12
                                        && my >= listY && my < listY + VISIBLE_MODELS * ROW_H) {
                                int idx = modelScroll + (my - listY) / ROW_H;
                                if (idx >= 0 && idx < models.size()) {
                                        ModelStore.ModelInfo m = models.get(idx);
                                        final PvpBot botRef = PvpBot.get();
                                        if ((click.modifiers() & org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT) != 0) {
                                                PvpBot.worker().execute(() -> {
                                                        try {
                                                                ModelStore.delete(m.fileName);
                                                        } catch (Exception ex) {
                                                                LOGGER_DELETE_WARN(ex);
                                                        }
                                                });
                                                toast("Deleted " + m.fileName);
                                                refreshModels();
                                        } else {
                                                toast("Loading " + m.fileName + "…");
                                                PvpBot.worker().execute(() -> {
                                                        try {
                                                                String d = ModelStore.loadInto(
                                                                                ModelStore.dir().resolve(m.fileName), botRef);
                                                                toast("Hot-swapped: " + d);
                                                        } catch (Exception ex) {
                                                                toast("Load failed: " + ex.getMessage());
                                                        }
                                                });
                                        }
                                        return true;
                                }
                        }
                }
                return super.mouseClicked(click, doubled);
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
                if (tab == 2 && models.size() > VISIBLE_MODELS) {
                        modelScroll = Math.max(0, Math.min(models.size() - VISIBLE_MODELS,
                                        modelScroll - (int) Math.signum(vertical)));
                        return true;
                }
                return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
        }

        private void refreshModels() {
                models = ModelStore.list();
                if (modelScroll > Math.max(0, models.size() - VISIBLE_MODELS)) {
                        modelScroll = Math.max(0, models.size() - VISIBLE_MODELS);
                }
        }

        private static void LOGGER_DELETE_WARN(Exception ex) {
                dev.z.pvpbot.PvpBot.LOGGER.warn("[pvpbot] model delete failed: {}", ex.toString());
        }

        private void toast(String msg) {
                toast.clear();
                toast.add(msg);
        }

        @Override
        public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
                // the tabs are mouse-only; make 1-4 switch them from the keyboard too
                int keyCode = input.key();
                if (keyCode >= org.lwjgl.glfw.GLFW.GLFW_KEY_1
                                && keyCode <= org.lwjgl.glfw.GLFW.GLFW_KEY_4) {
                        int t = keyCode - org.lwjgl.glfw.GLFW.GLFW_KEY_1;
                        if (t != tab) {
                                tab = t;
                                toast.clear();
                                layoutButtons();
                        }
                        return true;
                }
                return super.keyPressed(input);
        }

        @Override
        public boolean shouldCloseOnEsc() {
                return true;
        }

        @Override
        public void close() {
                PvpBot.get().config().save();
                if (client != null) client.setScreen(parent);
        }
}
