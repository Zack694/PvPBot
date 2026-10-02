package dev.z.pvpbot.bot;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;

/**
 * v2.3 — the opponent's REAL velocity, measured from position deltas.
 *
 * A remote player's client-side {@code getVelocity()} is not their motion:
 * OtherClientPlayerEntity only lerps toward velocities the server sends in
 * velocity packets (knockback), so it reads ~0 while they sprint, strafe or
 * flee. Every lead, closing-speed and movement-memory feature built on it was
 * blind in multiplayer. This tracker samples the target's position once per
 * game tick and exposes the per-tick displacement instead (exactly what the
 * offline simulator measures, see ObsV4).
 *
 * Thread-safe for readers (the 120 Hz aim thread reads it).
 */
public final class TargetMotion {

        private static volatile int trackedId = Integer.MIN_VALUE;
        private static volatile Vec3d vel = Vec3d.ZERO;
        private static double px, py, pz;
        private static long lastTick = Long.MIN_VALUE;

        private TargetMotion() {
        }

        /** Call once per game tick with the current target (null clears). */
        public static void update(Entity target, long tick) {
                if (target == null) {
                        trackedId = Integer.MIN_VALUE;
                        vel = Vec3d.ZERO;
                        lastTick = Long.MIN_VALUE;
                        return;
                }
                if (tick == lastTick) {
                        return;
                }
                double x = target.getX(), y = target.getY(), z = target.getZ();
                if (target.getId() != trackedId || tick - lastTick != 1) {
                        vel = Vec3d.ZERO;
                } else {
                        double dx = x - px, dy = y - py, dz = z - pz;
                        // teleport / respawn guard (same as ObsV4)
                        if (Math.abs(dx) > 2 || Math.abs(dz) > 2 || Math.abs(dy) > 3) {
                                vel = Vec3d.ZERO;
                        } else {
                                vel = new Vec3d(dx, dy, dz);
                        }
                }
                trackedId = target.getId();
                px = x;
                py = y;
                pz = z;
                lastTick = tick;
        }

        /** Per-tick velocity of {@code e}: measured for the tracked target, vanilla otherwise. */
        public static Vec3d of(Entity e) {
                if (e == null) {
                        return Vec3d.ZERO;
                }
                if (e.getId() == trackedId) {
                        return vel;
                }
                return e.getVelocity();
        }
}
