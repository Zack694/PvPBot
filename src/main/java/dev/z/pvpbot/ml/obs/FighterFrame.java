package dev.z.pvpbot.ml.obs;

/**
 * v2.3 — one fighter's observable state for ONE game tick, as plain data.
 *
 * Deliberately Minecraft-free: the mod fills it from entities
 * ({@code dev.z.pvpbot.bot.FrameAdapter}), the offline simulator
 * (pretrain/v3/obs.py) fills the exact same fields from its physics state.
 * Because both sides feed the SAME builder logic, an offline-trained brain
 * sees in-game exactly the numbers it learned on.
 *
 * Velocity is intentionally NOT a field: remote players' client-side
 * velocity only follows server velocity packets (knockback), so it reads ~0
 * while they run. {@link ObsV4} derives every velocity from POSITION DELTAS
 * between consecutive frames instead — identical in the sim and in-game.
 */
public final class FighterFrame {

        /** Feet position (blocks). */
        public double x, y, z;
        /** Look yaw (MC convention: 0 = +Z, 90 = -X) and pitch (+ = down), degrees. */
        public float yaw, pitch;
        public float health = 20f, absorption = 0f;
        /** Hitbox (0.6 x 1.8 standing, 0.6 x 1.5 sneaking). */
        public float width = 0.6f, height = 1.8f;
        public boolean onGround = true, sprinting, sneaking, swinging, usingItem;
        public int hurtTime;

        public double eyeY() {
                return y + (sneaking ? 1.27 : 1.62);
        }

        public FighterFrame copy() {
                FighterFrame f = new FighterFrame();
                f.x = x;
                f.y = y;
                f.z = z;
                f.yaw = yaw;
                f.pitch = pitch;
                f.health = health;
                f.absorption = absorption;
                f.width = width;
                f.height = height;
                f.onGround = onGround;
                f.sprinting = sprinting;
                f.sneaking = sneaking;
                f.swinging = swinging;
                f.usingItem = usingItem;
                f.hurtTime = hurtTime;
                return f;
        }
}
