package dev.z.pvpbot.bot;

import dev.z.pvpbot.ml.obs.FighterFrame;
import net.minecraft.entity.LivingEntity;

/**
 * v2.3 — copies a Minecraft entity into the plain {@link FighterFrame} the
 * ObsV4 builder consumes. The ONLY place entity state crosses into the v2
 * observation, so the offline simulator can produce the identical numbers.
 */
public final class FrameAdapter {

        private FrameAdapter() {
        }

        public static void fill(FighterFrame f, LivingEntity e) {
                f.x = e.getX();
                f.y = e.getY();
                f.z = e.getZ();
                f.yaw = e.getYaw();
                f.pitch = e.getPitch();
                f.health = e.getHealth();
                f.absorption = e.getAbsorptionAmount();
                f.width = e.getWidth();
                f.height = e.getHeight();
                f.onGround = e.isOnGround();
                f.sprinting = e.isSprinting();
                f.sneaking = e.isSneaking();
                f.swinging = e.handSwinging;
                f.usingItem = e.isUsingItem();
                f.hurtTime = e.hurtTime;
        }
}
