package dev.z.pvpbot.ml.obs;

/**
 * v2.3 — everything {@link ObsV4} needs for one tick, from MY perspective.
 * Plain data, Minecraft-free (see {@link FighterFrame}).
 *
 * Event flags describe what happened during THIS tick and are produced by
 * the same sources on both sides (mod: HitWatcher / the click path;
 * simulator: its combat resolution).
 */
public final class CombatFrame {

        public final FighterFrame me = new FighterFrame();
        public final FighterFrame them = new FighterFrame();

        /** My vanilla attack-cooldown progress, 0..1. */
        public float myCharge = 1f;
        /** My food level, 0..20. */
        public float food = 20f;
        /** Move combo my keys are executing right now (ActionSpace M_*, 0..8). */
        public int myMove = 0;
        /** My jump key is held / a bot jump tap is active. */
        public boolean myJumpHeld;

        /** 8 horizontal wall probes relative to my facing (0 fwd, 2 right, 4 back, 6 left), 0/1. */
        public final float[] terrain = new float[8];
        public float dropAhead, ceilingLow;
        /** Clear block line of sight eye -> their chest. */
        public boolean los = true;
        /** A click right now would hit them (vanilla-identical ray vs hitbox inside reach). */
        public boolean crosshairOnTarget;

        // ---- events this tick
        /** I swung (any click that reached the game). */
        public boolean iSwung;
        /** My swing/click landed damage on them this tick. */
        public boolean iHitThem;
        public float dmgDealt;
        /** That landed hit was a critical (I was falling, not sprinting). */
        public boolean iCrit;
        /** I took damage this tick. */
        public boolean iWasHit;
        public float dmgTaken;
}
