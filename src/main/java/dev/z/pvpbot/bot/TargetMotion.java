package dev.z.pvpbot.bot;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;

/**
 * v2.3 — the opponent's REAL velocity, measured from position deltas.
 *
 * A remote player's client-side {@code getVelocity()} is not their motion:
 * OtherClientPlayerEntity only lerps toward velocities the server sends in
 * velocity packets (knockback), so it reads ~0 while they sprint, strafe or
 * flee. This tracker samples the target's position once per game tick.
 *
 * Two outputs:
 *  - {@link #of}: the raw per-tick displacement (what ObsV4 and the
 *    simulator measure; used for movement memory / tactics);
 *  - {@link #smoothed}: an EMA of it (alpha 0.35, ~3-tick time constant).
 *    Remote positions arrive in network-paced steps, so the raw delta
 *    jitters tick to tick (0.0 / 0.5 / 0.1 …). Anything that steers the
 *    CROSSHAIR (aim lead, aim-net features) must read the smoothed value —
 *    feeding the raw delta into the lead made the aim swing ("going crazy").
 *
 * Thread-safe for readers (the 120 Hz aim thread reads it).
 */
public final class TargetMotion {

        private static volatile int trackedId = Integer.MIN_VALUE;
        private static volatile Vec3d vel = Vec3d.ZERO;
        private static volatile Vec3d smooth = Vec3d.ZERO;
        private static double px, py, pz;
        private static long lastTick = Long.MIN_VALUE;

        private TargetMotion() {
        }

        /** Call once per game tick with the current target (null clears). */
        public static void update(Entity target, long tick) {
                if (target == null) {
                        trackedId = Integer.MIN_VALUE;
                        vel = Vec3d.ZERO;
                        smooth = Vec3d.ZERO;
                        lastTick = Long.MIN_VALUE;
                        return;
                }
                if (tick == lastTick) {
                        return;
                }
                double x = target.getX(), y = target.getY(), z = target.getZ();
                if (target.getId() != trackedId || tick - lastTick != 1) {
                        vel = Vec3d.ZERO;
                        smooth = Vec3d.ZERO;
                } else {
                        double dx = x - px, dy = y - py, dz = z - pz;
                        // teleport / respawn guard (same as ObsV4)
                        if (Math.abs(dx) > 2 || Math.abs(dz) > 2 || Math.abs(dy) > 3) {
                                vel = Vec3d.ZERO;
                                smooth = Vec3d.ZERO;
                        } else {
                                vel = new Vec3d(dx, dy, dz);
                                Vec3d s = smooth;
                                smooth = new Vec3d(s.x + 0.35 * (dx - s.x), s.y + 0.35 * (dy - s.y), s.z + 0.35 * (dz - s.z));
                        }
                }
                trackedId = target.getId();
                px = x;
                py = y;
                pz = z;
                lastTick = tick;
        }

        /** Raw per-tick velocity of {@code e}: measured for the tracked target, vanilla otherwise. */
        public static Vec3d of(Entity e) {
                if (e == null) {
                        return Vec3d.ZERO;
                }
                if (e.getId() == trackedId) {
                        return vel;
                }
                return e.getVelocity();
        }

        /** Smoothed per-tick velocity (aim use). Vanilla velocity for untracked entities. */
        public static Vec3d smoothed(Entity e) {
                if (e == null) {
                        return Vec3d.ZERO;
                }
                if (e.getId() == trackedId) {
                        return smooth;
                }
                return e.getVelocity();
        }
}
