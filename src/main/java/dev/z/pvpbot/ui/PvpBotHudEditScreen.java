package dev.z.pvpbot.ui;

import dev.z.pvpbot.BotConfig;
import dev.z.pvpbot.PvpBot;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * v1.0.11 HUD LAYOUT EDITOR (user: "add a HUD customizer to edit the Position
 * and Size of each huds").
 *
 * Renders the whole HUD in preview mode (every element visible, sample data)
 * and lets the user:
 *   - DRAG any element anywhere (anchor auto-picks the nearest corner or
 *     center line so the layout survives resolution changes),
 *   - MOUSE-SCROLL on an element to resize it (0.5x - 2.0x),
 *   - Reset All (back to defaults) / Done (save + close).
 *
 * Open via /pvpbot hud (or hud edit) or the button in the built-in config
 * screen. The bot auto-pauses while any screen is open, so editing is safe.
 */
public final class PvpBotHudEditScreen extends Screen {

        private final Screen parent;
        private final BotConfig cfg;
        private String dragging = null;
        private int grabDx, grabDy;
        private final List<String> toast = new ArrayList<>();

        public PvpBotHudEditScreen(Screen parent) {
                super(Text.literal("PvPBot HUD Layout Editor"));
                this.parent = parent;
                this.cfg = PvpBot.get().config();
        }

        @Override
        protected void init() {
                int cx = this.width / 2;
                addDrawableChild(ButtonWidget.builder(Text.literal("Reset All"),
                                b -> {
                                        cfg.resetHud(null);
                                        toast("layout reset to defaults");
                                }).dimensions(cx - 155, this.height - 28, 100, 20).build());
                addDrawableChild(ButtonWidget.builder(Text.literal("Done"), b -> close())
                                .dimensions(cx + 55, this.height - 28, 100, 20).build());
        }

        private void toast(String msg) {
                toast.clear();
                toast.add(msg);
        }

        @Override
        public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
                // dim the world a little so the preview pops
                ctx.fill(0, 0, this.width, this.height, 0x55000000);

                // full HUD preview — also refreshes hud().elementRects
                PvpBot.get().hud().render(ctx, this.client, true);
                var rects = PvpBot.get().hud().elementRects;

                var tr = this.textRenderer;
                for (var en : rects.entrySet()) {
                        BotHud.Rect r = en.getValue();
                        boolean hot = en.getKey().equals(dragging)
                                        || contains(r, mouseX, mouseY);
                        int col = hot ? 0xFF00FF00 : 0xFF44AA44;
                        outline(ctx, r, col);
                        ctx.drawText(tr, Text.literal(en.getKey() + String.format("  %dx%d  %.2fx",
                                        r.w, r.h, cfg.el(en.getKey()).scale)),
                                        r.x, Math.max(2, r.y - 11), 0xFFFFFF55, true);
                }

                ctx.drawCenteredTextWithShadow(tr, Text.literal("PvPBot HUD Layout Editor"),
                                this.width / 2, 6, 0xFFFFFF55);
                ctx.drawCenteredTextWithShadow(tr, Text.literal(
                                "Drag an element to move it · scroll on it to resize · anchors auto-snap"),
                                this.width / 2, 18, 0xFFCCCCCC);
                for (int i = 0; i < toast.size(); i++) {
                        ctx.drawCenteredTextWithShadow(tr, Text.literal(toast.get(i)),
                                        this.width / 2, 32 + i * 10, 0xFF55FF55);
                }
                super.render(ctx, mouseX, mouseY, delta);
        }

        private static boolean contains(BotHud.Rect r, int x, int y) {
                return x >= r.x && x < r.x + r.w && y >= r.y && y < r.y + r.h;
        }

        private void outline(DrawContext ctx, BotHud.Rect r, int col) {
                int e = 1;
                ctx.fill(r.x - e, r.y - e, r.x + r.w + e, r.y, col);
                ctx.fill(r.x - e, r.y + r.h, r.x + r.w + e, r.y + r.h + e, col);
                ctx.fill(r.x - e, r.y, r.x, r.y + r.h, col);
                ctx.fill(r.x + r.w, r.y, r.x + r.w + e, r.y + r.h, col);
        }

        @Override
        public boolean mouseClicked(Click click, boolean doubled) {
                if (click.button() == 0) {
                        var rects = PvpBot.get().hud().elementRects;
                        // topmost first (map order == draw order)
                        List<String> ids = new ArrayList<>(rects.keySet());
                        for (int i = ids.size() - 1; i >= 0; i--) {
                                String id = ids.get(i);
                                BotHud.Rect r = rects.get(id);
                                if (contains(r, (int) click.x(), (int) click.y())) {
                                        dragging = id;
                                        grabDx = (int) click.x() - r.x;
                                        grabDy = (int) click.y() - r.y;
                                        toast("moving " + id);
                                        return true;
                                }
                        }
                }
                return super.mouseClicked(click, doubled);
        }

        @Override
        public boolean mouseDragged(Click click, double dragDx, double dragDy) {
                if (dragging != null) {
                        moveElement(dragging, (int) click.x(), (int) click.y());
                        return true;
                }
                return super.mouseDragged(click, dragDx, dragDy);
        }

        @Override
        public boolean mouseReleased(Click click) {
                if (dragging != null) {
                        dragging = null;
                        cfg.save();
                        toast("position saved");
                }
                return super.mouseReleased(click);
        }

        @Override
        public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
                var rects = PvpBot.get().hud().elementRects;
                List<String> ids = new ArrayList<>(rects.keySet());
                for (int i = ids.size() - 1; i >= 0; i--) {
                        String id = ids.get(i);
                        if (contains(rects.get(id), (int) mouseX, (int) mouseY)) {
                                BotConfig.HudElement e = cfg.el(id);
                                e.scale = MathHelper_clamp(e.scale + (float) vertical * 0.05f, 0.5f, 2f);
                                toast(id + " size " + String.format("%.2fx", e.scale));
                                return true;
                        }
                }
                return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
        }

        /** Drag math: keep the grab point under the cursor and re-pick the anchor. */
        private void moveElement(String id, int mouseX, int mouseY) {
                var rects = PvpBot.get().hud().elementRects;
                BotHud.Rect r = rects.get(id);
                if (r == null) return;
                BotConfig.HudElement e = cfg.el(id);
                int w = r.w, h = r.h; // scaled box size (recomputed from old rect; scale unchanged while dragging)
                int left = mouseX - grabDx;
                int top = mouseY - grabDy;
                int sw = this.width, sh = this.height;
                // keep the element mostly on screen
                left = MathHelper_clamp(left, -w / 2, sw - w / 2);
                top = MathHelper_clamp(top, -h / 2, sh - h / 2);
                int cx = left + w / 2, cy = top + h / 2;

                boolean centered = e.anchor >= 4;
                if (centered && Math.abs(cx - sw / 2) > sw / 4) centered = false;
                else if (!centered && Math.abs(cx - sw / 2) < sw / 8) centered = true;

                if (centered) {
                        e.ox = left - (sw - w) / 2;
                        if (cy < sh / 3) {
                                e.anchor = 4;
                                e.oy = top;
                        } else if (cy < 2 * sh / 3) {
                                e.anchor = 6;
                                e.oy = cy - sh / 2;
                        } else {
                                e.anchor = 5;
                                e.oy = top - sh;
                        }
                } else {
                        boolean leftHalf = cx < sw / 2;
                        e.anchor = cy < sh / 2 ? (leftHalf ? 0 : 1) : (leftHalf ? 2 : 3);
                        e.ox = leftHalf ? left : left - sw;
                        e.oy = cy < sh / 2 ? top : top - sh;
                }
        }

        private static float MathHelper_clamp(float v, float min, float max) {
                return v < min ? min : Math.min(v, max);
        }

        private static int MathHelper_clamp(int v, int min, int max) {
                return v < min ? min : Math.min(v, max);
        }

        @Override
        public void close() {
                cfg.save();
                if (client != null) client.setScreen(parent);
        }
}
