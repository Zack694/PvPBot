package dev.z.pvpbot.bot;

import dev.z.pvpbot.mixin.MinecraftClientAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;

/**
 * The actuator: translates the policy's virtual inputs into the exact same
 * code paths a physical keyboard/mouse produce.
 *
 * - WASD / sprint / jump: KeyBinding#setPressed (what the OS key event sets).
 * - Left click: MinecraftClient#doAttack (what handleInputEvents calls on click).
 * - Mouse: injected through Mouse#onCursorPos — the raw GLFW callback — with
 *   deltas quantized to whole hardware counts on the player's sensitivity grid.
 *
 * No packets are ever constructed, intercepted or modified by this mod.
 */
public final class Actuator {

        private static final int K_W = 0, K_S = 1, K_A = 2, K_D = 3, K_SPRINT = 4, K_JUMP = 5, K_SNEAK = 6;
        private static final int KEY_COUNT = 7;

        private final MinecraftClient mc;
        private final boolean[] botOwned = new boolean[KEY_COUNT]; // keys the BOT pressed
        private int jumpHoldTicks = 0;
        private static volatile int lastMoveCombo = ActionSpace.M_NONE;
        // v1.0.11: the bot clicks through MinecraftClient#doAttack — the attack
        // KeyBinding is never pressed, so the keystrokes HUD's LMB cell never
        // lit up. This static pulse is set on every bot click (like a real
        // mouse button flash) and decays in tickPost().
        private static volatile int attackVisualTicks = 0;

        /** True while a bot click is "held" visually (2-tick flash per click). */
        public static boolean attackVisualActive() {
                return attackVisualTicks > 0;
        }

        /** Movement combo the bot is currently outputting (read by Perception). */
        public static int currentMoveCombo() {
                return lastMoveCombo;
        }

        public Actuator(MinecraftClient mc) {
                this.mc = mc;
        }

        // ---------------------------------------------------------------- keys

        public void setMove(int moveCombo) {
                lastMoveCombo = moveCombo;
                set(mc.options.forwardKey, moveCombo == ActionSpace.M_W || moveCombo == ActionSpace.M_WA || moveCombo == ActionSpace.M_WD, K_W);
                set(mc.options.backKey, moveCombo == ActionSpace.M_S || moveCombo == ActionSpace.M_SA || moveCombo == ActionSpace.M_SD, K_S);
                set(mc.options.leftKey, moveCombo == ActionSpace.M_A || moveCombo == ActionSpace.M_WA || moveCombo == ActionSpace.M_SA, K_A);
                set(mc.options.rightKey, moveCombo == ActionSpace.M_D || moveCombo == ActionSpace.M_WD || moveCombo == ActionSpace.M_SD, K_D);
        }

        public void setSprint(boolean on) {
                set(mc.options.sprintKey, on, K_SPRINT);
        }

        public void setSneak(boolean on) {
                set(mc.options.sneakKey, on, K_SNEAK);
        }

        /** v2.3: a bot jump tap is being held right now (ObsV4 own-keys feature). */
        public boolean jumpHeld() {
                return jumpHoldTicks > 0;
        }

        public void setJump(boolean on) {
                if (on) {
                        jumpHoldTicks = 2; // hold for 2 ticks like a human tap, then release
                }
        }

        /**
         * v1.0.12 HARD LEFT-CLICK BLOCK — the final, unbypassable gate sits
         * HERE, at the physical click itself: a left click is only sent while
         * the player's CURRENT attack-cooldown percentage is inside the
         * configured [min, max] window (or the meter is fully charged — a
         * 100% meter can never climb back into the window). A blocked click
         * is not queued, delayed or retried by the actuator; the controller's
         * per-tick loop "tries again instantly" on the next tick with the
         * fresh percentage. Vanilla's own attackCooldown spam guard and reach
         * checks apply on top, exactly as for a human.
         */
        public boolean attack(float minPct, float maxPct) {
                if (mc.attackCooldown > 0) {
                        return false;
                }
                if (mc.player != null) {
                        float pct = mc.player.getAttackCooldownProgress(0.0f);
                        boolean inWindow = pct >= minPct && (pct <= maxPct || pct >= 0.999f);
                        if (!inWindow) {
                                return false; // left click blocked — try again next tick
                        }
                }
                attackVisualTicks = 2; // v1.0.11: light the LMB keystroke cell
                MinecraftClientAccessor acc = (MinecraftClientAccessor) mc;
                return acc.pvpbot$invokeDoAttack();
        }

        /**
         * Ownership rule: the bot may PRESS any key, but may only RELEASE keys
         * it pressed itself. A key the user is physically holding is never
         * cancelled mid-hold — their WASD keeps working while the bot fights.
         */
        private void set(KeyBinding kb, boolean on, int keyIdx) {
                if (kb == null) return;
                if (on) {
                        if (!kb.isPressed()) kb.setPressed(true);
                        botOwned[keyIdx] = true;
                } else if (botOwned[keyIdx]) {
                        if (kb.isPressed()) kb.setPressed(false);
                        botOwned[keyIdx] = false;
                }
        }

        private KeyBinding keyFor(int idx) {
                if (mc.options == null) return null;
                switch (idx) {
                        case K_W: return mc.options.forwardKey;
                        case K_S: return mc.options.backKey;
                        case K_A: return mc.options.leftKey;
                        case K_D: return mc.options.rightKey;
                        case K_SPRINT: return mc.options.sprintKey;
                        case K_JUMP: return mc.options.jumpKey;
                        case K_SNEAK: return mc.options.sneakKey;
                        default: return null;
                }
        }

        // ---------------------------------------------------------------- mouse

        // v2.0 PHASE 1: accumulated by the 120Hz aim thread, drained by the
        // render thread once per frame. Double precision + synchronized so
        // sub-frame fractional deltas are never rounded away or torn.
        private double pendingYawDeg = 0.0;   // desired mouse-right degrees (+ = right)
        private double pendingPitchDeg = 0.0; // desired mouse-down degrees (+ = down)

        public synchronized void queueLook(float yawDeg, float pitchDeg) {
                pendingYawDeg += yawDeg;
                pendingPitchDeg += pitchDeg;
        }

        /** Atomically take (and zero) the accumulated look deltas. */
        private synchronized double[] drainLook() {
                double[] d = {pendingYawDeg, pendingPitchDeg};
                pendingYawDeg = 0.0;
                pendingPitchDeg = 0.0;
                return d;
        }

        /**
         * Converts pending degrees into mouse deltas and injects them through
         * the vanilla cursor-pos callback. Called ONCE PER RENDER FRAME on the
         * game/render thread (the 120Hz aim thread only accumulates degrees).
         *
         * v1.0.5: deltas are injected as FRACTIONAL double counts (like a real
         * high-DPI mouse — vanilla's own pipeline consumes doubles end to end).
         * v2.0: the pending accumulator is double precision and thread-safe,
         * so 120Hz-computed motion survives frame boundaries exactly.
         */
        public void applyMouse(boolean gridSnap) {
                if (mc.player == null || mc.getWindow() == null) {
                        return;
                }
                double[] pend = drainLook();
                float sens = mc.options.getMouseSensitivity().getValue().floatValue(); // 0..1
                float f = sens * 0.6f + 0.2f;
                float countsPerDeg = 1f / (f * f * f * 8.0f * 0.15f); // deg per count inverted
                double yawCounts = pend[0] * countsPerDeg;
                double pitchCounts = pend[1] * countsPerDeg;
                if (Math.abs(yawCounts) < 1e-4 && Math.abs(pitchCounts) < 1e-4) {
                        return;
                }
                try {
                        dev.z.pvpbot.mixin.MouseAccessor mouse = (dev.z.pvpbot.mixin.MouseAccessor) mc.mouse;
                        double x = mouse.pvpbot$x();
                        double y = mouse.pvpbot$y();
                        // real GLFW mouse-move event, processed by the vanilla input
                        // pipeline — fractional deltas preserved end to end
                        mouse.pvpbot$onCursorPos(mc.getWindow().getHandle(), x + yawCounts, y + pitchCounts);
                } catch (Throwable ignored) {
                        // never crash the game over input injection
                }
        }

        public void tickPost() {
                if (attackVisualTicks > 0) attackVisualTicks--; // v1.0.11 LMB flash decay
                if (jumpHoldTicks > 0) {
                        jumpHoldTicks--;
                        set(mc.options.jumpKey, jumpHoldTicks > 0, K_JUMP);
                }
        }

        /**
         * Release every BOT-OWNED key. Called on stop / death / screen open.
         * Keys the user is physically holding are left untouched.
         */
        public void releaseAll() {
                for (int i = 0; i < KEY_COUNT; i++) {
                        if (botOwned[i]) {
                                KeyBinding kb = keyFor(i);
                                if (kb != null && kb.isPressed()) kb.setPressed(false);
                                botOwned[i] = false;
                        }
                }
                synchronized (this) {
                        pendingYawDeg = 0.0;
                        pendingPitchDeg = 0.0;
                }
                jumpHoldTicks = 0;
                attackVisualTicks = 0; // v1.0.11
        }
}
