package dev.z.pvpbot.ui;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.List;

/**
 * v2.3 — the PvPBot UI kit. Every PvPBot screen is drawn with these
 * primitives (no vanilla button chrome): rounded panels, animated switches,
 * sliders, buttons, tabs and themed tooltips. Colors are ARGB.
 */
public final class Gfx {

        // ---- palette (dark slate + electric blue / violet accents)
        public static final int SCRIM = 0xA0050709;
        public static final int BG = 0xF2101420;
        public static final int SURFACE = 0xFF161B29;
        public static final int SURFACE_HI = 0xFF1D2436;
        public static final int SURFACE_HOVER = 0xFF232C42;
        public static final int BORDER = 0xFF2A3350;
        public static final int ACCENT = 0xFF5B8CFF;
        public static final int ACCENT_2 = 0xFF9B6BFF;
        public static final int TEXT = 0xFFE9EDF7;
        public static final int MUTED = 0xFF8D97B0;
        public static final int DIM = 0xFF5D6782;
        public static final int GOOD = 0xFF3FD69A;
        public static final int BAD = 0xFFFF6B7A;
        public static final int WARN = 0xFFFFC65C;
        public static final int TRACK = 0xFF262E44;

        private Gfx() {
        }

        /** Rounded rectangle (radius 2, drawn from 5 fills). */
        public static void round(DrawContext c, int x, int y, int w, int h, int col) {
                if (w <= 0 || h <= 0) return;
                if (w < 4 || h < 4) {
                        c.fill(x, y, x + w, y + h, col);
                        return;
                }
                c.fill(x + 2, y, x + w - 2, y + 1, col);
                c.fill(x + 1, y + 1, x + w - 1, y + 2, col);
                c.fill(x, y + 2, x + w, y + h - 2, col);
                c.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, col);
                c.fill(x + 2, y + h - 1, x + w - 2, y + h, col);
        }

        /** Rounded rectangle with a 1px border. */
        public static void card(DrawContext c, int x, int y, int w, int h, int fill, int border) {
                round(c, x, y, w, h, border);
                round(c, x + 1, y + 1, w - 2, h - 2, fill);
        }

        /** Soft drop shadow under a panel. */
        public static void shadow(DrawContext c, int x, int y, int w, int h) {
                for (int i = 1; i <= 4; i++) {
                        int a = 0x18 - i * 4;
                        round(c, x - i, y - i + 2, w + 2 * i, h + 2 * i, a << 24);
                }
        }

        public static int lerpColor(int a, int b, float t) {
                t = Math.max(0f, Math.min(1f, t));
                int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
                int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
                return ((int) (aa + (ba - aa) * t) << 24) | ((int) (ar + (br - ar) * t) << 16)
                                | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
        }

        public static int alpha(int col, float a) {
                int base = col >>> 24;
                return ((int) (base * Math.max(0f, Math.min(1f, a))) << 24) | (col & 0xFFFFFF);
        }

        /** Frame-rate independent exponential approach. */
        public static float approach(float cur, float target, float dtSec, float speed) {
                float k = 1f - (float) Math.exp(-dtSec * speed);
                float v = cur + (target - cur) * k;
                return Math.abs(target - v) < 0.002f ? target : v;
        }

        public static boolean in(double mx, double my, int x, int y, int w, int h) {
                return mx >= x && mx < x + w && my >= y && my < y + h;
        }

        public static void text(DrawContext c, TextRenderer tr, String s, int x, int y, int col) {
                c.drawText(tr, s, x, y, col, false);
        }

        public static void textShadow(DrawContext c, TextRenderer tr, String s, int x, int y, int col) {
                c.drawText(tr, s, x, y, col, true);
        }

        public static void textCentered(DrawContext c, TextRenderer tr, String s, int cx, int y, int col) {
                c.drawText(tr, s, cx - tr.getWidth(s) / 2, y, col, false);
        }

        public static void textRight(DrawContext c, TextRenderer tr, String s, int rx, int y, int col) {
                c.drawText(tr, s, rx - tr.getWidth(s), y, col, false);
        }

        /** Animated pill switch (26 x 12). {@code knob} 0 = off .. 1 = on. */
        public static void toggle(DrawContext c, int x, int y, float knob, boolean hovered) {
                int track = lerpColor(TRACK, GOOD, knob);
                if (hovered) track = lerpColor(track, 0xFFFFFFFF, 0.08f);
                round(c, x, y, 26, 12, track);
                int kx = x + 1 + (int) (14 * knob);
                round(c, kx, y + 1, 11, 10, 0xFFF4F7FC);
        }

        /** Slider track with fill + grip. {@code n} 0..1. */
        public static void slider(DrawContext c, int x, int y, int w, float n, boolean active, boolean hovered) {
                n = Math.max(0f, Math.min(1f, n));
                round(c, x, y, w, 4, TRACK);
                int fw = (int) ((w - 2) * n);
                if (fw > 0) round(c, x, y, Math.max(4, fw + 2), 4, active ? ACCENT_2 : ACCENT);
                int gx = x + (int) ((w - 8) * n);
                int grip = active ? 0xFFFFFFFF : hovered ? 0xFFF0F4FF : 0xFFDDE4F2;
                round(c, gx, y - 3, 8, 10, grip);
        }

        /** Flat button. {@code hover} 0..1 animated. */
        public static void button(DrawContext c, TextRenderer tr, int x, int y, int w, int h, String label,
                                  int base, float hover, boolean enabled) {
                int fill = enabled ? lerpColor(base, lerpColor(base, 0xFFFFFFFF, 0.18f), hover) : SURFACE;
                round(c, x, y, w, h, fill);
                if (hover > 0.01f && enabled) {
                        c.fill(x + 2, y + h - 1, x + w - 2, y + h, alpha(0xFFFFFFFF, 0.25f * hover));
                }
                int col = enabled ? TEXT : DIM;
                textCentered(c, tr, label, x + w / 2, y + (h - 8) / 2, col);
        }

        /** Word-wrap plain text to a pixel width (honors explicit newlines). */
        public static List<String> wrap(TextRenderer tr, String s, int maxW) {
                List<String> out = new ArrayList<>();
                for (String para : s.split("\n")) {
                        StringBuilder line = new StringBuilder();
                        for (String word : para.split(" ")) {
                                String cand = line.length() == 0 ? word : line + " " + word;
                                if (tr.getWidth(cand) > maxW && line.length() > 0) {
                                        out.add(line.toString());
                                        line = new StringBuilder(word);
                                } else {
                                        line = new StringBuilder(cand);
                                }
                        }
                        out.add(line.toString());
                }
                return out;
        }

        /** Themed tooltip box. Call after {@code createNewRootLayer()} so it sits on top. */
        public static void tooltip(DrawContext c, TextRenderer tr, String title, String body, int mx, int my,
                                   int screenW, int screenH) {
                int maxW = Math.min(260, screenW - 24);
                List<String> lines = wrap(tr, body, maxW);
                int w = Math.max(tr.getWidth(title), 0);
                for (String l : lines) w = Math.max(w, tr.getWidth(l));
                w += 14;
                int h = 12 + (title.isEmpty() ? 0 : 12) + lines.size() * 10 + 2;
                int x = mx + 12, y = my + 10;
                if (x + w > screenW - 4) x = mx - w - 8;
                if (y + h > screenH - 4) y = screenH - h - 4;
                if (x < 4) x = 4;
                if (y < 4) y = 4;
                shadow(c, x, y, w, h);
                card(c, x, y, w, h, 0xF7141927, ACCENT);
                int ty = y + 6;
                if (!title.isEmpty()) {
                        text(c, tr, title, x + 7, ty, 0xFFFFFFFF);
                        ty += 12;
                }
                for (String l : lines) {
                        text(c, tr, l, x + 7, ty, 0xFFC9D2E6);
                        ty += 10;
                }
        }
}
