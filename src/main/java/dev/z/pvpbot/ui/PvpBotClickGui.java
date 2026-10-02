package dev.z.pvpbot.ui;

import dev.z.pvpbot.PvpBot;
import dev.z.pvpbot.bot.BotController;
import dev.z.pvpbot.bot.FocusMode;
import dev.z.pvpbot.ml.ModelStore;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * v2.3 — ClickGUI on RIGHT CONTROL, fully custom-drawn (no Minecraft
 * buttons). Tabs: CONTROL (big session buttons + live stats), MODELS
 * (snapshot / hot-swap / delete), SETTINGS (opens the config + HUD editor).
 * Keys 1-3 switch tabs. The bot auto-pauses while any screen is open.
 */
public final class PvpBotClickGui extends Screen {

        private static final String[] TABS = {"Control", "Models", "Settings"};

        private final Screen parent;
        private int tab = 0;
        private float tabAnim = 0f;
        private float openAnim = 0f;
        private long lastFrameMs = 0L;
        private final java.util.concurrent.CopyOnWriteArrayList<String> toast = new java.util.concurrent.CopyOnWriteArrayList<>();
        private long toastMs = 0L;

        private List<ModelStore.ModelInfo> models = new ArrayList<>();
        private int modelScroll = 0;

        /** A custom button, rebuilt every frame from the current state. */
        private record Btn(String id, int x, int y, int w, int h, String label, int color, Runnable action) {
        }

        private final List<Btn> buttons = new ArrayList<>();
        private final java.util.Map<String, Float> hover = new java.util.HashMap<>();

        public PvpBotClickGui(Screen parent) {
                super(Text.literal("PvPBot Control"));
                this.parent = parent;
        }

        @Override
        protected void init() {
                refreshModels();
        }

        private int pw() {
                return Math.min(this.width - 16, 300);
        }

        private int ph() {
                return Math.min(this.height - 16, 236);
        }

        private int px() {
                return (this.width - pw()) / 2;
        }

        private int py() {
                return (this.height - ph()) / 2;
        }

        // ================================================================ render

        @Override
        public void renderBackground(DrawContext c, int mouseX, int mouseY, float delta) {
                c.fillGradient(0, 0, this.width, this.height, 0x90060810, 0xC0080B14);
        }

        @Override
        public void render(DrawContext c, int mouseX, int mouseY, float delta) {
                long now = System.currentTimeMillis();
                if (lastFrameMs == 0L) lastFrameMs = now;
                float dt = Math.min(0.1f, (now - lastFrameMs) / 1000f);
                lastFrameMs = now;
                openAnim = Gfx.approach(openAnim, 1f, dt, 10f);
                tabAnim = Gfx.approach(tabAnim, tab, dt, 16f);

                int px = px(), py = py() + (int) ((1f - openAnim) * 10), pw = pw(), ph = ph();
                Gfx.shadow(c, px, py, pw, ph);
                Gfx.card(c, px, py, pw, ph, Gfx.BG, Gfx.BORDER);
                c.fillGradient(px + 1, py + 1, px + pw - 1, py + 3, Gfx.ACCENT, Gfx.ACCENT_2);
                Gfx.text(c, textRenderer, "PvPBot", px + 12, py + 11, 0xFFFFFFFF);
                BotController ctl = PvpBot.get().controller();
                String state = ctl.isHumanTraining() ? "HUMAN-TRAIN" : ctl.isTrainingSession() ? "TRAINING"
                                : ctl.isStarted() ? "ENGAGED" : "IDLE";
                int stCol = ctl.isStarted() ? Gfx.GOOD : Gfx.DIM;
                int sw = textRenderer.getWidth(state) + 12;
                Gfx.round(c, px + pw - 12 - sw, py + 8, sw, 14, Gfx.alpha(stCol, 0.25f));
                Gfx.textCentered(c, textRenderer, state, px + pw - 12 - sw / 2, py + 11, stCol);

                // tabs (sliding indicator)
                int tx = px + 10, ty = py + 28, tw = (pw - 20) / TABS.length;
                Gfx.round(c, tx, ty, pw - 20, 18, Gfx.SURFACE);
                Gfx.round(c, tx + (int) (tabAnim * tw) + 1, ty + 1, tw - 2, 16, Gfx.SURFACE_HOVER);
                for (int i = 0; i < TABS.length; i++) {
                        Gfx.textCentered(c, textRenderer, TABS[i], tx + i * tw + tw / 2, ty + 5,
                                        i == tab ? 0xFFFFFFFF : Gfx.MUTED);
                }

                buttons.clear();
                int cy = py + 54;
                if (tab == 0) {
                        renderControl(c, ctl, px, cy, pw);
                } else if (tab == 1) {
                        renderModels(c, px, cy, pw, mouseX, mouseY);
                } else {
                        renderSettings(c, px, cy, pw);
                }

                for (Btn b : buttons) {
                        boolean h = Gfx.in(mouseX, mouseY, b.x, b.y, b.w, b.h);
                        float hv = Gfx.approach(hover.getOrDefault(b.id, 0f), h ? 1f : 0f, dt, 16f);
                        hover.put(b.id, hv);
                        Gfx.button(c, textRenderer, b.x, b.y, b.w, b.h, b.label, b.color, hv, true);
                }

                if (!toast.isEmpty() && now - toastMs < 4000) {
                        String t = textRenderer.trimToWidth(toast.get(0), pw - 24);
                        int w = textRenderer.getWidth(t) + 16;
                        Gfx.round(c, this.width / 2 - w / 2, py + ph + 6, w, 16, 0xE0182233);
                        Gfx.textCentered(c, textRenderer, t, this.width / 2, py + ph + 10, Gfx.GOOD);
                }
        }

        private void renderControl(DrawContext c, BotController ctl, int px, int y, int pw) {
                int x = px + 12, w = pw - 24;
                boolean training = ctl.isTrainingSession(), human = ctl.isHumanTraining();
                boolean engaged = ctl.isStarted() && !training && !human;
                buttons.add(new Btn("train", x, y, w, 24, training ? "Stop training" : "Start training",
                                training ? 0xFF7A2E3C : 0xFF2F5BD1, () -> {
                                        if (ctl.isTrainingSession()) ctl.stop();
                                        else ctl.startTraining();
                                }));
                buttons.add(new Btn("human", x, y + 28, w, 24, human ? "Stop human-train" : "Start human-train",
                                human ? 0xFF7A2E3C : 0xFF27795A, () -> {
                                        if (ctl.isHumanTraining()) ctl.stop();
                                        else ctl.startHumanTrain();
                                }));
                int hw = (w - 4) / 2;
                buttons.add(new Btn("engage", x, y + 56, hw, 20, engaged ? "Disengage" : "Engage",
                                engaged ? 0xFF6E3A2A : Gfx.SURFACE_HI, ctl::toggle));
                buttons.add(new Btn("focus", x + hw + 4, y + 56, hw, 20, FocusMode.isActive() ? "Focus: on" : "Focus: off",
                                FocusMode.isActive() ? 0xFF5E4A1F : Gfx.SURFACE_HI, () -> FocusMode.toggle(this.client)));
                boolean pure = PvpBot.get().config().pureMode;
                buttons.add(new Btn("pure", x, y + 80, w, 20, pure ? "Brain: PURE v2 (click for classic v1)" : "Brain: CLASSIC v1 (click for pure v2)",
                                pure ? 0xFF4B2F86 : 0xFF23406E, () -> ctl.setPureMode(!PvpBot.get().config().pureMode)));

                int sy = y + 108;
                Gfx.round(c, x, sy, w, 52, Gfx.SURFACE);
                Gfx.text(c, textRenderer, String.format("Episodes %d   W %d  L %d  D %d", ctl.episodesDone, ctl.wins, ctl.losses, ctl.draws),
                                x + 8, sy + 6, Gfx.TEXT);
                Gfx.text(c, textRenderer, String.format("Exploration %.2f   %s", ctl.epsilon(), ctl.curriculumPhase()),
                                x + 8, sy + 18, Gfx.MUTED);
                Gfx.text(c, textRenderer, textRenderer.trimToWidth(ctl.v2StatusLine(), w - 16), x + 8, sy + 30, Gfx.MUTED);
                Gfx.text(c, textRenderer, "Right Ctrl closes · 1-3 switch tabs", x + 8, sy + 41, Gfx.DIM);
        }

        private void renderModels(DrawContext c, int px, int y, int pw, int mx, int my) {
                int x = px + 12, w = pw - 24;
                String active = PvpBot.get().activeModelName();
                Gfx.text(c, textRenderer, textRenderer.trimToWidth("Active: " + (active != null ? active : "autosave"), w), x, y, Gfx.GOOD);
                int hw = (w - 4) / 2;
                buttons.add(new Btn("snap", x, y + 12, hw, 18, "Save snapshot", 0xFF2F5BD1, this::snapshot));
                buttons.add(new Btn("refresh", x + hw + 4, y + 12, hw, 18, "Refresh", Gfx.SURFACE_HI, this::refreshModels));

                int ly = y + 36, rowH = 22, visible = 4;
                Gfx.round(c, x, ly, w, visible * rowH + 4, Gfx.SURFACE);
                if (models.isEmpty()) {
                        Gfx.textCentered(c, textRenderer, "No snapshots yet", x + w / 2, ly + 40, Gfx.DIM);
                }
                for (int i = 0; i < visible; i++) {
                        int idx = modelScroll + i;
                        if (idx >= models.size()) break;
                        ModelStore.ModelInfo m = models.get(idx);
                        int ry = ly + 2 + i * rowH;
                        boolean isActive = m.fileName.equals(active);
                        boolean h = Gfx.in(mx, my, x + 2, ry, w - 4, rowH - 2);
                        if (isActive || h) Gfx.round(c, x + 2, ry, w - 4, rowH - 2, isActive ? 0xFF1C3A2E : Gfx.SURFACE_HOVER);
                        Gfx.text(c, textRenderer, textRenderer.trimToWidth(m.fileName, w - 16), x + 8, ry + 2, isActive ? Gfx.GOOD : Gfx.TEXT);
                        Gfx.text(c, textRenderer, textRenderer.trimToWidth(m.statsLine(), w - 16), x + 8, ry + 11, Gfx.DIM);
                }
                Gfx.text(c, textRenderer, "Click = hot-swap   Shift+click = delete   Wheel = scroll", x, ly + visible * rowH + 8, Gfx.DIM);
                Gfx.text(c, textRenderer, textRenderer.trimToWidth(dev.z.pvpbot.ml.ILStore.get().statusLine(), w), x, ly + visible * rowH + 20, Gfx.MUTED);
        }

        private void renderSettings(DrawContext c, int px, int y, int pw) {
                int x = px + 12, w = pw - 24;
                buttons.add(new Btn("cfg", x, y, w, 24, "Open settings", 0xFF2F5BD1,
                                () -> this.client.setScreen(new PvpBotConfigScreen(this, 0))));
                buttons.add(new Btn("hud", x, y + 28, w, 24, "HUD layout editor", Gfx.SURFACE_HI,
                                () -> this.client.setScreen(new PvpBotHudEditScreen(this))));
                buttons.add(new Btn("il", x, y + 56, w, 20, "Load IL video sessions", 0xFF27795A, () -> {
                        final PvpBot bot = PvpBot.get();
                        toast("Scanning config/pvpbot/il/ …");
                        PvpBot.worker().execute(() -> toast(dev.z.pvpbot.ml.ILStore.get().loadAll(bot)));
                }));
                Gfx.text(c, textRenderer, "Every setting has a hover explanation.", x, y + 86, Gfx.MUTED);
                Gfx.text(c, textRenderer, "Right-click a setting to restore its default.", x, y + 98, Gfx.MUTED);
        }

        private void snapshot() {
                var ctl = PvpBot.get().controller();
                String name = (ctl.config().pureMode ? "v2-ep" : "ep") + ctl.episodesDone + "-"
                                + java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HHmmss"));
                final PvpBot bot = PvpBot.get();
                final boolean v2 = ctl.config().pureMode;
                toast("Saving snapshot…");
                PvpBot.worker().execute(() -> {
                        try {
                                String fn = v2 ? ModelStore.saveSnapshotV2(name, bot) : ModelStore.saveSnapshot(name, bot);
                                toast("Saved " + fn);
                                refreshModels();
                        } catch (Exception ex) {
                                toast("Save failed: " + ex.getMessage());
                        }
                });
        }

        // ================================================================ input

        @Override
        public boolean mouseClicked(Click click, boolean doubled) {
                double mx = click.x(), my = click.y();
                int px = px(), py = py(), pw = pw();
                int tx = px + 10, ty = py + 28, tw = (pw - 20) / TABS.length;
                if (Gfx.in(mx, my, tx, ty, pw - 20, 18)) {
                        tab = Math.max(0, Math.min(TABS.length - 1, (int) ((mx - tx) / tw)));
                        return true;
                }
                for (Btn b : new ArrayList<>(buttons)) {
                        if (Gfx.in(mx, my, b.x, b.y, b.w, b.h)) {
                                b.action.run();
                                return true;
                        }
                }
                if (tab == 1 && !models.isEmpty()) {
                        int x = px + 12, w = pw - 24, ly = py + 54 + 36, rowH = 22;
                        if (Gfx.in(mx, my, x, ly, w, 4 * rowH + 4)) {
                                int idx = modelScroll + (int) ((my - ly - 2) / rowH);
                                if (idx >= 0 && idx < models.size()) {
                                        ModelStore.ModelInfo m = models.get(idx);
                                        final PvpBot bot = PvpBot.get();
                                        if ((click.modifiers() & org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT) != 0) {
                                                PvpBot.worker().execute(() -> {
                                                        try {
                                                                ModelStore.delete(m.fileName);
                                                                refreshModels();
                                                        } catch (Exception ex) {
                                                                PvpBot.LOGGER.warn("[pvpbot] model delete failed: {}", ex.toString());
                                                        }
                                                });
                                                toast("Deleted " + m.fileName);
                                        } else {
                                                toast("Loading " + m.fileName + "…");
                                                PvpBot.worker().execute(() -> {
                                                        try {
                                                                toast("Hot-swapped: " + ModelStore.loadInto(ModelStore.dir().resolve(m.fileName), bot));
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
                if (tab == 1 && models.size() > 4) {
                        modelScroll = Math.max(0, Math.min(models.size() - 4, modelScroll - (int) Math.signum(vertical)));
                        return true;
                }
                return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
        }

        @Override
        public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
                int k = input.key();
                if (k >= org.lwjgl.glfw.GLFW.GLFW_KEY_1 && k <= org.lwjgl.glfw.GLFW.GLFW_KEY_3) {
                        tab = k - org.lwjgl.glfw.GLFW.GLFW_KEY_1;
                        return true;
                }
                if (k == org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_CONTROL) {
                        close();
                        return true;
                }
                return super.keyPressed(input);
        }

        private void refreshModels() {
                List<ModelStore.ModelInfo> m = ModelStore.list();
                models = m;
                modelScroll = Math.max(0, Math.min(modelScroll, Math.max(0, m.size() - 4)));
        }

        private void toast(String msg) {
                toast.clear();
                toast.add(msg);
                toastMs = System.currentTimeMillis();
        }

        @Override
        public boolean shouldPause() {
                return false;
        }

        @Override
        public void close() {
                PvpBot.get().config().save();
                if (client != null) client.setScreen(parent);
        }
}
