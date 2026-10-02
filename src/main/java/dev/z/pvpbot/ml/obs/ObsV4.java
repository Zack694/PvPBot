package dev.z.pvpbot.ml.obs;

/**
 * v2.3 — OBSERVATION v4: the 100-dim input of the four-head brain.
 *
 * Why a rewrite (audit of the v2.1 104-dim vector):
 *  - the opponent's velocity came from {@code getVelocity()}, which for a
 *    remote player only follows server knockback packets — it read ~0 while
 *    they ran, so closing speed, lead and strafe features were dead online;
 *  - several features were constants in real play: their aggression (never
 *    updated), s-tap counter (never incremented), post-hit probes (never
 *    called), my last-attack clock (never written), memory age (always 0);
 *  - the advanced block had logic bugs: the jump detector fired on "still in
 *    the air" instead of take-off, the forward "streak" measured the oldest
 *    forward tick instead of a streak, the "current" strafe sign was the
 *    OLDEST one, and the 60-tick swing window read a 20-tick buffer;
 *  - angles were linear in [-180, 180] (a discontinuity right behind you).
 *
 * v4 is built ONLY from {@link CombatFrame}s — plain numbers both the mod and
 * the offline simulator produce — and this exact algorithm is mirrored line
 * for line in {@code pretrain/v3/obs.py}. A JUnit parity test replays a
 * simulator recording through this class and demands the same vector, so a
 * brain trained offline for hours sees in-game what it learned on.
 *
 * Every feature is a MEASUREMENT. No strategy lives here.
 *
 * Usage: call {@link #tick(CombatFrame)} once per game tick while a target
 * exists, then {@link #build()} for the current observation.
 * {@link #resetEpisode()} at round start, {@link #resetOpponent()} when the
 * opponent changes.
 */
public final class ObsV4 {

        public static final int DIM = 100;
        public static final int VERSION = 4;

        private static final int H = 40; // history ring length (2 s)

        // ---------------------------------------------------------------- state
        private long t = 0;                     // ticks fed since construction
        private FighterFrame prevMe, prevThem;
        private double myVx, myVy, myVz, thVx, thVy, thVz;
        private double thSvx, thSvz;            // smoothed their horizontal velocity (EMA 0.5)
        private double prevHDist = -1;
        private double closing;                 // -d(horizontal dist)/dt, + = closing
        private boolean hasPrev = false;

        // latest frame (copied fields used by build)
        private final CombatFrame cur = new CombatFrame();

        // event clocks (tick of last event; -1e9 = never)
        private long lastMyHit = -1_000_000, lastTaken = -1_000_000, lastMySwing = -1_000_000;
        private long lastTheirSwing = -1_000_000, lastTheirJump = -1_000_000;
        private long episodeStart = 0;
        private int comboDealt, comboTaken;
        private float dmgDealt, dmgTaken, momentum;

        // their rhythm / profile (persists across rounds vs the same opponent)
        private boolean prevTheirSwinging, prevTheirGround = true;
        private int theirAirTicks;
        private float theirSwingInterval = 0f;  // EMA ticks between swings (0 = unknown)
        private float theirYawRate = 0f;
        private float prevTheirYaw;
        private boolean haveTheirYaw = false;
        private float resetRate = 0.3f;         // P(sprint reset after their swing)
        private float jumpResetRate = 0.3f;     // P(they jump (own take-off) within 12t of my hit)
        private float theirAccuracy = 0.4f;     // their hits / their swings
        private float theirSwingDist = 3.0f;    // EMA distance at their swings
        private float theirSpeed = 0.15f;       // EMA horizontal speed
        private float myAccuracy = 0.5f;        // my hits / my swings
        private long resetWatchUntil = -1;      // sprint-reset watch window after their swing
        private double resetWatchToward;        // their toward-speed at swing time
        private long jumpWatchUntil = -1;       // jump-reset watch window after I hit them
        private long accWatchSwing = -1;        // my swing awaiting hit confirmation
        private long theirAccWatch = -1;        // their swing awaiting hit confirmation

        // ring buffers (index = t % H)
        private final boolean[] rTheirSwingEdge = new boolean[H];
        private final boolean[] rTheirAir = new boolean[H];
        private final boolean[] rTheirSneak = new boolean[H];
        private final int[] rTheirStrafe = new int[H];   // -1/0/+1 lateral sign
        private final boolean[] rMySwing = new boolean[H];
        private final boolean[] rMyHit = new boolean[H];
        private final boolean[] rTaken = new boolean[H];
        private final boolean[] rMyJump = new boolean[H];
        private final int[] rMyFwd = new int[H];
        private final int[] rMyStr = new int[H];
        private final boolean[] rMySprint = new boolean[H];
        private int filled = 0;

        // ----------------------------------------------------------- lifecycle

        /** New round vs the same opponent: episode stats reset, profile kept. */
        public void resetEpisode() {
                episodeStart = t;
                comboDealt = comboTaken = 0;
                dmgDealt = dmgTaken = momentum = 0f;
                lastMyHit = lastTaken = lastMySwing = -1_000_000;
                lastTheirSwing = lastTheirJump = -1_000_000;
                hasPrev = false;
                prevMe = prevThem = null;
                myVx = myVy = myVz = thVx = thVy = thVz = thSvx = thSvz = 0;
                prevHDist = -1;
                closing = 0;
                theirAirTicks = 0;
                prevTheirSwinging = false;
                prevTheirGround = true;
                haveTheirYaw = false;
                resetWatchUntil = jumpWatchUntil = accWatchSwing = theirAccWatch = -1;
                filled = 0;
                java.util.Arrays.fill(rTheirSwingEdge, false);
                java.util.Arrays.fill(rTheirAir, false);
                java.util.Arrays.fill(rTheirSneak, false);
                java.util.Arrays.fill(rTheirStrafe, 0);
                java.util.Arrays.fill(rMySwing, false);
                java.util.Arrays.fill(rMyHit, false);
                java.util.Arrays.fill(rTaken, false);
                java.util.Arrays.fill(rMyJump, false);
                java.util.Arrays.fill(rMyFwd, 0);
                java.util.Arrays.fill(rMyStr, 0);
                java.util.Arrays.fill(rMySprint, false);
                java.util.Arrays.fill(rTheirRadial, 0f);
                lastTheirToward = lastTheirLateral = 0;
        }

        /** New opponent: profile priors restored too. */
        public void resetOpponent() {
                resetEpisode();
                theirSwingInterval = 0f;
                theirYawRate = 0f;
                resetRate = 0.3f;
                jumpResetRate = 0.3f;
                theirAccuracy = 0.4f;
                theirSwingDist = 3.0f;
                theirSpeed = 0.15f;
                myAccuracy = 0.5f;
        }

        // ---------------------------------------------------------------- tick

        public void tick(CombatFrame f) {
                t++;
                copyInto(f, cur);
                FighterFrame me = f.me, th = f.them;

                // ---- velocities from position deltas (teleport-guarded)
                if (hasPrev) {
                        double mdx = me.x - prevMe.x, mdy = me.y - prevMe.y, mdz = me.z - prevMe.z;
                        double tdx = th.x - prevThem.x, tdy = th.y - prevThem.y, tdz = th.z - prevThem.z;
                        boolean tele = Math.abs(mdx) > 2 || Math.abs(mdz) > 2 || Math.abs(mdy) > 3
                                        || Math.abs(tdx) > 2 || Math.abs(tdz) > 2 || Math.abs(tdy) > 3;
                        if (tele) {
                                myVx = myVy = myVz = thVx = thVy = thVz = thSvx = thSvz = 0;
                                prevHDist = -1;
                        } else {
                                myVx = mdx;
                                myVy = mdy;
                                myVz = mdz;
                                thVx = tdx;
                                thVy = tdy;
                                thVz = tdz;
                                thSvx += 0.5 * (thVx - thSvx);
                                thSvz += 0.5 * (thVz - thSvz);
                        }
                }
                prevMe = me.copy();
                prevThem = th.copy();
                hasPrev = true;

                double dx = th.x - me.x, dz = th.z - me.z;
                double hd = Math.sqrt(dx * dx + dz * dz);
                closing = prevHDist < 0 ? 0 : (prevHDist - hd);
                prevHDist = hd;
                double inv = hd > 1e-6 ? 1.0 / hd : 0.0;
                double ux = dx * inv, uz = dz * inv;               // unit me -> them
                double theirToward = -(thVx * ux + thVz * uz);     // + = closing on me
                double theirLateral = thVx * uz - thVz * ux;       // signed circling component
                double tSpeed = Math.sqrt(thVx * thVx + thVz * thVz);

                // ---- their swing rhythm (rising edge of the swing animation)
                boolean edge = th.swinging && !prevTheirSwinging;
                prevTheirSwinging = th.swinging;
                if (edge) {
                        long gap = t - lastTheirSwing;
                        if (lastTheirSwing > -1_000_000 && gap >= 3 && gap <= 60) {
                                theirSwingInterval = theirSwingInterval <= 0f ? gap
                                                : theirSwingInterval + 0.25f * (gap - theirSwingInterval);
                        }
                        lastTheirSwing = t;
                        theirSwingDist += 0.2f * ((float) hd - theirSwingDist);
                        // sprint-reset watch: does their closing speed collapse right after?
                        if (resetWatchUntil < t) {
                                resetWatchUntil = t + 8;
                                resetWatchToward = theirToward;
                        }
                        if (theirAccWatch >= 0) {
                                theirAccuracy += 0.15f * (0f - theirAccuracy); // previous swing never landed
                        }
                        theirAccWatch = t;
                }
                if (resetWatchUntil >= t && resetWatchUntil - t < 8) {
                        if (resetWatchToward > 0.12 && theirToward < resetWatchToward - 0.10) {
                                resetRate += 0.2f * (1f - resetRate);
                                resetWatchUntil = -1;
                        } else if (resetWatchUntil == t) {
                                resetRate += 0.2f * (0f - resetRate);
                                resetWatchUntil = -1;
                        }
                }

                // ---- their air / jumps (OWN take-off edge — a knockback launch
                //      arrives with hurtTime 9-10 and is not a jump)
                boolean air = !th.onGround;
                if (air && prevTheirGround && thVy > 0.2 && th.hurtTime < 9) {
                        lastTheirJump = t;
                        if (jumpWatchUntil >= t) {
                                jumpResetRate += 0.2f * (1f - jumpResetRate);
                                jumpWatchUntil = -1;
                        }
                }
                if (jumpWatchUntil >= 0 && jumpWatchUntil < t) {
                        jumpResetRate += 0.2f * (0f - jumpResetRate);
                        jumpWatchUntil = -1;
                }
                prevTheirGround = th.onGround;
                theirAirTicks = air ? theirAirTicks + 1 : 0;

                // ---- their look activity
                if (haveTheirYaw) {
                        float dyaw = Math.abs(wrap(th.yaw - prevTheirYaw));
                        theirYawRate += 0.2f * (dyaw - theirYawRate);
                }
                prevTheirYaw = th.yaw;
                haveTheirYaw = true;
                theirSpeed += 0.1f * ((float) tSpeed - theirSpeed);

                // ---- events
                if (f.iSwung) {
                        lastMySwing = t;
                        if (accWatchSwing >= 0) {
                                myAccuracy += 0.15f * (0f - myAccuracy);
                        }
                        accWatchSwing = t;
                }
                if (f.iHitThem) {
                        lastMyHit = t;
                        comboDealt++;
                        comboTaken = 0;
                        dmgDealt += f.dmgDealt;
                        jumpWatchUntil = t + 12;
                        if (accWatchSwing >= 0) {
                                myAccuracy += 0.15f * (1f - myAccuracy);
                                accWatchSwing = -1;
                        }
                }
                if (accWatchSwing >= 0 && t - accWatchSwing > 8) {
                        myAccuracy += 0.15f * (0f - myAccuracy);
                        accWatchSwing = -1;
                }
                if (f.iWasHit) {
                        lastTaken = t;
                        comboTaken++;
                        comboDealt = 0;
                        dmgTaken += f.dmgTaken;
                        if (theirAccWatch >= 0) {
                                theirAccuracy += 0.15f * (1f - theirAccuracy);
                                theirAccWatch = -1;
                        }
                }
                if (theirAccWatch >= 0 && t - theirAccWatch > 8) {
                        theirAccuracy += 0.15f * (0f - theirAccuracy);
                        theirAccWatch = -1;
                }
                if (t - lastTaken > 40) comboTaken = 0;
                if (t - lastMyHit > 40) comboDealt = 0;
                float bal = (f.iHitThem ? f.dmgDealt : 0f) - (f.iWasHit ? f.dmgTaken : 0f);
                momentum += 0.05f * (bal - momentum);

                // ---- ring buffers
                int i = (int) (t % H);
                rTheirSwingEdge[i] = edge;
                rTheirAir[i] = air;
                rTheirSneak[i] = th.sneaking;
                rTheirStrafe[i] = theirLateral > 0.03 ? 1 : (theirLateral < -0.03 ? -1 : 0);
                rTheirRadial[i] = (float) theirToward;
                rMySwing[i] = f.iSwung;
                rMyHit[i] = f.iHitThem;
                rTaken[i] = f.iWasHit;
                rMyJump[i] = f.myJumpHeld || (!me.onGround && myVy > 0.2);
                rMyFwd[i] = moveFwd(f.myMove);
                rMyStr[i] = moveStr(f.myMove);
                rMySprint[i] = me.sprinting;
                if (filled < H) filled++;

                lastTheirToward = theirToward;
                lastTheirLateral = theirLateral;
        }

        private double lastTheirToward, lastTheirLateral;

        // --------------------------------------------------------------- build

        public float[] build() {
                float[] s = new float[DIM];
                CombatFrame f = cur;
                FighterFrame me = f.me, th = f.them;

                double yr = Math.toRadians(me.yaw);
                double fx = -Math.sin(yr), fz = Math.cos(yr);   // my forward
                double rx = -fz, rz = fx;                       // my right
                double dx = th.x - me.x, dz = th.z - me.z;
                double hd = Math.sqrt(dx * dx + dz * dz);

                // ---- A. self (0..18)
                s[0] = (float) clamp(me.health / 20.0, 0, 1.5);
                s[1] = (float) clamp(me.absorption / 20.0, 0, 1);
                s[2] = (float) clamp(f.food / 20.0, 0, 1);
                s[3] = (float) clamp(f.myCharge, 0, 1);
                s[4] = b(me.sprinting);
                s[5] = b(me.onGround);
                s[6] = b(me.sneaking);
                s[7] = (float) clamp(myVy / 0.4, -2, 2);
                s[8] = (float) clamp((myVx * fx + myVz * fz) / 0.28, -2, 2);
                s[9] = (float) clamp((myVx * rx + myVz * rz) / 0.28, -2, 2);
                s[10] = (float) clamp(me.hurtTime / 10.0, 0, 1);
                s[11] = since(lastMyHit, 10);
                s[12] = since(lastTaken, 10);
                s[13] = since(lastMySwing, 10);
                s[14] = (float) clamp(comboDealt / 4.0, 0, 1.5);
                s[15] = (float) clamp(comboTaken / 4.0, 0, 1.5);
                s[16] = moveFwd(f.myMove);
                s[17] = moveStr(f.myMove);
                s[18] = b(f.myJumpHeld);

                // ---- B. relative geometry (19..36)
                double myReach = reachDist(me.x, me.eyeY(), me.z, th);
                double theirReach = reachDist(th.x, th.eyeY(), th.z, me);
                s[19] = (float) clamp(hd / 3.0, 0, 4);
                s[20] = (float) clamp(myReach / 3.0, 0, 4);
                s[21] = b(myReach <= 3.0);
                s[22] = (float) clamp(theirReach / 3.0, 0, 4);
                s[23] = (float) clamp((th.y - me.y) / 2.0, -2, 2);
                double bearing = Math.toDegrees(Math.atan2(-dx, dz));
                double relYaw = wrap(bearing - me.yaw);
                s[24] = (float) Math.sin(Math.toRadians(relYaw));
                s[25] = (float) Math.cos(Math.toRadians(relYaw));
                s[26] = (float) (relYaw / 180.0);
                double chestY = th.y + th.height * 0.6;
                double pitchNeed = -Math.toDegrees(Math.atan2(chestY - me.eyeY(), Math.max(1e-6, hd)));
                s[27] = (float) clamp(wrap(pitchNeed - me.pitch) / 45.0, -2, 2);
                s[28] = b(f.crosshairOnTarget);
                s[29] = b(f.los);
                s[30] = (float) clamp(Math.toDegrees(Math.atan2(th.width * 0.5, Math.max(0.3, hd))) / 10.0, 0, 2);
                s[31] = (float) clamp(closing / 0.5, -2, 2);
                double inv = hd > 1e-6 ? 1.0 / hd : 0.0;
                double ux = dx * inv, uz = dz * inv;
                s[32] = (float) clamp((myVx * ux + myVz * uz) / 0.3, -2, 2);
                s[33] = (float) clamp(lastTheirToward / 0.3, -2, 2);
                s[34] = (float) clamp((thVx * fx + thVz * fz) / 0.3, -2, 2);
                s[35] = (float) clamp((thVx * rx + thVz * rz) / 0.3, -2, 2);
                s[36] = (float) clamp(thVy / 0.4, -2, 2);

                // ---- C. them (37..52)
                s[37] = (float) clamp(th.health / 20.0, 0, 1.5);
                s[38] = (float) clamp(th.absorption / 20.0, 0, 1);
                s[39] = (float) clamp((me.health - th.health) / 20.0, -1, 1);
                s[40] = b(th.onGround);
                s[41] = b(th.sprinting);
                s[42] = b(th.sneaking);
                s[43] = (float) clamp(th.hurtTime / 10.0, 0, 1);
                s[44] = b(th.swinging);
                s[45] = since(lastTheirSwing, 10);
                s[46] = (float) clamp((t - lastTheirSwing) / 12.5, 0, 1);
                double tlx = -Math.sin(Math.toRadians(th.yaw)) * Math.cos(Math.toRadians(th.pitch));
                double tly = -Math.sin(Math.toRadians(th.pitch));
                double tlz = Math.cos(Math.toRadians(th.yaw)) * Math.cos(Math.toRadians(th.pitch));
                double mcx = me.x - th.x, mcy = (me.y + me.height * 0.6) - th.eyeY(), mcz = me.z - th.z;
                double ml = Math.max(1e-6, Math.sqrt(mcx * mcx + mcy * mcy + mcz * mcz));
                double theirAimErr = Math.toDegrees(Math.acos(clamp((tlx * mcx + tly * mcy + tlz * mcz) / ml, -1, 1)));
                s[47] = (float) clamp(theirAimErr / 90.0, 0, 2);
                s[48] = (float) clamp(theirYawRate / 30.0, 0, 2);
                double theirBearing = Math.toDegrees(Math.atan2(-(me.x - th.x), me.z - th.z));
                s[49] = (float) Math.sin(Math.toRadians(wrap(theirBearing - th.yaw)));
                s[50] = b(theirReach <= 3.0 && theirAimErr < 25.0);
                s[51] = b(!th.onGround && thVy < 0);
                s[52] = (float) clamp(theirAirTicks / 10.0, 0, 1.5);

                // ---- D. prediction (53..58)
                for (int k = 0; k < 2; k++) {
                        double lead = k == 0 ? 3 : 6;
                        double px = th.x + thSvx * lead - me.x, pz = th.z + thSvz * lead - me.z;
                        s[53 + 2 * k] = (float) clamp((px * fx + pz * fz) / 3.0, -3, 3);
                        s[54 + 2 * k] = (float) clamp((px * rx + pz * rz) / 3.0, -3, 3);
                }
                double pdx = (th.x + thSvx * 3) - (me.x + myVx * 3), pdz = (th.z + thSvz * 3) - (me.z + myVz * 3);
                s[57] = (float) clamp(Math.sqrt(pdx * pdx + pdz * pdz) / 3.0, 0, 4);
                s[58] = closing > 0.02 ? (float) clamp((hd - 3.0) / closing / 20.0, 0, 1) : 1f;

                // ---- E. opponent rhythm (59..74)
                s[59] = (float) clamp(countB(rTheirSwingEdge, 10) / 3.0, 0, 1.5);
                s[60] = (float) clamp(countB(rTheirSwingEdge, 40) / 6.0, 0, 1.5);
                s[61] = (float) clamp(theirSwingInterval / 20.0, 0, 2);
                s[62] = (float) (countB(rTheirAir, 20) / 20.0);
                s[63] = (float) (countB(rTheirSneak, 20) / 20.0);
                int sign = rTheirStrafe[(int) (t % H)];
                s[64] = sign;
                s[65] = (float) clamp(strafeStreak() / 20.0, 0, 1.5);
                s[66] = (float) clamp(strafeChanges(40) / 8.0, 0, 1.5);
                s[67] = (float) clamp(towardStreak(true) / 20.0, 0, 1.5);
                s[68] = (float) clamp(towardStreak(false) / 20.0, 0, 1.5);
                s[69] = resetRate;
                s[70] = jumpResetRate;
                s[71] = since(lastTheirJump, 20);
                s[72] = theirAccuracy;
                s[73] = (float) clamp(theirSwingDist / 3.0, 0, 2);
                s[74] = (float) clamp(theirSpeed / 0.28, 0, 2);

                // ---- F. my history (75..82)
                s[75] = (float) clamp(countB(rMySwing, 20) / 3.0, 0, 1.5);
                s[76] = (float) clamp(countB(rMyHit, 40) / 4.0, 0, 1.5);
                s[77] = (float) (countB(rMyJump, 20) / 20.0);
                s[78] = (float) meanI(rMyFwd, 10);
                s[79] = (float) meanI(rMyStr, 10);
                s[80] = (float) clamp(changesI(rMyStr, 20) / 6.0, 0, 1.5);
                s[81] = (float) (countB(rMySprint, 10) / 10.0);
                s[82] = myAccuracy;

                // ---- G. terrain (83..92)
                for (int k = 0; k < 8; k++) {
                        s[83 + k] = f.terrain[k];
                }
                s[91] = f.dropAhead;
                s[92] = f.ceilingLow;

                // ---- H. match (93..99)
                s[93] = since(episodeStart, 600);
                s[94] = (float) clamp(dmgDealt / 20.0, 0, 2);
                s[95] = (float) clamp(dmgTaken / 20.0, 0, 2);
                s[96] = (float) clamp((dmgDealt - dmgTaken) / 10.0, -2, 2);
                s[97] = (float) clamp(momentum / 0.5, -2, 2);
                s[98] = (float) clamp(countB(rTaken, 40) / 4.0, 0, 1.5);
                s[99] = 1f;
                return s;
        }

        // ------------------------------------------------------------- helpers

        /** 1 - exp(-dt / tau): 0 right after the event, -> 1 long after (never-seen = 1). */
        private float since(long eventTick, double tau) {
                long dt = t - eventTick;
                if (dt < 0) dt = 0;
                if (dt > 100000) return 1f;
                return (float) (1.0 - Math.exp(-dt / tau));
        }

        private int countB(boolean[] r, int n) {
                int c = 0, m = Math.min(n, filled);
                for (int k = 0; k < m; k++) {
                        if (r[idx(k)]) c++;
                }
                return c;
        }

        private double meanI(int[] r, int n) {
                int m = Math.min(n, filled);
                if (m == 0) return 0;
                double sum = 0;
                for (int k = 0; k < m; k++) sum += r[idx(k)];
                return sum / m;
        }

        private int changesI(int[] r, int n) {
                int m = Math.min(n, filled), c = 0, last = 0;
                for (int k = 0; k < m; k++) {
                        int v = r[idx(k)];
                        if (v != 0) {
                                if (last != 0 && v != last) c++;
                                last = v;
                        }
                }
                return c;
        }

        /** Consecutive most-recent ticks with the current non-zero strafe sign. */
        private int strafeStreak() {
                int m = filled;
                if (m == 0) return 0;
                int s0 = rTheirStrafe[idx(0)];
                if (s0 == 0) return 0;
                int c = 0;
                for (int k = 0; k < m; k++) {
                        if (rTheirStrafe[idx(k)] == s0) c++;
                        else break;
                }
                return c;
        }

        private int strafeChanges(int n) {
                return changesI(rTheirStrafe, n);
        }

        /** Consecutive most-recent ticks closing on me (toward=true) or retreating. */
        private int towardStreak(boolean toward) {
                // derived from closing sign history: reuse the swing-free measure
                // of THEIR radial velocity, kept in its own ring
                int m = filled, c = 0;
                for (int k = 0; k < m; k++) {
                        float v = rTheirRadial[idx(k)];
                        if (toward ? v > 0.05f : v < -0.05f) c++;
                        else break;
                }
                return c;
        }

        private final float[] rTheirRadial = new float[H];

        /** ring index of the k-th most recent entry (0 = this tick). */
        private int idx(int k) {
                return (int) (((t - k) % H + H) % H);
        }

        private static float b(boolean v) {
                return v ? 1f : 0f;
        }

        private static double clamp(double v, double lo, double hi) {
                return v < lo ? lo : (v > hi ? hi : v);
        }

        private static double wrap(double deg) {
                double d = deg % 360.0;
                if (d >= 180.0) d -= 360.0;
                if (d < -180.0) d += 360.0;
                return d;
        }

        private static float wrap(float deg) {
                return (float) wrap((double) deg);
        }

        public static int moveFwd(int move) {
                return (move == 1 || move == 5 || move == 6) ? 1 : (move == 2 || move == 7 || move == 8) ? -1 : 0;
        }

        public static int moveStr(int move) {
                return (move == 4 || move == 6 || move == 8) ? 1 : (move == 3 || move == 5 || move == 7) ? -1 : 0;
        }

        /**
         * Distance from an eye point to the NEAREST point of a fighter's hitbox
         * (vanilla interaction reach is measured eye -> hitbox, so a target is
         * hittable up to ~3.3 blocks center-to-center).
         */
        public static double reachDist(double ex, double ey, double ez, FighterFrame o) {
                double hw = o.width * 0.5;
                double cx = clamp(ex, o.x - hw, o.x + hw);
                double cy = clamp(ey, o.y, o.y + o.height);
                double cz = clamp(ez, o.z - hw, o.z + hw);
                double ax = ex - cx, ay = ey - cy, az = ez - cz;
                return Math.sqrt(ax * ax + ay * ay + az * az);
        }

        private static void copyInto(CombatFrame src, CombatFrame dst) {
                copyF(src.me, dst.me);
                copyF(src.them, dst.them);
                dst.myCharge = src.myCharge;
                dst.food = src.food;
                dst.myMove = src.myMove;
                dst.myJumpHeld = src.myJumpHeld;
                System.arraycopy(src.terrain, 0, dst.terrain, 0, 8);
                dst.dropAhead = src.dropAhead;
                dst.ceilingLow = src.ceilingLow;
                dst.los = src.los;
                dst.crosshairOnTarget = src.crosshairOnTarget;
                dst.iSwung = src.iSwung;
                dst.iHitThem = src.iHitThem;
                dst.dmgDealt = src.dmgDealt;
                dst.iCrit = src.iCrit;
                dst.iWasHit = src.iWasHit;
                dst.dmgTaken = src.dmgTaken;
        }

        private static void copyF(FighterFrame s, FighterFrame d) {
                d.x = s.x;
                d.y = s.y;
                d.z = s.z;
                d.yaw = s.yaw;
                d.pitch = s.pitch;
                d.health = s.health;
                d.absorption = s.absorption;
                d.width = s.width;
                d.height = s.height;
                d.onGround = s.onGround;
                d.sprinting = s.sprinting;
                d.sneaking = s.sneaking;
                d.swinging = s.swinging;
                d.usingItem = s.usingItem;
                d.hurtTime = s.hurtTime;
        }
}
