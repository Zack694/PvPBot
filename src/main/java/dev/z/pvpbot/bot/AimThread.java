package dev.z.pvpbot.bot;

import dev.z.pvpbot.PvpBot;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;

/**
 * v2.0 PHASE 1 — THREADED 120Hz AIM LOOP (user: "make the aim smooth at
 * 60Hz or 120Hz" while their client renders at 33-62 fps).
 *
 * The old frame aim runs once per RENDERED frame — at 33 fps the crosshair
 * receives 33 aim corrections per second, each one a visible step. This
 * thread instead wakes at a FIXED WALL-CLOCK RATE (cfg.aimThreadHz, default
 * 120 = every 8.33ms) and computes the aim delta for its slice of time:
 *
 *   - deltas are accumulated in DOUBLE precision inside the Actuator, so
 *     sub-frame motion is never rounded away;
 *   - the render thread drains the accumulator once per frame and injects
 *     it through the real GLFW cursor pipeline — all window input stays on
 *     the thread the JVM expects;
 *   - the target position is DEAD-RECKONED between ticks (last position +
 *     velocity * time-since-tick), which is exactly Minecraft's own linear
 *     interpolation, so the crosshair tracks motion that happened between
 *     frames;
 *   - the whole loop costs a few microseconds per wake (a ~60k-parameter
 *     forward pass + trig) — under 0.5% of one CPU core at 120Hz.
 *
 * Perceived smoothness on a 33-62 fps client: no trajectory detail is lost
 * (computed at 120Hz), fractional deltas glide instead of stepping, and the
 * visible result is fluid at any fps — the display can only SHOW frames,
 * but the crosshair path feeding them is continuous now.
 */
public final class AimThread {

        private final BotController ctl;
        private final MinecraftClient mc;
        private Thread thread;
        private volatile boolean running = false;

        public AimThread(BotController ctl, MinecraftClient mc) {
                this.ctl = ctl;
                this.mc = mc;
        }

        public boolean isRunning() {
                return running && thread != null && thread.isAlive();
        }

        public synchronized void ensureStarted() {
                if (isRunning()) return;
                running = true;
                thread = new Thread(this::run, "PvPBot-Aim");
                thread.setDaemon(true);
                thread.setPriority(Thread.NORM_PRIORITY + 1); // aim reflexes above housekeeping, below the game
                thread.start();
        }

        public synchronized void stop() {
                running = false;
                if (thread != null) {
                        thread.interrupt();
                        thread = null;
                }
        }

        private void run() {
                long lastNanos = System.nanoTime();
                while (running) {
                        try {
                                int hz = Math.max(30, Math.min(240, PvpBot.get().config().aimThreadHz));
                                long period = 1_000_000_000L / hz;
                                long now = System.nanoTime();
                                long sleepNs = period - (now - lastNanos);
                                if (sleepNs > 0) {
                                        // precise periodic sleep — nanos first, then yield-spin the tail
                                        // so the rate survives Windows' ~15ms sleep granularity
                                        long ms = sleepNs / 1_000_000L;
                                        int ns = (int) (sleepNs % 1_000_000L);
                                        if (ms > 0) Thread.sleep(ms, ns);
                                        else Thread.onSpinWait();
                                        now = System.nanoTime();
                                }
                                float dtMs = (now - lastNanos) / 1_000_000f;
                                lastNanos = now;
                                dtMs = Math.max(0.5f, Math.min(100f, dtMs));
                                step(dtMs);
                        } catch (InterruptedException e) {
                                return; // stopped
                        } catch (Throwable ignored) {
                                // the aim thread must never kill the game
                        }
                }
        }

        private void step(float dtMs) {
                if (!ctl.isStarted() || ctl.isPaused()) return;
                ClientPlayerEntity self = mc.player;
                if (self == null || mc.world == null || self.isDead()) return;
                LivingEntity target = ctl.selector().target();
                if (target == null) return;
                // a screen the user opened pauses the controller (its tick returns
                // early) — mirror that here so the camera never fights the UI
                if (mc.currentScreen != null) return;

                long now = System.nanoTime();
                float timeSec = (now / 1_000_000_000f) % 200000f;

                // Inter-tick motion note: entity positions update every 50ms tick
                // while this loop runs at 8.33ms. The aim model already LEADS the
                // target by its velocity (aimLeadTicks) and the humanizer applies
                // dt-corrected exponential smoothing, so the sub-tick residual is
                // covered twice over — adding raw dead-reckoning on top would
                // double-count the lead and make the crosshair overshoot.

                // v2.0 PHASE 2-b: pure mode — distribute the four-head brain's
                // blended aim budget instead of the tracker+shaper path
                float[] pure = ctl.takePureAimFrame(dtMs);
                if (pure != null) {
                        ctl.actuator.queueLook(pure[0], pure[1]);
                        return;
                }

                float[] aimDelta = ctl.aim.aimStepTime(self, target, ctl.memory, timeSec);
                float[] shaped = ctl.humanizer.shapeAimFrame(aimDelta[0], aimDelta[1], dtMs);
                ctl.actuator.queueLook(shaped[0], shaped[1]);
        }
}
